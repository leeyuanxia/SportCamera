package cn.leeyuanxia.sportcamera.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder.Companion.isConfigFrame
import cn.leeyuanxia.sportcamera.hardware.storage.MotionPhotoStorageManager
import cn.leeyuanxia.sportcamera.util.DebugLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * 动态照片合成器
 *
 * 负责将预录环形缓冲中的 H.264 帧合成为动态照片文件：
 * 1. 从帧中提取中间帧作为 JPEG 封面图
 * 2. 将所有帧合成为 MP4 视频数据
 * 3. 通过 MotionPhotoStorageManager 组合 JPEG + MP4 + XMP 元数据并保存
 *
 * JPEG 提取流程：帧 → 临时 MP4 文件 → MediaMetadataRetriever.getFrameAtTime → Bitmap → JPEG
 * 这是最稳定的方案，兼容所有设备。
 */
class MotionPhotoAssembler(
    private val context: Context,
    private val storageManager: MotionPhotoStorageManager,
) {
    companion object {
        private const val TAG = "MotionPhotoAssembler"
        private const val JPEG_QUALITY = 95
        private const val TEMP_MP4_NAME = "motion_photo_temp.mp4"
    }

    /**
     * 合成动态照片
     *
     * @param frames 从环形缓冲 dump 的编码帧（约 2 秒）
     * @param width 编码宽度
     * @param height 编码高度
     * @param csd0Data SPS 数据
     * @param csd1Data PPS 数据
     * @param rotation 旋转角度（0 或 90）
     * @param onProgress 进度回调
     * @return 保存后的文件 Uri 字符串
     */
    fun assemble(
        frames: List<RingBufferRecorder.EncodedFrame>,
        width: Int,
        height: Int,
        csd0Data: ByteArray?,
        csd1Data: ByteArray?,
        rotation: Int,
        onProgress: (Float) -> Unit = {},
    ): String {
        val dataFrames = frames.filter { !it.isConfigFrame() }
        if (dataFrames.isEmpty()) {
            throw IllegalStateException("无数据帧可用于动态照片")
        }

        DebugLog.d(TAG, "动态照片合成开始: ${dataFrames.size} 数据帧, ${width}x${height}, rotation=$rotation")

        // 步骤 1: 将帧写入临时 MP4 文件
        val tempMp4 = File(context.cacheDir, TEMP_MP4_NAME)
        try {
            writeFramesToMp4(dataFrames, width, height, csd0Data, csd1Data, rotation, tempMp4)
            onProgress(0.4f)

            // 步骤 2: 从临时 MP4 提取中间帧 JPEG
            val jpegBytes = extractMiddleFrameAsJpeg(tempMp4.absolutePath, rotation)
            DebugLog.d(TAG, "JPEG 封面提取完成: ${jpegBytes.size}B")
            onProgress(0.6f)

            // 步骤 3: 读取 MP4 字节数据
            val mp4Bytes = tempMp4.readBytes()
            DebugLog.d(TAG, "MP4 视频数据: ${mp4Bytes.size}B")
            onProgress(0.8f)

            // 步骤 4: 组合 JPEG + MP4 + XMP 并保存
            val result = storageManager.saveMotionPhoto(jpegBytes, mp4Bytes, width, height)
            onProgress(1.0f)

            DebugLog.d(TAG, "动态照片保存成功: $result")
            return result

        } finally {
            // 清理临时文件
            if (tempMp4.exists()) {
                tempMp4.delete()
            }
        }
    }

    /**
     * 将 H.264 编码帧写入 MP4 文件
     *
     * 复用 VideoStorageManager.assembleToMp4 的逻辑模式，
     * 但仅处理视频轨（无音频），PTS 归一化从 0 开始。
     */
    private fun writeFramesToMp4(
        dataFrames: List<RingBufferRecorder.EncodedFrame>,
        width: Int,
        height: Int,
        csd0: ByteArray?,
        csd1: ByteArray?,
        rotation: Int,
        outputFile: File,
    ) {
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        try {
            // 添加视频轨
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
            DebugLog.d(TAG, "MP4 合成参数: ${width}x${height}, csd0=${csd0?.size ?: "null"}B, csd1=${csd1?.size ?: "null"}B, frames=${dataFrames.size}")
            if (csd0 != null) {
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }
            if (csd1 != null) {
                videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            }
            val videoTrackIndex = muxer.addTrack(videoFormat)

            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            muxer.start()

            // 写入视频帧（PTS 归一化从 0 开始）
            val baseTimeUs = dataFrames.first().presentationTimeUs
            var lastPtsUs = -1L
            var written = 0

            for (frame in dataFrames) {
                var ptsUs = frame.presentationTimeUs - baseTimeUs
                if (ptsUs < 0) continue

                // 保证 PTS 单调递增
                val writePtsUs = if (ptsUs <= lastPtsUs) lastPtsUs + 1 else ptsUs
                lastPtsUs = writePtsUs

                val bufferInfo = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = frame.data.size
                    presentationTimeUs = writePtsUs
                    flags = frame.flags
                }

                muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(frame.data), bufferInfo)
                written++
            }

            muxer.stop()
            DebugLog.d(TAG, "临时 MP4 写入完成: ${written}帧, 时长=${lastPtsUs / 1000}ms")

        } catch (e: Exception) {
            DebugLog.e(TAG, "MP4 合成失败", e)
            throw e
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从 MP4 文件中提取中间帧并转为 JPEG
     *
     * @param mp4Path MP4 文件路径
     * @param rotation 旋转角度（用于旋转 Bitmap）
     * @return JPEG 字节数组
     */
    private fun extractMiddleFrameAsJpeg(mp4Path: String, rotation: Int): ByteArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4Path)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 2000L
            val middleTimeUs = durationMs * 500L

            val bitmap = retriever.getFrameAtTime(middleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return createPlaceholderJpeg(rotation)

            DebugLog.d(TAG, "提取帧: ${bitmap.width}x${bitmap.height}, duration=${durationMs}ms")
            val finalBitmap = if (rotation == 90 && bitmap.width > bitmap.height) {
                rotateBitmap(bitmap, 90f)
            } else {
                bitmap
            }
            val jpegStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegStream)
            if (finalBitmap !== bitmap) finalBitmap.recycle()
            bitmap.recycle()
            return jpegStream.toByteArray()
        } catch (e: Exception) {
            DebugLog.e(TAG, "帧提取失败: ${e.message}")
            return createPlaceholderJpeg(rotation)
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    /**
     * 生成纯色占位 JPEG（帧提取失败时使用）
     */
    private fun createPlaceholderJpeg(rotation: Int): ByteArray {
        val w = if (rotation == 90) 1280 else 720
        val h = if (rotation == 90) 720 else 1280
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.DKGRAY)
        val jpegStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegStream)
        bitmap.recycle()
        DebugLog.d(TAG, "占位 JPEG 已生成: ${w}x${h}")
        return jpegStream.toByteArray()
    }

    /**
     * 旋转 Bitmap
     */
    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
    }
}

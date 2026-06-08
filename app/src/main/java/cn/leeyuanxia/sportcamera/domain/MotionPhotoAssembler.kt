package cn.leeyuanxia.sportcamera.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import cn.leeyuanxia.sportcamera.hardware.audio.AudioRecorder
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
 * 负责将预录环形缓冲中的 H.264 视频帧 + AAC 音频帧合成为动态照片文件：
 * 1. 将视频帧 + 音频帧合成为带音轨的 MP4
 * 2. 提取中间关键帧作为 JPEG 封面图
 * 3. 通过 MotionPhotoStorageManager 组合 JPEG + MP4 + XMP 元数据并保存
 *
 * JPEG 提取流程：帧 → 临时 MP4 → MediaMetadataRetriever.getFrameAtTime → Bitmap → JPEG
 */
class MotionPhotoAssembler(
    private val context: Context,
    private val storageManager: MotionPhotoStorageManager,
) {
    companion object {
        private const val TAG = "MotionPhotoAssembler"
        private const val JPEG_QUALITY = 98
        private const val TEMP_MP4_NAME = "motion_photo_temp.mp4"
    }

    /**
     * 合成动态照片（含音频）
     *
     * @param frames 从环形缓冲 dump 的编码帧（约 2 秒）
     * @param width 编码宽度
     * @param height 编码高度
     * @param csd0Data SPS 数据
     * @param csd1Data PPS 数据
     * @param rotation 旋转角度（0 或 90）
     * @param audioFrames 从音频环形缓冲 dump 的 AAC 音频帧
     * @param audioCsdData 音频 CSD（AudioSpecificConfig）
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
        audioFrames: List<AudioRecorder.EncodedAudioFrame> = emptyList(),
        audioCsdData: ByteArray? = null,
        onProgress: (Float) -> Unit = {},
    ): String {
        val dataFrames = frames.filter { !it.isConfigFrame() }
        if (dataFrames.isEmpty()) {
            throw IllegalStateException("无数据帧可用于动态照片")
        }

        DebugLog.d(TAG, "动态照片合成开始: ${dataFrames.size} 视频帧, ${audioFrames.size} 音频帧, ${width}x${height}, rotation=$rotation")

        // 步骤 1: 将视频帧 + 音频帧写入临时 MP4 文件
        val tempMp4 = File(context.cacheDir, TEMP_MP4_NAME)
        try {
            writeFramesToMp4(
                dataFrames = dataFrames,
                audioFrames = audioFrames,
                audioCsdData = audioCsdData,
                width = width,
                height = height,
                csd0 = csd0Data,
                csd1 = csd1Data,
                rotation = rotation,
                outputFile = tempMp4,
            )
            onProgress(0.4f)

            // 步骤 2: 从临时 MP4 提取中间帧 JPEG
            val jpegBytes = extractBestFrameAsJpeg(tempMp4.absolutePath, rotation)
            DebugLog.d(TAG, "JPEG 封面提取完成: ${jpegBytes.size}B")
            onProgress(0.6f)

            // 步骤 3: 读取 MP4 字节数据
            val mp4Bytes = tempMp4.readBytes()
            DebugLog.d(TAG, "MP4 视频数据: ${mp4Bytes.size}B (含${if (audioFrames.isNotEmpty()) "音轨" else "无音频"})")
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
     * 将 H.264 视频帧 + AAC 音频帧写入 MP4 文件
     *
     * 参考 VideoStorageManager.assembleToMp4 的实现模式，
     * 但仅处理单段帧（无 pre/post 分割），PTS 归一化从 0 开始。
     */
    private fun writeFramesToMp4(
        dataFrames: List<RingBufferRecorder.EncodedFrame>,
        audioFrames: List<AudioRecorder.EncodedAudioFrame>,
        audioCsdData: ByteArray?,
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
            // 1. 添加视频轨
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
            DebugLog.d(TAG, "MP4 合成参数: ${width}x${height}, csd0=${csd0?.size ?: "null"}B, " +
                "csd1=${csd1?.size ?: "null"}B, videoFrames=${dataFrames.size}, audioFrames=${audioFrames.size}")
            if (csd0 != null) {
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
            }
            if (csd1 != null) {
                videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            }
            val videoTrackIndex = muxer.addTrack(videoFormat)

            // 2. 添加音频轨（如果有音频帧）
            val audioTrackIndex = if (audioFrames.isNotEmpty()) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    audioCsdData?.takeIf { it.isNotEmpty() }?.let {
                        setByteBuffer("csd-0", ByteBuffer.wrap(it))
                    }
                }
                muxer.addTrack(audioFormat).also {
                    DebugLog.d(TAG, "音频轨已添加: index=$it, frames=${audioFrames.size}")
                }
            } else -1

            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            muxer.start()

            // 3. 写入视频帧（PTS 归一化从 0 开始）
            val baseTimeUs = dataFrames.first().presentationTimeUs
            var lastPtsUs = -1L
            var videoWritten = 0

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
                videoWritten++
            }

            DebugLog.d(TAG, "视频写入完成: ${videoWritten}帧, 时长=${lastPtsUs / 1000}ms")
            val videoDurationUs = lastPtsUs

            // 4. 写入音频帧（PTS 归一化从 0 开始）
            if (audioTrackIndex >= 0 && audioFrames.isNotEmpty()) {
                val audioDataFrames = audioFrames.filter {
                    it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                }
                if (audioDataFrames.isNotEmpty()) {
                    val audioFirstPtsUs = audioDataFrames.first().presentationTimeUs
                    var audioLastPtsUs = -1L
                    var audioWritten = 0

                    for (audioFrame in audioDataFrames) {
                        // 音频 PTS 归一化：从 0 开始
                        val normalizedUs = audioFrame.presentationTimeUs - audioFirstPtsUs

                        val writePtsUs = if (normalizedUs <= audioLastPtsUs) audioLastPtsUs + 1 else normalizedUs
                        audioLastPtsUs = writePtsUs

                        val bufferInfo = MediaCodec.BufferInfo().apply {
                            offset = 0
                            size = audioFrame.data.size
                            presentationTimeUs = writePtsUs
                            flags = audioFrame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv()
                        }

                        muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(audioFrame.data), bufferInfo)
                        audioWritten++
                    }

                    DebugLog.d(TAG, "音频写入完成: ${audioWritten}帧, PTS范围=0~${audioLastPtsUs / 1000}ms")

                    // 合理性检查：音频 PTS 不应远超视频时长
                    if (audioLastPtsUs > videoDurationUs + 5_000_000L) {
                        DebugLog.e(TAG, "音频 PTS 异常! audio=${audioLastPtsUs / 1000}ms >> video=${videoDurationUs / 1000}ms")
                    }
                }
            } else {
                DebugLog.d(TAG, "无音频轨（${audioFrames.size} 帧），生成纯视频 MP4")
            }

            muxer.stop()
            DebugLog.d(TAG, "临时 MP4 写入完成: ${videoWritten}视频帧, ${if (audioTrackIndex >= 0) "含音轨" else "无音频"}")

        } catch (e: Exception) {
            DebugLog.e(TAG, "MP4 合成失败", e)
            throw e
        } finally {
            try { muxer.release() } catch (_: Exception) {}
        }
    }

    /**
     * 从 MP4 文件中提取最佳帧并转为 JPEG
     *
     * 策略：优先提取靠近中间位置的关键帧（I-frame），
     * 关键帧在 H.264 中具有最高画质，避免 P/B 帧的压缩伪影。
     *
     * @param mp4Path MP4 文件路径
     * @param rotation 旋转角度（用于旋转 Bitmap）
     * @return JPEG 字节数组
     */
    private fun extractBestFrameAsJpeg(mp4Path: String, rotation: Int): ByteArray {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(mp4Path)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 2000L

            // 策略：尝试多个时间点，选择第一个成功的（有效的关键帧）
            // - 先试 1/3 位置（通常已过黑帧）
            // - 再试 1/2 位置
            // - 最后 fallback 到开头
            val candidateTimesUs = listOf(
                durationMs * 333L,    // 1/3 处
                durationMs * 500L,    // 1/2 处
                0L,                    // 开头
            )

            var bitmap: Bitmap? = null
            for (timeUs in candidateTimesUs) {
                bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    DebugLog.d(TAG, "提取帧成功: time=${timeUs / 1000}ms, ${bitmap.width}x${bitmap.height}")
                    break
                }
            }

            if (bitmap == null) {
                DebugLog.w(TAG, "所有时间点提取均失败，使用占位图")
                return createPlaceholderJpeg(rotation)
            }

            val finalBitmap = if (rotation == 90 && bitmap.width > bitmap.height) {
                rotateBitmap(bitmap, 90f)
            } else {
                bitmap
            }

            // 使用高质量 JPEG 压缩，减少二次压缩损失
            val jpegStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, jpegStream)

            if (finalBitmap !== bitmap) finalBitmap.recycle()
            bitmap.recycle()

            val result = jpegStream.toByteArray()
            DebugLog.d(TAG, "封面 JPEG: ${finalBitmap.width}x${finalBitmap.height}, quality=$JPEG_QUALITY, size=${result.size}B")
            return result

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

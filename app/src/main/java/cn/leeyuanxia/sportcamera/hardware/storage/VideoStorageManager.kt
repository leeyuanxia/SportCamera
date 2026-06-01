package cn.leeyuanxia.sportcamera.hardware.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder.Companion.isConfigFrame
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 视频存储管理器（MediaStore API，兼容 Scoped Storage）
 */
class VideoStorageManager(private val context: Context) {

    companion object {
        private const val DIR_NAME = "SportCamera"
        private const val FILE_PREFIX = "SPORT_"
        private const val FILE_EXTENSION = ".mp4"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        private const val TAG = "VideoStorage"
    }

    fun createOutputFile(): VideoOutput {
        val fileName = "${FILE_PREFIX}${DATE_FORMAT.format(Date())}${FILE_EXTENSION}"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DIR_NAME")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException("无法通过 MediaStore 创建视频文件")

        val pfd = context.contentResolver.openFileDescriptor(uri, "w")
            ?: throw IllegalStateException("无法打开 MediaStore 文件描述符")

        return VideoOutput(uri, pfd)
    }

    fun assembleToMp4(
        preFrames: List<RingBufferRecorder.EncodedFrame>,
        postFrames: List<RingBufferRecorder.EncodedFrame>,
        output: VideoOutput,
        width: Int,
        height: Int,
        csd0Data: ByteArray? = null,
        csd1Data: ByteArray? = null,
        rotation: Int = 0,
        onProgress: (Float) -> Unit = {},
    ) {
        val allFrames = preFrames + postFrames
        val dataFrames = allFrames.filter { !it.isConfigFrame() }

        if (dataFrames.isEmpty()) {
            Log.w(TAG, "无数据帧，跳过合成")
            try { context.contentResolver.delete(output.uri, null, null) } catch (_: Exception) {}
            try { output.pfd.close() } catch (_: Exception) {}
            onProgress(1.0f)
            return
        }

        // 优先用传入的 csd-0/csd-1，兜底从帧提取
        val csd0 = csd0Data?.takeIf { it.isNotEmpty() }
            ?: postFrames.firstOrNull { it.isConfigFrame() }?.data
            ?: preFrames.firstOrNull { it.isConfigFrame() }?.data
        val csd1 = csd1Data?.takeIf { it.isNotEmpty() }

        Log.d(TAG, "合成: pre=${preFrames.size}, post=${postFrames.size}, data=${dataFrames.size}, ${width}x${height}, csd0=${csd0?.size ?: 0}B, csd1=${csd1?.size ?: 0}B")

        val muxer = MediaMuxer(output.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxSuccess = false

        try {
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )

            if (csd0 != null) {
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                Log.d(TAG, "csd-0 已设置: ${csd0.size}B")
            }
            if (csd1 != null) {
                videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                Log.d(TAG, "csd-1 已设置: ${csd1.size}B")
            }

            val trackIndex = muxer.addTrack(videoFormat)

            // 竖屏录制时设置旋转提示，播放器自动转 90°
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
                Log.d(TAG, "rotation 已设置: ${rotation}°")
            }

            muxer.start()

            val baseTimeUs = dataFrames.first().presentationTimeUs
            var processed = 0
            var lastPtsUs = -1L

            for (frame in dataFrames) {
                val ptsUs = frame.presentationTimeUs - baseTimeUs
                if (ptsUs < 0) continue

                val writePtsUs = if (ptsUs <= lastPtsUs) lastPtsUs + 1 else ptsUs
                lastPtsUs = writePtsUs

                val bufferInfo = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = frame.data.size
                    presentationTimeUs = writePtsUs
                    flags = frame.flags
                }

                muxer.writeSampleData(trackIndex, ByteBuffer.wrap(frame.data), bufferInfo)
                processed++
                onProgress(processed.toFloat() / dataFrames.size)
            }

            Log.d(TAG, "写入完成: ${processed}帧, 准备 stop")
            muxer.stop()
            muxSuccess = true
            Log.d(TAG, "合成完成: ${processed}帧")
        } catch (e: Exception) {
            Log.e(TAG, "合成失败", e)
            try { context.contentResolver.delete(output.uri, null, null) } catch (_: Exception) {}
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            try { output.pfd.close() } catch (_: Exception) {}

            // 成功时标记为完成
            if (muxSuccess) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }.also { values ->
                            context.contentResolver.update(output.uri, values, null, null)
                        }
                    } catch (_: Exception) {}
                }
                try { context.contentResolver.notifyChange(output.uri, null) } catch (_: Exception) {}
                Log.d(TAG, "视频已保存到相册: ${output.uri}")
            }
        }
    }

    class VideoOutput(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
    )
}

package cn.leeyuanxia.sportcamera.hardware.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import cn.leeyuanxia.sportcamera.hardware.audio.AudioRecorder
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

    /**
     * 合成 MP4（视频 + 音频）
     *
     * 时间戳同步策略：
     * - 视频帧 PTS 来自相机硬件时钟，归一化从 0 开始
     * - 音频帧 PTS 来自 System.nanoTime()，需要映射到视频时间轴
     * - 映射方式：audioPtsUs - audioStartTimeUs + videoFirstFramePtsUs - baseTimeUs
     * - 简化：videoFirstFramePtsUs == baseTimeUs，所以偏移 = audioPtsUs - audioStartTimeUs
     */
    fun assembleToMp4(
        preFrames: List<RingBufferRecorder.EncodedFrame>,
        postFrames: List<RingBufferRecorder.EncodedFrame>,
        audioFrames: List<AudioRecorder.EncodedAudioFrame>,
        audioStartTimeUs: Long,
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

        Log.d(TAG, "合成: pre=${preFrames.size}, post=${postFrames.size}, data=${dataFrames.size}, audio=${audioFrames.size}, ${width}x${height}")

        val muxer = MediaMuxer(output.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxSuccess = false

        try {
            // 1. 添加视频轨
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
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
                }
                muxer.addTrack(audioFormat).also {
                    Log.d(TAG, "音频轨已添加: index=$it, ${audioFrames.size} 帧")
                }
            } else -1

            // 竖屏录制时设置旋转提示
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }

            muxer.start()

            // 3. 写入视频帧（PTS 归一化从 0 开始）
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

                muxer.writeSampleData(videoTrackIndex, ByteBuffer.wrap(frame.data), bufferInfo)
                processed++
                onProgress(processed.toFloat() / (dataFrames.size + audioFrames.size))
            }

            // 4. 写入音频帧（PTS 映射到视频时间轴）
            if (audioTrackIndex >= 0 && audioFrames.isNotEmpty()) {
                var audioLastPtsUs = lastPtsUs
                for (audioFrame in audioFrames) {
                    // 映射：音频 PTS 相对于录制开始时刻的偏移
                    val audioOffsetUs = audioFrame.presentationTimeUs - audioStartTimeUs
                    if (audioOffsetUs < 0) continue

                    val writePtsUs = if (audioOffsetUs <= audioLastPtsUs) audioLastPtsUs + 1 else audioOffsetUs
                    audioLastPtsUs = writePtsUs

                    val bufferInfo = MediaCodec.BufferInfo().apply {
                        offset = 0
                        size = audioFrame.data.size
                        presentationTimeUs = writePtsUs
                        flags = audioFrame.flags
                    }

                    muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(audioFrame.data), bufferInfo)
                    processed++
                    onProgress(processed.toFloat() / (dataFrames.size + audioFrames.size))
                }
            }

            Log.d(TAG, "写入完成: ${processed}帧 (${dataFrames.size} 视频 + ${audioFrames.size} 音频)")
            muxer.stop()
            muxSuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "合成失败", e)
            try { context.contentResolver.delete(output.uri, null, null) } catch (_: Exception) {}
        } finally {
            try { muxer.release() } catch (_: Exception) {}
            try { output.pfd.close() } catch (_: Exception) {}

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

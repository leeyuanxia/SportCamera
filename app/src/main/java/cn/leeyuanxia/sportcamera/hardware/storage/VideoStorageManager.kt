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
import cn.leeyuanxia.sportcamera.util.DebugLog
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

    /**
     * 创建输出文件
     *
     * @param resolutionLabel 分辨率标签（如 "720p"、"1080p"、"4K"）
     * @param fps 帧率
     */
    fun createOutputFile(resolutionLabel: String = "", fps: Int = 0): VideoOutput {
        val resTag = if (resolutionLabel.isNotEmpty() && fps > 0) {
            "_${resolutionLabel}_${fps}fps"
        } else ""
        val fileName = "${FILE_PREFIX}${DATE_FORMAT.format(Date())}$resTag${FILE_EXTENSION}"

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
     * - 预录音频 PTS：frame.pts - preAudioStartUs（从 0 开始）
     * - 后录音频 PTS：frame.pts - audioStartTimeUs + preVideoDurationUs（从预录视频末尾开始）
     * - preVideoDurationUs = preFrames 最后一帧 PTS - 第一帧 PTS
     */
    fun assembleToMp4(
        preFrames: List<RingBufferRecorder.EncodedFrame>,
        postFrames: List<RingBufferRecorder.EncodedFrame>,
        preAudioFrames: List<AudioRecorder.EncodedAudioFrame> = emptyList(),
        preAudioStartUs: Long = 0L,
        audioFrames: List<AudioRecorder.EncodedAudioFrame>,
        audioStartTimeUs: Long,
        audioCsdData: ByteArray? = null,
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
        val allAudioFrames = preAudioFrames + audioFrames

        if (dataFrames.isEmpty()) {
            DebugLog.w(TAG, "无数据帧，跳过合成")
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

        DebugLog.d(TAG, "合成: pre=${preFrames.size}, post=${postFrames.size}, data=${dataFrames.size}, preAudio=${preAudioFrames.size}, postAudio=${audioFrames.size}, ${width}x${height}")

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

            // 2. 添加音频轨（如果有任意音频帧）
            val audioTrackIndex = if (allAudioFrames.isNotEmpty()) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 1
                ).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(
                        MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC
                    )
                    // 设置音频 CSD（AudioSpecificConfig），某些设备的 MediaMuxer 需要
                    audioCsdData?.takeIf { it.isNotEmpty() }?.let {
                        setByteBuffer("csd-0", ByteBuffer.wrap(it))
                        DebugLog.d(TAG, "音频 CSD 已设置到 MediaFormat: ${it.size}B")
                    }
                }
                muxer.addTrack(audioFormat).also {
                    DebugLog.d(TAG, "音频轨已添加: index=$it, preAudio=${preAudioFrames.size}, postAudio=${audioFrames.size}")
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

            // 预录帧数量（用于检测 pre→post 交界）
            val preFrameCount = preFrames.filter { !it.isConfigFrame() }.size
            // transition gap 偏移量 — 在交界处检测到的时间跳变，后续帧统一减去此偏移
            var junctionOffsetUs = 0L

            for ((index, frame) in dataFrames.withIndex()) {
                var ptsUs = frame.presentationTimeUs - baseTimeUs
                if (ptsUs < 0) continue

                // 核心修复：消除 pre→post 交界处的 transition gap
                // 预录和后录使用不同的编码器实例，中间有 transition gap（几十~几百ms），
                // 如果不处理，播放器在交界处会暂停等待 → 画面卡顿
                if (index == preFrameCount && preFrameCount > 0 && lastPtsUs >= 0) {
                    val gapUs = ptsUs - lastPtsUs
                    val estimatedIntervalUs = if (preFrameCount >= 2) {
                        // 用 pre 帧的平均间隔估算
                        val preSpan = dataFrames[preFrameCount - 1].presentationTimeUs - dataFrames[0].presentationTimeUs
                        preSpan / (preFrameCount - 1)
                    } else 33_333L  // 30fps 默认

                    if (gapUs > estimatedIntervalUs * 2) {
                        // 检测到 transition gap → 记录偏移量，后续所有 post 帧减去
                        junctionOffsetUs = gapUs - estimatedIntervalUs
                        DebugLog.d(TAG, "交界处 gap=${gapUs/1000}ms, 帧间隔≈${estimatedIntervalUs/1000}ms, 消除偏移=${junctionOffsetUs/1000}ms")
                    }
                }

                // 应用 junction 偏移
                ptsUs -= junctionOffsetUs

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
                onProgress(processed.toFloat() / (dataFrames.size + allAudioFrames.size))
            }

            DebugLog.d(TAG, "视频写入完成: ${processed}帧, pre=${preFrameCount}, post=${dataFrames.size - preFrameCount}, 总长=${lastPtsUs/1000}ms, junctionOffset=${junctionOffsetUs/1000}ms")

            // 4. 写入音频帧
            if (audioTrackIndex >= 0 && allAudioFrames.isNotEmpty()) {
                // 预录视频的原始时长（音频是连续录制的，pre/post 之间无 gap，不需要修正）
                val preVideoDurationUs = if (preFrames.isNotEmpty()) {
                    val preDataFrames = preFrames.filter { !it.isConfigFrame() }
                    if (preDataFrames.size >= 2) {
                        preDataFrames.last().presentationTimeUs - preDataFrames.first().presentationTimeUs
                    } else 0L
                } else 0L

                DebugLog.d(TAG, "音频时间轴: 视频总长=${lastPtsUs/1000}ms, preVideo时长=${preVideoDurationUs/1000}ms, junctionOffset=${junctionOffsetUs/1000}ms, preAudio=${preAudioFrames.size}, postAudio=${audioFrames.size}")

                var audioLastPtsUs = -1L
                val videoDurationUs = lastPtsUs  // 视频轨最后帧的 PTS = 视频总时长

                // 4a. 写入预录音频帧（PTS 从 0 开始）
                if (preAudioFrames.isNotEmpty()) {
                    audioLastPtsUs = writeAudioFrames(
                        muxer = muxer,
                        trackIndex = audioTrackIndex,
                        frames = preAudioFrames,
                        ptsOffsetUs = 0L,
                        lastPtsUs = audioLastPtsUs,
                        maxPtsUs = videoDurationUs,  // 截断到视频时长，保证音画同步
                        onProgress = { processed++; onProgress(processed.toFloat() / (dataFrames.size + allAudioFrames.size)) },
                    )
                    DebugLog.d(TAG, "预录音频已写入: ${preAudioFrames.size} 帧")
                }

                // 4b. 写入后录音频帧（PTS 从 preVideoDurationUs 开始）
                if (audioFrames.isNotEmpty()) {
                    audioLastPtsUs = writeAudioFrames(
                        muxer = muxer,
                        trackIndex = audioTrackIndex,
                        frames = audioFrames,
                        ptsOffsetUs = preVideoDurationUs,
                        lastPtsUs = audioLastPtsUs,
                        maxPtsUs = videoDurationUs,  // 截断到视频时长，保证音画同步
                        onProgress = { processed++; onProgress(processed.toFloat() / (dataFrames.size + allAudioFrames.size)) },
                    )
                    DebugLog.d(TAG, "后录音频已写入: ${audioFrames.size} 帧")
                }
            }

            if (audioTrackIndex < 0) {
                DebugLog.w(TAG, "无音频轨: preAudio=${preAudioFrames.size}, postAudio=${audioFrames.size}")
            }
            DebugLog.d(TAG, "写入完成: ${processed}帧 (${dataFrames.size} 视频 + ${allAudioFrames.size} 音频)")
            muxer.stop()
            muxSuccess = true
        } catch (e: Exception) {
            DebugLog.e(TAG, "合成失败", e)
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
                DebugLog.d(TAG, "视频已保存到相册: ${output.uri}")
            }
        }
    }

    /**
     * 将音频帧写入 MediaMuxer
     *
     * @param muxer MediaMuxer 实例
     * @param trackIndex 音频轨索引
     * @param frames 音频帧列表
     * @param baseTimeUs 该段音频的 startTimeUs 基准
     * @param ptsOffsetUs 在最终时间轴上的偏移（预录=0，后录=preVideoDurationUs）
     * @param lastPtsUs 上一段的最后 PTS（保证单调性）
     * @param maxPtsUs 允许写入的最大 PTS（默认无限制）。超出此 PTS 的帧被丢弃，
     *                 用于音画同步：视频环形缓冲可能因 maxBytes 裁剪导致实际时长短于音频，
     *                 此时截断音频到视频时长，避免"视频已结束音频还在播"
     * @param onProgress 每写入一帧的回调
     * @return 最后写入的 PTS
     */
    private fun writeAudioFrames(
        muxer: MediaMuxer,
        trackIndex: Int,
        frames: List<AudioRecorder.EncodedAudioFrame>,
        ptsOffsetUs: Long,
        lastPtsUs: Long,
        maxPtsUs: Long = Long.MAX_VALUE,
        onProgress: () -> Unit,
    ): Long {
        if (frames.isEmpty()) return lastPtsUs

        // 过滤掉 CSD 配置帧（安全网：正常情况下 AudioRecorder 已过滤，但兜底处理）
        val dataFrames = frames.filter {
            it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        }
        if (dataFrames.isEmpty()) {
            DebugLog.w(TAG, "音频段无数据帧（全是CSD），跳过")
            return lastPtsUs
        }

        var currentLastPtsUs = lastPtsUs
        var written = 0
        var skippedByMaxPts = 0

        // 用第一帧 PTS 作为归一化基准
        // 很多设备上 MediaCodec AAC 编码器输出 PTS 从 0 开始，而非透传输入值
        val firstFramePtsUs = dataFrames.first().presentationTimeUs
        val lastFramePtsUs = dataFrames.last().presentationTimeUs
        DebugLog.d(TAG, "音频段: ${dataFrames.size}帧, rawPTS范围=${firstFramePtsUs}~${lastFramePtsUs}, 偏移=${ptsOffsetUs/1000}ms, maxPts=${if(maxPtsUs==Long.MAX_VALUE)"∞" else "${maxPtsUs/1000}ms"}")

        for (audioFrame in dataFrames) {
            val normalizedUs = audioFrame.presentationTimeUs - firstFramePtsUs
            val audioOffsetUs = normalizedUs + ptsOffsetUs

            // 音画同步：超出视频时长的音频帧不再写入
            // AAC 帧 PTS 单调递增，一旦超出即可 break
            if (audioOffsetUs > maxPtsUs) {
                skippedByMaxPts = dataFrames.size - written
                break
            }

            val writePtsUs = if (audioOffsetUs <= currentLastPtsUs) currentLastPtsUs + 1 else audioOffsetUs
            currentLastPtsUs = writePtsUs

            val bufferInfo = MediaCodec.BufferInfo().apply {
                offset = 0
                size = audioFrame.data.size
                presentationTimeUs = writePtsUs
                // 清除 BUFFER_FLAG_CODEC_CONFIG 标志，只保留 KEY_FRAME 等有效标志
                flags = audioFrame.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG.inv()
            }

            muxer.writeSampleData(trackIndex, ByteBuffer.wrap(audioFrame.data), bufferInfo)
            written++
            onProgress()
        }
        if (skippedByMaxPts > 0) {
            DebugLog.d(TAG, "音频截断到视频时长: 跳过 ${skippedByMaxPts} 帧（超出 maxPts=${maxPtsUs/1000}ms）")
        }
        DebugLog.d(TAG, "音频写入完成: ${written}帧, PTS范围=${ptsOffsetUs/1000}ms~${currentLastPtsUs/1000}ms")
        return currentLastPtsUs
    }

    class VideoOutput(
        val uri: Uri,
        val pfd: ParcelFileDescriptor,
    )
}

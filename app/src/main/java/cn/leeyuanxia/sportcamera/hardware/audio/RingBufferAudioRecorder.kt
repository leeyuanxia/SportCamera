package cn.leeyuanxia.sportcamera.hardware.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 音频环形缓冲录制器 — 待机时持续采集麦克风并编码 AAC，写入内存环形缓冲
 *
 * 与视频 [cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder] 对称的音频版本。
 * 唤醒时 dump 最近的音频帧，与后录音频拼接，确保合成视频前半段也有声音。
 *
 * 数据流：
 * AudioRecord (MIC, 44.1kHz, mono, PCM_16BIT)
 *   → MediaCodec AAC 编码器 (128kbps, AAC-LC)
 *   → ConcurrentLinkedDeque 环形缓冲（超容量时直接丢弃最旧帧）
 *
 * 内存占用：128kbps × 30s / 8 ≈ 480KB（可忽略）
 */
class RingBufferAudioRecorder(
    private val maxDurationSec: Int = 30,
    private val kwsManager: KwsManager? = null,
) {
    companion object {
        private const val TAG = "RingBufAudioRec"
        private const val SAMPLE_RATE = 44100
        private const val KWS_SAMPLE_RATE = 16000  // KWS 模型要求的采样率
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 128_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    // 帧类型直接使用 AudioRecorder.EncodedAudioFrame，与合成接口保持类型一致

    // 环形缓冲容量 ≈ 码率 × 时长 / 8
    private val maxBytes = (BIT_RATE.toLong() / 8 * maxDurationSec).toInt()
    private val buffer = ConcurrentLinkedDeque<AudioRecorder.EncodedAudioFrame>()

    @Volatile private var currentBytes = 0L
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null
    @Volatile private var isRunning = false

    /** 录制开始时的 nanoTime 基准（微秒），用于 PTS 计算 */
    @Volatile
    var startTimeUs: Long = 0L
        private set

    /** 音频 CSD（AudioSpecificConfig），从 INFO_OUTPUT_FORMAT_CHANGED 提取 */
    @Volatile
    var csdData: ByteArray? = null
        private set

    /**
     * 准备 AudioRecord 和 AAC 编码器
     */
    fun prepare() {
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = minBufferSize * 2

        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize,
        )

        // 检查 AudioRecord 是否初始化成功
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            DebugLog.e(TAG, "AudioRecord 初始化失败! state=${rec.state}, minBuf=$minBufferSize")
            rec.release()
            return
        }
        audioRecord = rec

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        DebugLog.d(TAG, "音频预录准备完成: AAC ${SAMPLE_RATE}Hz ${BIT_RATE / 1000}kbps, buffer=${bufferSize}B, 环形缓冲${maxDurationSec}s (~${maxBytes / 1024}KB)")
    }

    /**
     * 启动采集和编码
     */
    fun start() {
        val rec = audioRecord
        if (rec == null) {
            DebugLog.e(TAG, "启动失败: audioRecord 为 null")
            return
        }
        val enc = encoder
        if (enc == null) {
            DebugLog.e(TAG, "启动失败: encoder 为 null")
            return
        }

        startTimeUs = System.nanoTime() / 1000
        enc.start()
        rec.startRecording()

        // 检查 AudioRecord 是否真正开始录制
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            DebugLog.e(TAG, "AudioRecord 未进入录制状态! state=${rec.recordingState}")
        }

        isRunning = true
        DebugLog.d(TAG, "音频预录已启动: recordingState=${rec.recordingState}, startTimeUs=$startTimeUs")
    }

    /**
     * 采集 PCM 并送入编码器（在 IO 线程循环运行）
     *
     * 同时将 PCM 数据降采样后共享给 KwsManager（避免两个 AudioRecord 竞争麦克风）
     */
    suspend fun captureAndEncode() = withContext(Dispatchers.IO) {
        val record = audioRecord ?: run {
            DebugLog.e(TAG, "captureAndEncode: audioRecord 为 null！")
            return@withContext
        }
        val codec = encoder ?: run {
            DebugLog.e(TAG, "captureAndEncode: encoder 为 null！")
            return@withContext
        }
        val bufferSize = record.bufferSizeInFrames * 2 // PCM_16BIT = 2 bytes/sample
        val pcmBuffer = ByteArray(bufferSize)
        var feedCount = 0L
        var readErrorCount = 0L

        DebugLog.d(TAG, "captureAndEncode 开始: bufferSize=${pcmBuffer.size}, recordState=${record.state}, kws=$kwsManager")
        try {
            while (isRunning) {
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) {
                    readErrorCount++
                    if (readErrorCount <= 3 || readErrorCount % 100 == 0L) {
                        DebugLog.w(TAG, "音频预录 read 返回 $bytesRead (第${readErrorCount}次)")
                    }
                    continue
                }

                // 送入 AAC 编码器（分批写入，因为输入缓冲区可能小于 bytesRead）
                var offset = 0
                while (offset < bytesRead) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex < 0) break
                    val inputBuffer = codec.getInputBuffer(inputIndex) ?: break
                    val capacity = inputBuffer.capacity()
                    val toWrite = minOf(bytesRead - offset, capacity)

                    inputBuffer.clear()
                    inputBuffer.put(pcmBuffer, offset, toWrite)

                    val ptsUs = System.nanoTime() / 1000
                    codec.queueInputBuffer(inputIndex, 0, toWrite, ptsUs, 0)
                    offset += toWrite
                    feedCount++
                    if (feedCount == 1L || feedCount % 100 == 0L) {
                        DebugLog.d(TAG, "音频预录已喂入: $feedCount 次 (write=$toWrite), 缓冲帧: ${buffer.size}")
                    }
                }

                // 共享 PCM 数据给 KWS（降采样 44.1kHz → 16kHz）
                // feedPcm 是非 suspend 函数，不会阻塞采集循环
                kwsManager?.let { kws ->
                    try {
                        val sampleCount = bytesRead / 2
                        val kwsSamples = downsampleTo16k(pcmBuffer, sampleCount)
                        kws.feedPcm(kwsSamples)
                    } catch (e: Exception) {
                        // KWS 异常不应影响音频采集
                        DebugLog.w(TAG, "KWS feedPcm 异常: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                DebugLog.d(TAG, "音频预录采集协程被取消")
            } else {
                DebugLog.e(TAG, "音频预录采集异常: ${e.message}", e)
            }
        }
        // 不发送 EOS — 预录模式下通过 isRunning=false 退出，编码器由 stop() 释放
        DebugLog.d(TAG, "音频预录采集结束, 共喂入: $feedCount 次, read错误: $readErrorCount 次")
    }

    /**
     * 将 44.1kHz PCM_16BIT ByteArray 降采样为 16kHz ShortArray
     *
     * 使用线性插值：ratio = 44100/16000 ≈ 2.75625
     */
    private fun downsampleTo16k(pcmData: ByteArray, sampleCount44k: Int): ShortArray {
        val ratio = SAMPLE_RATE.toDouble() / KWS_SAMPLE_RATE
        val sampleCount16k = (sampleCount44k / ratio).toInt().coerceAtLeast(1)
        val result = ShortArray(sampleCount16k)

        for (i in 0 until sampleCount16k) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex

            val s1 = if (srcIndex < sampleCount44k) {
                val offset = srcIndex * 2
                (pcmData[offset].toInt() and 0xFF) or (pcmData[offset + 1].toInt() shl 8)
            } else 0

            val s2 = if (srcIndex + 1 < sampleCount44k) {
                val offset = (srcIndex + 1) * 2
                (pcmData[offset].toInt() and 0xFF) or (pcmData[offset + 1].toInt() shl 8)
            } else s1

            result[i] = (s1 + frac * (s2 - s1)).toInt().toShort()
        }
        return result
    }

    /**
     * 持续从编码器读取 AAC 帧并写入环形缓冲（在 IO 线程运行）
     */
    suspend fun drainEncoder() = withContext(Dispatchers.IO) {
        val codec = encoder ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()
        var drainCount = 0L

        DebugLog.d(TAG, "音频预录 drain 开始")
        var tryAgainCount = 0L
        try {
            while (isRunning) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)

                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            addToRingBuffer(AudioRecorder.EncodedAudioFrame(
                                data = data,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                flags = bufferInfo.flags,
                            ))
                            drainCount++
                            // 首帧详细日志（含 PTS）+ 每 100 帧汇总
                            if (drainCount == 1L) {
                                DebugLog.d(TAG, "音频预录首帧: pts=${bufferInfo.presentationTimeUs}, size=${bufferInfo.size}, config=$isConfig, tryAgain=$tryAgainCount")
                            }
                            if (drainCount % 100 == 0L) {
                                DebugLog.d(TAG, "音频预录 drain: $drainCount 帧, 缓冲: ${buffer.size}, 字节: $currentBytes, 最新pts=${bufferInfo.presentationTimeUs}")
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        tryAgainCount++
                        delay(1)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        DebugLog.d(TAG, "音频预录输出格式: $fmt")
                        // 提取 CSD（AudioSpecificConfig）
                        try {
                            fmt.getByteBuffer("csd-0")?.let { buf ->
                                csdData = ByteArray(buf.remaining()).also { buf.get(it) }
                                DebugLog.d(TAG, "音频预录 CSD 已提取: ${csdData?.size}B")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) {
                DebugLog.d(TAG, "音频预录 drain 被取消 (已drain${drainCount}帧)")
            } else {
                DebugLog.w(TAG, "音频预录 drain 异常: ${e.message}")
            }
        }
        DebugLog.d(TAG, "音频预录 drain 结束, 总计: $drainCount 帧, tryAgain: $tryAgainCount 次")
    }

    /**
     * 将帧写入环形缓冲，超容量时丢弃最旧帧
     *
     * 音频与视频不同：AAC 每帧独立解码，无需关键帧边界对齐，直接丢弃即可。
     */
    private fun addToRingBuffer(frame: AudioRecorder.EncodedAudioFrame) {
        buffer.addLast(frame)
        currentBytes += frame.data.size

        // 超容量 → 从头部丢弃
        while (currentBytes > maxBytes && buffer.size > 1) {
            val removed = buffer.removeFirst()
            currentBytes -= removed.data.size
        }
    }

    /**
     * 获取最近 N 毫秒的音频帧（过滤掉 CSD 配置帧）
     */
    fun dumpRecentFrames(durationMs: Long): List<AudioRecorder.EncodedAudioFrame> {
        val allFrames = buffer.toList()
        val totalSize = allFrames.size
        val configFrames = allFrames.count { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 }
        val dataFrames = allFrames.filter { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }

        DebugLog.d(TAG, "dumpRecentFrames(${durationMs}ms): 总帧数=$totalSize, CSD帧=$configFrames, 数据帧=${dataFrames.size}, isRunning=$isRunning")

        if (dataFrames.isEmpty()) {
            if (totalSize > 0) {
                DebugLog.w(TAG, "dumpRecentFrames: 仅有 ${totalSize} 个 CSD 帧，无音频数据帧！")
            } else {
                DebugLog.w(TAG, "dumpRecentFrames: 环形缓冲完全为空！")
            }
            return emptyList()
        }

        val latestTime = dataFrames.last().presentationTimeUs
        val earliestTime = dataFrames.first().presentationTimeUs
        val bufferDurationMs = (latestTime - earliestTime) / 1000
        val targetStartTime = latestTime - durationMs * 1000

        DebugLog.d(TAG, "dumpRecentFrames: 缓冲时长=${bufferDurationMs}ms, PTS范围=${earliestTime}~${latestTime}")

        return dataFrames.filter { it.presentationTimeUs >= targetStartTime }
    }

    /**
     * 获取所有缓冲帧（过滤掉 CSD 配置帧）
     */
    fun dumpAllFrames(): List<AudioRecorder.EncodedAudioFrame> =
        buffer.toList().filter { it.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 }

    /** 获取当前缓冲帧数（诊断用） */
    fun bufferSize(): Int = buffer.size

    /** 清空环形缓冲 */
    fun clear() {
        buffer.clear()
        currentBytes = 0
    }

    /**
     * 停止采集和编码
     */
    fun stop() {
        isRunning = false
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
            // MediaCodec stop 可能抛异常
        }
        encoder?.release()
        encoder = null
        DebugLog.d(TAG, "音频预录已停止")
    }

    /**
     * 完全释放资源
     */
    fun release() {
        stop()
        clear()
    }
}

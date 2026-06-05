package cn.leeyuanxia.sportcamera.hardware.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 音频录制编码器 — 采集麦克风 PCM 并编码为 AAC
 *
 * 数据流：
 * AudioRecord (44.1kHz, mono, PCM_16BIT)
 *   → dequeueInputBuffer() → queueInputBuffer()
 *   → MediaCodec AAC 编码器 (audio/mp4a-latm, 128kbps)
 *   → drainEncoder() → EncodedAudioFrame 列表
 *
 * 使用方式：
 * 1. prepare()  — 创建 AudioRecord + 配置编码器
 * 2. start()    — 启动采集和编码
 * 3. stop()     — 发送 EOS，等待编码完成
 * 4. getEncodedFrames() — 获取编码后的 AAC 帧
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 128_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    /** 编码后的音频帧 */
    data class EncodedAudioFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var isRunning = false

    /** 线程安全的帧缓冲区：drainEncoder()（IO线程）写入，dumpRecentFrames()（主线程）读取 */
    private val frames = ConcurrentLinkedDeque<EncodedAudioFrame>()

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
        // 创建 AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            minBufferSize * 2,
        )

        // 创建 AAC 编码器
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

        DebugLog.d(TAG, "音频编码器准备完成: AAC ${SAMPLE_RATE}Hz ${BIT_RATE / 1000}kbps mono")
    }

    /**
     * 启动采集和编码
     *
     * 返回 drain 协程的 Job（调用方应在 stop 后 join 它）
     */
    suspend fun start() {
        startTimeUs = System.nanoTime() / 1000
        encoder?.start()
        audioRecord?.startRecording()
        isRunning = true
        DebugLog.d(TAG, "音频录制已启动")
    }

    /**
     * 采集 PCM 并送入编码器（在 IO 线程循环运行）
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

        DebugLog.d(TAG, "captureAndEncode 开始: bufferSize=${pcmBuffer.size}, recordState=${record.state}")
        try {
            while (isRunning) {
                // 从麦克风读取 PCM 数据
                val bytesRead = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) {
                    readErrorCount++
                    if (readErrorCount <= 3 || readErrorCount % 100 == 0L) {
                        DebugLog.w(TAG, "音频 read 返回 $bytesRead (第${readErrorCount}次)")
                    }
                    continue
                }

                // 送入编码器（分批写入，防止 BufferOverflow）
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
                        DebugLog.d(TAG, "音频已喂入: $feedCount 次 (write=$toWrite)")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                DebugLog.d(TAG, "采集协程被取消 (已喂入${feedCount}次)")
                throw e  // 必须重抛，违反协程取消约定会导致父协程无法感知取消
            } else {
                DebugLog.e(TAG, "采集异常: ${e.message}", e)
            }
        } finally {
            // 发送 EOS
            sendEndOfStream(codec)
        }
        DebugLog.d(TAG, "采集结束, 共喂入: $feedCount 次, read错误: $readErrorCount 次")
    }

    /**
     * 持续从编码器读取 AAC 帧
     */
    suspend fun drainEncoder() = withContext(Dispatchers.IO) {
        val codec = encoder ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()
        var drainCount = 0L

        DebugLog.d(TAG, "音频 drainEncoder 开始")
        try {
            while (true) {
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            val data = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(data)

                            // 跳过 CSD 配置帧（已单独存储在 csdData 中）
                            // CSD 帧不能写入 MediaMuxer 的 sample data，否则破坏音频轨
                            val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isConfig) {
                                frames.add(EncodedAudioFrame(
                                    data = data,
                                    presentationTimeUs = bufferInfo.presentationTimeUs,
                                    flags = bufferInfo.flags,
                                ))
                                drainCount++
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        // 收到 EOS → drain 完成
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            DebugLog.d(TAG, "音频收到 EOS")
                            break
                        }
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(1)
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val fmt = codec.outputFormat
                        DebugLog.d(TAG, "音频输出格式: $fmt")
                        // 提取 CSD（AudioSpecificConfig），某些设备的 MediaMuxer 需要
                        try {
                            fmt.getByteBuffer("csd-0")?.let { buf ->
                                csdData = ByteArray(buf.remaining()).also { buf.get(it) }
                                DebugLog.d(TAG, "音频 CSD 已提取: ${csdData?.size}B")
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) {
                DebugLog.d(TAG, "音频 drain 被取消")
                throw e  // 必须重抛，违反协程取消约定
            } else {
                DebugLog.w(TAG, "音频 drain 异常: ${e.message}")
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            encoder = null
        }
        DebugLog.d(TAG, "音频 drain 完成, 总计: $drainCount 帧")
    }

    /**
     * 停止采集（发送 EOS，编码器由 drain 自然关闭）
     */
    fun stop() {
        isRunning = false
        audioRecord?.apply {
            try { stop() } catch (_: Exception) {}
            try { release() } catch (_: Exception) {}
        }
        audioRecord = null
        DebugLog.d(TAG, "音频录制已停止")
    }

    /**
     * 获取所有编码后的 AAC 帧
     */
    fun getEncodedFrames(): List<EncodedAudioFrame> = frames.toList()

    /**
     * 发送 EOS 标志
     */
    private fun sendEndOfStream(codec: MediaCodec) {
        try {
            for (attempt in 1..10) {
                val inputIndex = codec.dequeueInputBuffer(50_000)
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    DebugLog.d(TAG, "音频 EOS 已发送（第 ${attempt} 次）")
                    return
                }
            }
            DebugLog.w(TAG, "音频 EOS 发送失败")
        } catch (_: Exception) {}
    }
}

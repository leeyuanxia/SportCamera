package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 环形缓冲区录制器 — 预录的核心组件
 *
 * 使用 MediaCodec 编码器（ByteBuffer 输入）将 YUV 帧编码为 H.264 NAL，
 * 编码后的帧写入内存环形缓冲区（不写磁盘 → 省电）。
 * 唤醒时可 dump 所有缓冲帧用于合成视频。
 *
 * 省电设计：
 * - 待机模式使用较低帧率（15fps）和码率（1.5Mbps）Baseline Profile
 * - 环形缓冲仅在内存中，无 Flash IO
 * - 丢弃策略：超过容量时从头部丢弃到关键帧边界
 *
 * 数据流：
 * ImageAnalysis → YUV_420_888 → NV12 ByteArray → feedFrame()
 *   → dequeueInputBuffer() → queueInputBuffer() → MediaCodec 编码
 *   → drainEncoder() 读取编码帧 → 环形缓冲区
 */
class RingBufferRecorder(
    private val maxDurationSec: Int = 30,
    private val width: Int = STANDBY_WIDTH,
    private val height: Int = STANDBY_HEIGHT,
    private val fps: Int = STANDBY_FPS,
    private val bitrateBps: Int = STANDBY_BITRATE,
) : FrameConsumer {

    /** 编码后的帧数据 */
    data class EncodedFrame(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int,
    )

    companion object {
        // 待机模式默认参数（省电）
        const val STANDBY_WIDTH = 1280
        const val STANDBY_HEIGHT = 720
        const val STANDBY_FPS = 15
        const val STANDBY_BITRATE = 1_500_000

        private const val TAG = "RingBufferRecorder"

        /** 判断是否为关键帧 */
        fun EncodedFrame.isKeyFrame(): Boolean =
            flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

        /** 判断是否为配置帧（SPS/PPS/CSD） */
        fun EncodedFrame.isConfigFrame(): Boolean =
            flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
    }

    // 环形缓冲区容量 ≈ 码率 × 时长 / 8
    private val maxBytes = (bitrateBps.toLong() / 8 * maxDurationSec).toInt()
    private val buffer = ConcurrentLinkedDeque<EncodedFrame>()

    @Volatile private var currentBytes = 0L
    @Volatile private var isRunning = false
    @Volatile private var isPrepared = false

    private var encoder: MediaCodec? = null

    /** SPS/PPS 配置数据（每个 MP4 文件开头需要） */
    private var csdData: ByteArray? = null

    /**
     * 准备编码器（ByteBuffer 输入模式）
     *
     * 编码器配置为接受 YUV420 数据（通过 dequeueInputBuffer/queueInputBuffer），
     * 而非 Surface 输入。
     */
    fun prepare() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2) // 每 2s 一个关键帧
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // 使用 High Profile 与 ActiveRecorder 一致，确保 SPS/PPS 兼容可直接拼接
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            )
            setInteger(
                MediaFormat.KEY_LEVEL,
                MediaCodecInfo.CodecProfileLevel.AVCLevel4
            )
        }

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        isPrepared = true
    }

    /**
     * 启动编码器
     *
     * 关键：先 start 编码器，再置 isRunning = true。
     * 否则 feedFrame（在 analyzerExecutor 线程）可能在编码器未启动时
     * 调用 dequeueInputBuffer → 抛异常 → 帧被丢弃。
     */
    fun start() {
        if (!isPrepared) throw IllegalStateException("RingBufferRecorder 未 prepare")
        encoder?.start()
        isRunning = true
        Log.d(TAG, "编码器已启动: ${width}x${height} @${fps}fps, ${bitrateBps/1000}kbps, 环形缓冲${maxDurationSec}s")
    }

    private var feedCount = 0L
    private var dropCount = 0L

    /**
     * 向编码器送入一帧 YUV 数据（实现 FrameConsumer 接口）
     *
     * 由 CameraFramePipeline 在 ImageAnalysis 回调中调用。
     * 使用 dequeueInputBuffer + queueInputBuffer 发送数据。
     */
    override fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int) {
        val codec = encoder ?: return
        if (!isRunning) return

        try {
            // 使用 1000μs (1ms) 超时而非 0，部分设备上 timeout=0 可能抛异常
            val inputIndex = codec.dequeueInputBuffer(1000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(yuvData)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    yuvData.size,
                    timestampUs,
                    0
                )
                feedCount++
                if (feedCount % 150 == 0L) {
                    Log.d(TAG, "已喂帧: $feedCount, 丢弃: $dropCount, 缓冲帧数: ${buffer.size}")
                }
            } else {
                dropCount++
                // inputIndex < 0 → 输入缓冲区满，丢弃该帧
            }
        } catch (e: Exception) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 1L) {
                Log.w(TAG, "喂帧异常 (丢弃#${dropCount}, 类型=${e.javaClass.simpleName}): ${e.message}", e)
            }
        }
    }

    /**
     * 持续从编码器读取编码帧并写入环形缓冲
     *
     * 在 IO 线程上运行，直到 [stop] 被调用。
     */
    suspend fun drainEncoder() = withContext(Dispatchers.IO) {
        val codec = encoder ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()
        var drainCount = 0L

        Log.d(TAG, "drainEncoder 开始")
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

                        // 保存 CSD（SPS/PPS）配置帧
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            csdData = data
                        }

                        addToRingBuffer(EncodedFrame(
                            data = data,
                            presentationTimeUs = bufferInfo.presentationTimeUs,
                            flags = bufferInfo.flags,
                        ))
                        drainCount++
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (drainCount == 1L || drainCount % 150 == 0L) {
                        Log.d(TAG, "已 drain: $drainCount 帧, 缓冲大小: ${buffer.size}, 字节: $currentBytes")
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(1)
                // INFO_OUTPUT_FORMAT_CHANGED → 忽略，我们用固定格式
            }
        }
        } catch (e: Exception) {
            Log.w(TAG, "drainEncoder 退出 (drain了${drainCount}帧): ${e.message}")
        }
        Log.d(TAG, "drainEncoder 结束, 总计 drain: $drainCount 帧")
    }

    /**
     * 将帧写入环形缓冲，超容量时丢弃旧帧
     */
    private fun addToRingBuffer(frame: EncodedFrame) {
        buffer.addLast(frame)
        currentBytes += frame.data.size

        // 超容量 → 从头部丢弃到下一个关键帧边界
        while (currentBytes > maxBytes && buffer.size > 1) {
            val first = buffer.peekFirst() ?: break
            val second = buffer.elementAtOrNull(1) ?: break
            // 只在第二个帧是关键帧时才能丢弃第一个
            if (second.isKeyFrame() || second.isConfigFrame()) {
                buffer.removeFirst()
                currentBytes -= first.data.size
            } else {
                break // 不能在非关键帧边界切割
            }
        }
    }

    /**
     * Dump 环形缓冲中的所有帧（唤醒时调用）
     */
    fun dumpAllFrames(): List<EncodedFrame> = buffer.toList()

    /**
     * 获取最近的 N 毫秒帧数据
     */
    fun dumpRecentFrames(durationMs: Long): List<EncodedFrame> {
        val allFrames = buffer.toList()
        if (allFrames.isEmpty()) return emptyList()

        val latestTime = allFrames.last().presentationTimeUs
        val targetStartTime = latestTime - durationMs * 1000

        return allFrames.filter { it.presentationTimeUs >= targetStartTime }
    }

    /**
     * 获取 CSD（SPS/PPS）配置数据
     */
    fun getCsdData(): ByteArray? = csdData

    /** 清空环形缓冲 */
    fun clear() {
        buffer.clear()
        currentBytes = 0
    }

    /** 停止编码器 */
    fun stop() {
        isRunning = false
        try {
            encoder?.stop()
        } catch (_: Exception) {
            // MediaCodec stop 可能抛异常，忽略
        }
        encoder?.release()
        encoder = null
        isPrepared = false
    }

    /** 完全释放资源 */
    fun release() {
        stop()
        clear()
        csdData = null
    }
}

package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import cn.leeyuanxia.sportcamera.util.DebugLog
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
 * - 待机模式使用较低码率（3Mbps）High Profile，帧率与录制一致（30fps）
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
        const val STANDBY_FPS = 30
        const val STANDBY_BITRATE = 3_000_000

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

    /**
     * MediaCodec 编码器实例 — 跨线程访问，必须 @Volatile
     *
     * 线程分布：
     * - prepare() / start() 在 FrameAnalyzer 线程写入
     * - feedFrame() 在 FrameAnalyzer 线程读取（同线程，无可见性问题）
     * - drainEncoder() 在 IO 线程读取（跨线程！）
     * - stop() 可能从任意线程写入 null
     *
     * 如果缺少 @Volatile，IO 线程在 drainEncoder() 中读取 encoder 时
     * 可能始终看到 null（FrameAnalyzer 线程的写入不可见），
     * 导致 drainEncoder 立即 return，环形缓冲永远为空。
     */
    @Volatile
    private var encoder: MediaCodec? = null

    /** 编码器输出格式中的 CSD-0（SPS），从 INFO_OUTPUT_FORMAT_CHANGED 提取 */
    @Volatile
    var csd0Data: ByteArray? = null
        private set

    /** 编码器输出格式中的 CSD-1（PPS），从 INFO_OUTPUT_FORMAT_CHANGED 提取 */
    @Volatile
    var csd1Data: ByteArray? = null
        private set

    /**
     * 准备编码器（ByteBuffer 输入模式）
     *
     * 编码器配置为接受 YUV420 数据（通过 dequeueInputBuffer/queueInputBuffer），
     * 而非 Surface 输入。
     *
     * Level 选择策略：
     * - 4K@60fps 需要 Level 5.2（MaxMBps=2,073,600 ≥ 4K@60fps 的 1,944,000）
     * - Level 4 最大 MaxMBps=245,760，仅支持 4K@30fps 或 1080p@60fps
     *
     * 注意：部分硬件编码器对非标准 Level 值支持不佳，可能退化到软件编码。
     * 对于非 4K 分辨率，保守使用 Level 4（编码器实际输出能力不受 Level 参数限制）。
     */
    fun prepare() {
        // 根据分辨率和帧率选择合适的 AVC Level
        // 仅 4K@60fps 需要升级 Level，其他档位使用 Level 4 即可
        val mbPerFrame = (width / 16) * (height / 16)
        val mbPerSec = mbPerFrame * fps
        val avcLevel = when {
            mbPerSec > 1_000_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52  // 4K@60fps
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel4                   // 其他档位
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            // 关键帧间隔保持 2s：dumpRecentFrames 已修复从关键帧开始，
            // 无需缩短间隔。间隔过短会导致关键帧过多，编码器在 ByteBuffer
            // 模式下吞吐不足，输入缓冲区溢出丢帧（1080p@60fps 实测退化为 20fps）
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // 使用 High Profile，Level 根据分辨率/帧率选择
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            )
            setInteger(MediaFormat.KEY_LEVEL, avcLevel)
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
        DebugLog.d(TAG, "编码器已启动: ${width}x${height} @${fps}fps, ${bitrateBps/1000}kbps, 环形缓冲${maxDurationSec}s")
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
                    DebugLog.d(TAG, "已喂帧: $feedCount, 丢弃: $dropCount, 缓冲帧数: ${buffer.size}")
                }
            } else {
                dropCount++
                // inputIndex < 0 → 输入缓冲区满，丢弃该帧
            }
        } catch (e: Exception) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 1L) {
                DebugLog.w(TAG, "喂帧异常 (丢弃#${dropCount}, 类型=${e.javaClass.simpleName}): ${e.message}", e)
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

        DebugLog.d(TAG, "drainEncoder 开始")
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

                        // CSD 帧同时记录（兜底，优先用 outputFormat 提取的数据）
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            if (csd0Data == null) csd0Data = data
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
                        DebugLog.d(TAG, "已 drain: $drainCount 帧, 缓冲大小: ${buffer.size}, 字节: $currentBytes")
                    }
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(1)
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 从输出格式中提取 csd-0 (SPS) 和 csd-1 (PPS)
                    // 这是 MediaMuxer 合成 MP4 所需的编解码器特定数据
                    try {
                        val format = codec.outputFormat
                        format.getByteBuffer("csd-0")?.let {
                            csd0Data = ByteArray(it.remaining())
                            it.get(csd0Data!!)
                        }
                        format.getByteBuffer("csd-1")?.let {
                            csd1Data = ByteArray(it.remaining())
                            it.get(csd1Data!!)
                        }
                        DebugLog.d(TAG, "CSD 已提取: csd-0=${csd0Data?.size ?: 0}B, csd-1=${csd1Data?.size ?: 0}B")
                    } catch (_: Exception) {}
                }
            }
        }
        } catch (e: Exception) {
            // 协程取消是正常行为（如 onWakeWordDetected 取消 drainJob），不作为错误
            if (e is kotlinx.coroutines.CancellationException) {
                DebugLog.d(TAG, "drainEncoder 被取消 (已 drain ${drainCount} 帧)")
            } else {
                DebugLog.w(TAG, "drainEncoder 异常退出 (drain了${drainCount}帧): ${e.message}")
            }
        }
        DebugLog.d(TAG, "drainEncoder 结束, 总计 drain: $drainCount 帧")
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
     * 获取最近的 N 毫秒帧数据，确保从关键帧开始
     *
     * 关键修复：按时间戳过滤后，向前回溯到第一个关键帧（IDR），
     * 确保输出视频从可解码的帧开始。否则起始的 P 帧无法独立解码，
     * 播放器会显示冻结画面直到下一个 IDR 出现（最长 = KEY_I_FRAME_INTERVAL）。
     */
    fun dumpRecentFrames(durationMs: Long): List<EncodedFrame> {
        val allFrames = buffer.toList()
        if (allFrames.isEmpty()) return emptyList()

        val latestTime = allFrames.last().presentationTimeUs
        val targetStartTime = latestTime - durationMs * 1000

        // 按时间戳过滤
        val timeFiltered = allFrames.filter { it.presentationTimeUs >= targetStartTime }
        if (timeFiltered.isEmpty()) return emptyList()

        // 向前回溯到第一个关键帧（包括时间窗口之前的帧）
        // P 帧无法独立解码，必须从 IDR 帧开始
        val firstKeyFrameIndex = timeFiltered.indexOfFirst { it.isKeyFrame() }
        if (firstKeyFrameIndex > 0) {
            // 起始帧是 P 帧，需要向前在原始缓冲中找最近的 IDR
            val firstFilteredPts = timeFiltered.first().presentationTimeUs
            val keyFrameBefore = allFrames
                .filter { it.presentationTimeUs < firstFilteredPts && it.isKeyFrame() }
                .lastOrNull()

            if (keyFrameBefore != null) {
                // 从原始缓冲中包含该关键帧到时间窗口起始之间的所有帧
                val bridgeFrames = allFrames.filter {
                    it.presentationTimeUs >= keyFrameBefore.presentationTimeUs
                    && it.presentationTimeUs < firstFilteredPts
                }
                return bridgeFrames + timeFiltered
            }

            // 没有找到前导关键帧 → 丢弃第一个 IDR 之前的 P 帧
            return timeFiltered.drop(firstKeyFrameIndex)
        }

        return timeFiltered
    }

    /**
     * 获取 CSD-0（SPS）配置数据，优先从 outputFormat 提取
     */
    fun getCsdData(): ByteArray? = csd0Data

    /** 清空环形缓冲 */
    fun clear() {
        buffer.clear()
        currentBytes = 0
    }

    /** 获取当前缓冲帧数（诊断用） */
    fun bufferSize(): Int = buffer.size

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
        csd0Data = null
        csd1Data = null
    }
}

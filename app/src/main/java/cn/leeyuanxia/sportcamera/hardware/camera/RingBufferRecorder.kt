package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 环形缓冲区录制器 — 预录的核心组件
 *
 * 支持两种输入模式：
 * 1. ByteBuffer 模式（默认）：ImageAnalysis → YUV → NV12 → feedFrame() → 编码器
 * 2. Surface 模式（4K@60fps）：Camera2 → encoder.inputSurface → 编码器（零拷贝）
 *
 * 使用 MediaCodec 编码器将帧编码为 H.264 NAL，编码后的帧写入内存环形缓冲区（不写磁盘 → 省电）。
 * 唤醒时可 dump 所有缓冲帧用于合成视频。
 *
 * 省电设计：
 * - 待机模式使用较低码率（3Mbps）High Profile，帧率与录制一致（30fps）
 * - 环形缓冲仅在内存中，无 Flash IO
 * - 丢弃策略：超过容量时从头部丢弃到关键帧边界
 */
class RingBufferRecorder(
    private val maxDurationSec: Int = 30,
    private val width: Int = STANDBY_WIDTH,
    private val height: Int = STANDBY_HEIGHT,
    private val fps: Int = STANDBY_FPS,
    private val bitrateBps: Int = STANDBY_BITRATE,
    /** 强制使用 Surface 输入（物理相机模式下需要，因为无 ImageAnalysis 提供 ByteBuffer 帧） */
    private val forceSurfaceInput: Boolean = false,
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

    /**
     * 编码器输入 Surface（Surface 模式下使用）
     *
     * Surface 模式下，相机通过 Camera2 API 直接将画面输出到此 Surface，
     * 编码器消费 Surface 上的帧进行编码，无需 feedFrame() 调用。
     *
     * 跨线程安全：
     * - prepareWithSurface() 在 FrameAnalyzer 线程创建
     * - stop() 可能从任意线程释放
     */
    @Volatile
    private var inputSurface: Surface? = null

    /** 是否使用 Surface 输入模式（4K@60fps 或物理相机模式） */
    val useSurfaceInput: Boolean
        get() = forceSurfaceInput || (width >= 3840 && fps > 30)

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
     * 准备编码器（Surface 输入模式）— 4K@60fps 必须使用
     *
     * 与 ByteBuffer 模式的区别：
     * - KEY_COLOR_FORMAT 使用 COLOR_FormatSurface（而非 COLOR_FormatYUV420Flexible）
     * - 通过 createInputSurface() 获取输入 Surface，相机直接输出到此 Surface
     * - 不需要 feedFrame()，帧数据由相机硬件零拷贝写入
     * - drainEncoder() 逻辑完全不变
     *
     * 重要：Android 6.0+ 要求 createInputSurface() 在 configure() 之后、start() 之前调用
     * （参考：https://developer.android.com/reference/android/media/MediaCodec#createInputSurface()）
     *
     * @return 编码器输入 Surface，需传给 Camera2 API 作为输出目标
     */
    fun prepareWithSurface(): Surface {
        val mbPerFrame = (width / 16) * (height / 16)
        val mbPerSec = mbPerFrame * fps
        val avcLevel = when {
            mbPerSec > 1_000_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
        }

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(
                MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            )
            setInteger(MediaFormat.KEY_LEVEL, avcLevel)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // 1. 先 configure — 进入 Configured 状态
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        // 2. 再 createInputSurface — 必须在 Configured 状态下调用
        val surface = codec.createInputSurface()

        this.encoder = codec
        this.inputSurface = surface
        isPrepared = true

        DebugLog.d(TAG, "Surface 模式编码器已准备: ${width}x${height} @${fps}fps, ${bitrateBps/1000}kbps")
        return surface
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

    /**
     * 向编码器送入一帧 YUV 数据（零拷贝版本）
     *
     * YUV 转换直接写入编码器输入缓冲区，省去 ~12MB 的 ByteArray 中转。
     * 4K 高分辨率下使用此路径可减少 3-4ms/帧，显著提升帧率。
     *
     * @return true=成功送入，false=无可用输入缓冲区
     */
    override fun feedFrameDirect(image: android.media.Image, timestampUs: Long, width: Int, height: Int): Boolean {
        val codec = encoder ?: return false
        if (!isRunning) return false

        try {
            val inputTimeout = if (this.width >= 3840) 5000 else 1000
            val inputIndex = codec.dequeueInputBuffer(inputTimeout.toLong())
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex) ?: return false
                inputBuffer.clear()
                // YUV → NV12 直接写入编码器输入缓冲区，省去 12MB ByteArray 中转
                YuvConverter.imageToNv12Direct(image, inputBuffer)
                codec.queueInputBuffer(inputIndex, 0, inputBuffer.position(), timestampUs, 0)
                feedCount++
                if (feedCount % 150 == 0L) {
                    DebugLog.d(TAG, "已喂帧(direct): $feedCount, 丢弃: $dropCount, 缓冲帧数: ${buffer.size}")
                }
                return true
            } else {
                dropCount++
                return false
            }
        } catch (e: Exception) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 1L) {
                DebugLog.w(TAG, "喂帧异常(direct) (丢弃#${dropCount}): ${e.message}", e)
            }
            return false
        }
    }

    private var feedCount = 0L
    private var dropCount = 0L

    /**
     * 向编码器送入一帧 YUV 数据（实现 FrameConsumer 接口）
     *
     * 由 CameraFramePipeline 在 ImageAnalysis 回调中调用。
     * 使用 dequeueInputBuffer + queueInputBuffer 发送数据。
     *
     * 超时策略：
     * - 1080p 及以下：1000μs (1ms) — 帧小，编码快，1ms 足够
     * - 4K：5000μs (5ms) — 每帧 ~12MB，编码器处理慢，需要更多等待时间
     *   4K@60fps 下 1ms 超时会频繁返回 -1（输入缓冲区满），导致约 10fps 丢失
     */
    override fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int) {
        val codec = encoder ?: return
        if (!isRunning) return

        try {
            val inputTimeout = if (this.width >= 3840) 5000 else 1000
            val inputIndex = codec.dequeueInputBuffer(inputTimeout.toLong())
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
        // Surface 模式：释放 inputSurface 触发 EOS，drainEncoder 检测到后退出
        inputSurface?.release()
        inputSurface = null
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
        inputSurface = null
    }
}

package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 活跃录制器 — 唤醒后高质量录像
 *
 * 与 RingBufferRecorder 不同：
 * - 使用用户选择的分辨率/帧率/码率（High Profile）
 * - 所有帧直接收集到列表中（不做环形丢弃）
 * - 录制完成后将帧交给 VideoAssembler 合成
 *
 * 数据流：
 * ImageAnalysis → YUV_420_888 → NV12 ByteArray → feedFrame()
 *   → dequeueInputBuffer() → queueInputBuffer() → MediaCodec 编码
 *   → drainEncoder() 读取编码帧 → frames 列表
 *
 * 正确的 MediaCodec 关闭流程：
 * 1. signalEndOfStream() → 通知编码器无更多输入
 * 2. drainEncoder() 自然 drain 直到收到 EOS → 内部 stop+release
 * 3. 无需 cancel 协程，避免 native dequeue 在 codec 释放后崩溃
 */
class ActiveRecorder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrateBps: Int,
) : FrameConsumer {

    companion object {
        private const val TAG = "ActiveRecorder"
    }

    /**
     * MediaCodec 编码器实例 — 跨线程访问，需 @Volatile
     *
     * prepare()/startEncoder() 在主线程写入，feedFrame() 在 FrameAnalyzer 线程读取，
     * drainEncoder() 在 IO 线程读取，signalEndOfStream()/stop 可从任意线程调用。
     */
    @Volatile
    private var encoder: MediaCodec? = null

    @Volatile
    private var isRunning = false

    private val frames = mutableListOf<RingBufferRecorder.EncodedFrame>()

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
     */
    fun prepare() {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 每 1s 一个关键帧
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            // 高质量: High Profile
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
    }

    /**
     * 启动编码器
     *
     * 关键：先 start 编码器，再置 isRunning = true。
     * 否则 feedFrame 可能在编码器未启动时调用 dequeueInputBuffer 抛异常。
     */
    fun startEncoder() {
        encoder?.start()
        isRunning = true
        Log.d(TAG, "编码器已启动: ${width}x${height} @${fps}fps, ${bitrateBps/1000}kbps")
    }

    private var feedCount = 0L
    private var dropCount = 0L

    /**
     * 向编码器送入一帧 YUV 数据（实现 FrameConsumer 接口）
     *
     * 由 CameraFramePipeline 在 ImageAnalysis 回调中调用。
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
                    Log.d(TAG, "已喂帧: $feedCount, 丢弃: $dropCount, 收集帧数: ${frames.size}")
                }
            } else {
                dropCount++
            }
        } catch (e: Exception) {
            dropCount++
            if (dropCount <= 3 || dropCount % 100 == 1L) {
                Log.w(TAG, "喂帧异常 (丢弃#${dropCount}, 类型=${e.javaClass.simpleName}): ${e.message}", e)
            }
        }
    }

    /**
     * 通知编码器输入流结束
     *
     * ByteBuffer 输入模式不能调用 signalEndOfInputStream()（仅 Surface 模式有效），
     * 需要通过 queueInputBuffer 发送空缓冲区 + BUFFER_FLAG_END_OF_STREAM 标志。
     *
     * 关键修复：dequeueInputBuffer 超时从 1ms 增加到 50ms，并添加重试循环。
     * 原 1ms 超时太短，容易因为编码器输入缓冲区满而导致 EOS 发送失败，
     * 进而导致 drainEncoder 协程永远阻塞、UI 卡在 Recording 状态、视频无法保存。
     */
    fun signalEndOfStream() {
        isRunning = false
        try {
            val codec = encoder ?: return
            // 循环等待可用的输入缓冲区（最多重试 10 次，每次 50ms）
            for (attempt in 1..10) {
                val inputIndex = codec.dequeueInputBuffer(50_000) // 超时 50ms
                if (inputIndex >= 0) {
                    codec.queueInputBuffer(
                        inputIndex, 0, 0, 0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    Log.d(TAG, "EOS 发送成功（第 ${attempt} 次尝试）")
                    return
                }
            }
            Log.w(TAG, "EOS 发送失败：10 次重试后输入缓冲区仍不可用")
        } catch (_: Exception) {
            // 编码器可能已处于错误状态
            Log.w(TAG, "EOS 发送异常")
        }
    }

    /**
     * 持续从编码器读取编码帧（协程方式）
     *
     * 在 IO 线程上运行，直到收到 BUFFER_FLAG_END_OF_STREAM 后自然退出。
     */
    suspend fun drainEncoder() = withContext(Dispatchers.IO) {
        val codec = encoder ?: return@withContext
        val bufferInfo = MediaCodec.BufferInfo()
        var drainCount = 0L

        Log.d(TAG, "drainEncoder 开始")
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
                            frames.add(RingBufferRecorder.EncodedFrame(
                                data = data,
                                presentationTimeUs = bufferInfo.presentationTimeUs,
                                flags = bufferInfo.flags,
                            ))
                            drainCount++
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (drainCount == 1L || drainCount % 150 == 0L) {
                            Log.d(TAG, "已 drain: $drainCount 帧, 收集: ${frames.size}")
                        }

                        // 收到 EOS → drain 完成，退出循环
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            Log.d(TAG, "收到 EOS，drain 完成")
                            break
                        }
                    }
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(1)
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // 从输出格式中分别提取 csd-0 (SPS) 和 csd-1 (PPS)
                        // Qualcomm 编码器将它们分离存放，MediaMuxer 也需要分别设置
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
                            Log.d(TAG, "CSD 已提取: csd-0=${csd0Data?.size ?: 0}B, csd-1=${csd1Data?.size ?: 0}B")
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "drainEncoder 退出 (drain了${drainCount}帧): ${e.message}")
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            encoder = null
        }
        Log.d(TAG, "drainEncoder 结束, 总计 drain: $drainCount 帧")
    }

    /**
     * 获取所有编码帧
     */
    fun getAllFrames(): List<RingBufferRecorder.EncodedFrame> = frames.toList()

    /**
     * 清空帧列表
     */
    fun clear() {
        frames.clear()
    }
}

package cn.leeyuanxia.sportcamera.domain

import cn.leeyuanxia.sportcamera.util.DebugLog
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.camera.FrameConsumer
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder
import cn.leeyuanxia.sportcamera.power.ThermalThrottler

class PreRecordManager : FrameConsumer {

    companion object {
        private const val TAG = "PreRecordManager"
    }

    @Volatile var currentDuration: PreRecordDuration = PreRecordDuration.DEFAULT; private set
    @Volatile var currentProfile: ResolutionProfile = ResolutionProfile.DEFAULT; private set
    @Volatile var cameraWidth: Int = 0; private set
    @Volatile var cameraHeight: Int = 0; private set

    /**
     * 环形缓冲编码器 — 由相机线程写入，由主线程/drain线程读取
     *
     * 关键：必须使用 @Volatile 确保跨线程可见性。
     * ringBuffer 在相机分析线程（FrameAnalyzer）的 feedFrame() 中创建，
     * 但在主线程的 drainLoop() 和 dumpPreFrames() 中读取。
     * 没有 @Volatile，ARM 设备上主线程可能永远看不到相机线程的写入，
     * 导致 drainLoop 超时退出、预录缓冲永远为空。
     */
    @Volatile
    private var ringBuffer: RingBufferRecorder? = null

    /**
     * 编码器是否已创建并开始 drain — 跨线程访问，需 @Volatile
     *
     * 在相机线程的 createBuffer() 中设为 true，
     * 在主线程的 updateThrottleConfig() 和 stop() 中读取/重置。
     */
    @Volatile
    private var drainStarted: Boolean = false

    private var readyToCreate: Boolean = false

    // 热管理配置（默认值 = 无降频）
    @Volatile
    private var throttleConfig: ThermalThrottler.ThrottleConfig? = null

    // 用于检测 ringBuffer 是否被替换（热管理重建时），drainLoop 据此重启
    @Volatile
    private var ringBufferGeneration: Int = 0

    fun setDuration(duration: PreRecordDuration) { currentDuration = duration }
    fun setProfile(profile: ResolutionProfile) { currentProfile = profile }
    fun setOrientation(o: cn.leeyuanxia.sportcamera.domain.model.RecordOrientation) {}

    /**
     * 标记可以创建编码器。首帧到达 + 此标记都满足时，自动创建。
     */
    fun markReadyToCreate() {
        readyToCreate = true
    }

    /**
     * 用相机实际分辨率和用户选择的码率创建编码器
     *
     * 关键：使用安全发布模式。先在局部变量中完成 prepare() + start()，
     * 最后才赋值给 volatile 字段 ringBuffer。
     *
     * 如果先写 ringBuffer 再 prepare/start，主线程的 drainLoop 会在
     * prepare/start 完成前就看到非 null 的 ringBuffer，导致 drainEncoder
     * 读到 encoder=null / isRunning=false 立即退出（0 帧 drain）。
     *
     * 参数来源：
     * - 分辨率：相机实际输出（cameraWidth × cameraHeight），与用户选择的 profile 一致
     * - fps/bitrate：默认使用 currentProfile（用户选择），热管理降级时由 throttleConfig 覆盖
     */
    private fun createBuffer(w: Int, h: Int, fps: Int, bitrateBps: Int) {
        val oldBuffer = ringBuffer
        val recorder = RingBufferRecorder(
            maxDurationSec = currentDuration.seconds,
            width = w, height = h,
            fps = fps,
            bitrateBps = bitrateBps,
        )
        // 在局部变量上完成全部初始化
        recorder.prepare()
        recorder.start()
        drainStarted = true
        ringBufferGeneration++ // 通知 drainLoop 有新编码器
        // 安全发布：最后才写入 volatile 字段，确保 drainLoop 看到完全初始化的实例
        ringBuffer = recorder
        // 释放旧缓冲（在新缓冲发布之后，避免 ringBuffer 出现为 null 的窗口）
        oldBuffer?.release()
        DebugLog.d(TAG, "环形缓冲已创建 (gen=$ringBufferGeneration): ${w}x${h} @${fps}fps ${bitrateBps/1000}kbps, ${currentDuration.seconds}s")
    }

    /**
     * 持续 drain 循环 — 支持编码器被热管理替换后自动重启
     *
     * 当 updateThrottleConfig() 重建编码器时，ringBufferGeneration 递增，
     * drainEncoder() 对旧编码器返回后，检测到 generation 变化就继续 drain 新编码器。
     */
    suspend fun drainLoop() {
        var lastGeneration = ringBufferGeneration
        DebugLog.d(TAG, "drainLoop 启动，等待 ringBuffer 创建...")
        while (true) {
            // 等待 ringBuffer 存在
            var waits = 0
            while (ringBuffer == null && waits < 300) {
                kotlinx.coroutines.delay(10)
                waits++
            }
            val rb = ringBuffer
            if (rb == null) {
                DebugLog.e(TAG, "drainLoop: 等待编码器超时（3秒内无帧到达），ringBuffer 仍为 null")
                return
            }
            lastGeneration = ringBufferGeneration
            DebugLog.d(TAG, "drainLoop: ringBuffer 已就绪 (gen=$ringBufferGeneration, waits=${waits}x10ms)，开始 drain")
            rb.drainEncoder()
            // drainEncoder 返回 = 编码器被 stop
            // 检查是否有新编码器（热管理重建）
            if (ringBufferGeneration != lastGeneration) {
                DebugLog.d(TAG, "drainLoop: 检测到新编码器 (gen=$ringBufferGeneration)，继续 drain")
                continue
            }
            // 同一个 generation → 正常停止，退出
            DebugLog.d(TAG, "drainLoop: 编码器正常停止，退出")
            return
        }
    }

    override fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int) {
        // 首帧：记录相机分辨率
        if (cameraWidth == 0 && width > 0 && height > 0) {
            cameraWidth = width
            cameraHeight = height
            DebugLog.d(TAG, "首帧: ${width}x${height}, readyToCreate=$readyToCreate, drainStarted=$drainStarted, profile=${currentProfile.width}x${currentProfile.height}")
        }

        // 满足条件时自动创建编码器
        // 使用用户选择的分辨率档位参数（fps/bitrate），热管理降级时才覆盖
        if (readyToCreate && !drainStarted && cameraWidth > 0) {
            val config = throttleConfig
            createBuffer(
                cameraWidth, cameraHeight,
                fps = config?.preRecordFps ?: currentProfile.fps,
                bitrateBps = config?.preRecordBitrateBps ?: currentProfile.bitrateBps,
            )
        }

        ringBuffer?.feedFrame(yuvData, timestampUs, width, height)
    }

    /**
     * 接收热管理配置，动态调整预录参数
     *
     * 仅当参数实际变化时才重建编码器，避免无谓重建丢失帧。
     */
    fun updateThrottleConfig(config: ThermalThrottler.ThrottleConfig) {
        val oldConfig = throttleConfig
        throttleConfig = config

        // 参数未变化则跳过重建
        if (oldConfig != null
            && oldConfig.preRecordFps == config.preRecordFps
            && oldConfig.preRecordBitrateBps == config.preRecordBitrateBps
        ) {
            return
        }

        if (ringBuffer != null && drainStarted) {
            DebugLog.d(TAG, "热管理触发重建编码器: ${oldConfig?.preRecordFps}→${config.preRecordFps}fps, ${oldConfig?.preRecordBitrateBps}→${config.preRecordBitrateBps}bps")
            ringBuffer?.stop()
            ringBuffer?.release()
            drainStarted = false
            if (cameraWidth > 0) {
                createBuffer(
                    cameraWidth, cameraHeight,
                    fps = config.preRecordFps,
                    bitrateBps = config.preRecordBitrateBps,
                )
            }
        }
    }

    /**
     * Dump 预录帧 — 从环形缓冲获取前半段数据
     *
     * 唤醒词触发后调用，截取环形缓冲中最近的 preHalfMs 毫秒帧数据。
     * 如果环形缓冲为空或尚未创建，返回空列表并记录警告。
     */
    fun dumpPreFrames(): List<RingBufferRecorder.EncodedFrame> {
        val rb = ringBuffer
        if (rb == null) {
            DebugLog.e(TAG, "dumpPreFrames: ringBuffer 为 null！编码器可能未创建（readyToCreate=$readyToCreate, drainStarted=$drainStarted, cameraSize=${cameraWidth}x${cameraHeight}）")
            return emptyList()
        }
        val allCount = rb.bufferSize()
        val recent = rb.dumpRecentFrames(currentDuration.preHalfMs)
        val result = if (recent.isEmpty()) rb.dumpAllFrames() else recent
        DebugLog.d(TAG, "dumpPreFrames: 缓冲总帧数=$allCount, 最近${currentDuration.preHalfMs}ms帧数=${recent.size}, 最终输出=${result.size}")
        return result
    }

    /**
     * 从环形缓冲 dump 最近 N 毫秒的帧（不停止编码器，用于纯预录模式）
     */
    fun dumpRecentFrames(durationMs: Long): List<RingBufferRecorder.EncodedFrame> {
        val rb = ringBuffer ?: run {
            DebugLog.w(TAG, "dumpRecentFrames: ringBuffer 为 null")
            return emptyList()
        }
        val frames = rb.dumpRecentFrames(durationMs)
        DebugLog.d(TAG, "dumpRecentFrames(${durationMs}ms): ${frames.size} 帧")
        return frames
    }

    /**
     * 获取环形缓冲编码器的 CSD-0 数据（SPS）
     */
    fun getCsdData(): ByteArray? = ringBuffer?.csd0Data

    /**
     * 获取环形缓冲编码器的 CSD-1 数据（PPS）
     */
    fun getCsd1Data(): ByteArray? = ringBuffer?.csd1Data

    fun actualPreDurationMs(frames: List<RingBufferRecorder.EncodedFrame>): Long {
        if (frames.size < 2) return 0L
        return (frames.last().presentationTimeUs - frames.first().presentationTimeUs) / 1000
    }

    /**
     * 停止编码器并重置所有状态，确保下次 enterStandby 能正常创建新编码器
     *
     * 修复：之前 stop() 不重置 drainStarted/readyToCreate，
     * 导致第二次进入待机时 feedFrame() 跳过 createBuffer()，
     * 所有帧被喂入已停止的旧编码器而静默丢弃。
     */
    fun stop() {
        ringBuffer?.stop()
        ringBuffer?.release()
        ringBuffer = null
        drainStarted = false
        readyToCreate = false
    }

    fun clearBuffer() { ringBuffer?.clear() }

    fun release() {
        ringBuffer?.release()
        ringBuffer = null
        drainStarted = false
        readyToCreate = false
    }
}

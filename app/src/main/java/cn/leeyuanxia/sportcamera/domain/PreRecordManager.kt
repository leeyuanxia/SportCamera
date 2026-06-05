package cn.leeyuanxia.sportcamera.domain

import android.view.Surface
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

    /** 保护 ensureEncoder() / updateThrottleConfig() 并发创建编码器的锁 */
    private val encoderLock = Any()

    /**
     * Surface 模式回调 — 编码器 Surface 创建后通知 VoiceTriggerRecorder 绑定 Camera2
     *
     * 回调在 FrameAnalyzer 线程（ensureEncoder → createBuffer）中触发，
     * 回调内需要切换到协程调度器才能调用 CameraController 的 suspend 方法。
     */
    var encoderSurfaceReady: ((Surface) -> Unit)? = null

    /** 当前是否处于 Surface 模式 */
    val isSurfaceMode: Boolean
        get() = ringBuffer?.useSurfaceInput == true

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
     * Surface 模式专用：主动创建编码器（不等首帧）
     *
     * Surface 模式下帧由 Camera2 直接写入 encoder Surface，不经过 CameraFramePipeline，
     * 无法通过 feedFrame → ensureEngine → createBuffer 路径触发编码器创建。
     *
     * 此方法使用用户 profile 的分辨率参数直接创建编码器，
     * 编码器 Surface 通过 encoderSurfaceReady 回调通知外部绑定 Camera2。
     *
     * 调用时机：VoiceTriggerRecorder.enterStandby() 中 Surface 模式分支
     */
    fun createSurfaceEncoder(forceSurfaceInput: Boolean = false) {
        if (drainStarted) {
            DebugLog.d(TAG, "createSurfaceEncoder: 编码器已存在，跳过")
            return
        }
        val w = currentProfile.width
        val h = currentProfile.height
        if (w <= 0 || h <= 0) {
            DebugLog.e(TAG, "createSurfaceEncoder: 无效分辨率 ${w}x${h}")
            return
        }
        cameraWidth = w
        cameraHeight = h
        val config = throttleConfig
        DebugLog.d(TAG, "createSurfaceEncoder: profile=${currentProfile.width}x${currentProfile.height}@${currentProfile.fps}fps, " +
            "热管理 preRecordFps=${config?.preRecordFps}")
        createBuffer(
            w, h,
            fps = config?.preRecordFps ?: currentProfile.fps,
            bitrateBps = config?.preRecordBitrateBps ?: currentProfile.bitrateBps,
            forceSurfaceInput = forceSurfaceInput,
        )
    }

    /**
     * 用相机实际分辨率和用户选择的码率创建编码器
     *
     * 关键：使用安全发布模式。先在局部变量中完成 prepare() + start()，
     * 最后才赋值给 volatile 字段 ringBuffer。
     *
     * 调用者必须持有 encoderLock（除 createSurfaceEncoder 外，该方法在主线程同步执行）。
     */
    private fun createBuffer(w: Int, h: Int, fps: Int, bitrateBps: Int, forceSurfaceInput: Boolean = false) {
        synchronized(encoderLock) {
            createBufferInternal(w, h, fps, bitrateBps, forceSurfaceInput)
            ringBufferGeneration++
        }
    }

    /**
     * createBuffer 的内部实现（不获取锁、不递增 generation）
     *
     * updateThrottleConfig() 已在外层递增 generation 并持有 encoderLock，
     * 直接调用此方法避免重复递增。
     */
    private fun createBufferInternal(w: Int, h: Int, fps: Int, bitrateBps: Int, forceSurfaceInput: Boolean = false) {
        val oldBuffer = ringBuffer
        val recorder = RingBufferRecorder(
            maxDurationSec = currentDuration.seconds,
            width = w, height = h,
            fps = fps,
            bitrateBps = bitrateBps,
            forceSurfaceInput = forceSurfaceInput,
        )

        if (recorder.useSurfaceInput) {
            // Surface 模式：相机直接输出到编码器 Surface
            try {
                val surface = recorder.prepareWithSurface()
                recorder.start()
                drainStarted = true
                ringBuffer = recorder
                // 通知外部（VoiceTriggerRecorder）绑定 Camera2 会话
                encoderSurfaceReady?.invoke(surface)
                DebugLog.d(TAG, "环形缓冲已创建(Surface模式, gen=$ringBufferGeneration): ${w}x${h} @${fps}fps ${bitrateBps/1000}kbps, ${currentDuration.seconds}s")
            } catch (e: Exception) {
                // Surface 模式不支持，降级到 ByteBuffer 模式
                DebugLog.w(TAG, "Surface 模式失败，降级到 ByteBuffer: ${e.message}")
                // 关键修复：降级时关闭 useSurfaceInput，否则 feedFrame 会因 useSurfaceInput=true 而丢弃所有帧
                recorder.useSurfaceInput = false
                recorder.prepare()
                recorder.start()
                drainStarted = true
                ringBuffer = recorder
            }
        } else {
            // ByteBuffer 模式：现有逻辑不变
            recorder.prepare()
            recorder.start()
            drainStarted = true
            ringBuffer = recorder
            DebugLog.d(TAG, "环形缓冲已创建(gen=$ringBufferGeneration): ${w}x${h} @${fps}fps ${bitrateBps/1000}kbps, ${currentDuration.seconds}s")
        }

        // 释放旧缓冲（在新缓冲发布之后，避免 ringBuffer 出现为 null 的窗口）
        oldBuffer?.release()
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
        // Surface 模式下帧由 Camera2 直接写入 encoder Surface，不经过此路径
        if (ringBuffer?.useSurfaceInput == true) return
        ensureEncoder(width, height)
        ringBuffer?.feedFrame(yuvData, timestampUs, width, height)
    }

    /**
     * 零拷贝喂帧（4K 高分辨率路径）
     *
     * 编码器创建逻辑与 feedFrame 完全相同，区别仅在于帧数据
     * 直接写入编码器输入缓冲区，省去 ~12MB 的 ByteArray 中转。
     */
    override fun feedFrameDirect(image: android.media.Image, timestampUs: Long, width: Int, height: Int): Boolean {
        // Surface 模式下帧由 Camera2 直接写入 encoder Surface，不经过此路径
        if (ringBuffer?.useSurfaceInput == true) return true
        ensureEncoder(width, height)
        return ringBuffer?.feedFrameDirect(image, timestampUs, width, height) ?: false
    }

    /**
     * 编码器创建/重建逻辑（feedFrame 和 feedFrameDirect 共用）
     */
    private fun ensureEncoder(width: Int, height: Int) {
        synchronized(encoderLock) {
            val resolutionChanged = (cameraWidth > 0 && width > 0 && height > 0
                && (width != cameraWidth || height != cameraHeight))

            if (cameraWidth == 0 || resolutionChanged) {
                if (resolutionChanged) {
                    DebugLog.d(TAG, "分辨率变化: ${cameraWidth}x${cameraHeight} → ${width}x${height}，重建编码器")
                    ringBuffer?.stop()
                    ringBuffer?.release()
                    ringBuffer = null
                    drainStarted = false
                }
                cameraWidth = width
                cameraHeight = height
                DebugLog.d(TAG, "首帧/分辨率变更: ${width}x${height}, readyToCreate=$readyToCreate, drainStarted=$drainStarted")
            }

            if (readyToCreate && !drainStarted && cameraWidth > 0) {
                val config = throttleConfig
                createBufferInternal(
                    cameraWidth, cameraHeight,
                    fps = config?.preRecordFps ?: currentProfile.fps,
                    bitrateBps = config?.preRecordBitrateBps ?: currentProfile.bitrateBps,
                )
                ringBufferGeneration++
            }
        }
    }

    /**
     * 接收热管理配置，动态调整预录参数
     *
     * ByteBuffer 模式：参数变化时重建编码器
     * Surface 模式：不重建编码器（重建需要重建 Camera2 会话，代价太大），
     *   仅记录新配置，帧率变更通过 encoderSurfaceReady 回调已由外部处理
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

        // Surface 模式下不重建编码器，帧率由 Camera2 AE FPS Range 控制
        if (isSurfaceMode) {
            DebugLog.d(TAG, "Surface 模式热管理更新: ${oldConfig?.preRecordFps}→${config.preRecordFps}fps（由 AE FPS Range 控制，不重建编码器）")
            return
        }

        synchronized(encoderLock) {
            if (ringBuffer != null && drainStarted) {
                DebugLog.d(TAG, "热管理触发重建编码器: ${oldConfig?.preRecordFps}→${config.preRecordFps}fps, ${oldConfig?.preRecordBitrateBps}→${config.preRecordBitrateBps}bps")
                // 关键修复：先递增 generation 再停止旧编码器
                // drainLoop 在 rb.drainEncoder() 返回后会检查 ringBufferGeneration != lastGeneration
                // 如果 createBuffer 在 drainLoop 检查之后才执行，drainLoop 会误认为没有新编码器而退出
                // 提前递增 generation 确保 drainLoop 无论何时检查都能感知到重建请求
                ringBufferGeneration++
                ringBuffer?.stop()
                ringBuffer?.release()
                drainStarted = false
                if (cameraWidth > 0) {
                    createBufferInternal(
                        cameraWidth, cameraHeight,
                        fps = config.preRecordFps,
                        bitrateBps = config.preRecordBitrateBps,
                    )
                }
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
        encoderSurfaceReady = null
        ringBuffer?.stop()
        ringBuffer?.release()
        ringBuffer = null
        drainStarted = false
        readyToCreate = false
    }

    fun clearBuffer() { ringBuffer?.clear() }

    fun release() {
        encoderSurfaceReady = null
        ringBuffer?.release()
        ringBuffer = null
        drainStarted = false
        readyToCreate = false
    }
}

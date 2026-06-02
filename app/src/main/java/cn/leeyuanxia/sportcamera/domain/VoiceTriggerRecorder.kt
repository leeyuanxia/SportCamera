package cn.leeyuanxia.sportcamera.domain

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.audio.AudioRecorder
import cn.leeyuanxia.sportcamera.hardware.audio.KwsManager
import cn.leeyuanxia.sportcamera.hardware.camera.ActiveRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.CameraFramePipeline
import cn.leeyuanxia.sportcamera.hardware.storage.VideoStorageManager
import cn.leeyuanxia.sportcamera.power.PowerStateManager
import cn.leeyuanxia.sportcamera.power.ThermalThrottler
import cn.leeyuanxia.sportcamera.service.CameraForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 语音触发录像的核心编排器
 *
 * 用户期望的完整流程：
 * 1. 点击"待机" → 开始预录（环形缓冲持续录制）
 * 2. 说出"开始录像" → 触发：
 *    a. 从环形缓冲截取前半段（preRecordDuration / 2）
 *    b. 开始后录，持续 preRecordDuration / 2
 *    c. 合成：前半段 + 后半段 = preRecordDuration 总时长
 * 3. 保存到相册 → 回到待机
 *
 * 省电架构：
 * - 待机时启动前台服务（防系统杀） + WakeLock（保持 CPU） + 热管理（自适应降频）
 * - 录制时保持前台服务，WakeLock 由 FLAG_KEEP_SCREEN_ON 配合
 */
class VoiceTriggerRecorder(
    private val context: Context,
    private val kwsManager: KwsManager,
    private val preRecordManager: PreRecordManager,
    private val storageManager: VideoStorageManager,
    private val scope: CoroutineScope,
    private val framePipeline: CameraFramePipeline,
    private val powerStateManager: PowerStateManager,
    private val thermalThrottler: ThermalThrottler,
) {
    companion object {
        private const val TAG = "VoiceTrigger"
    }

    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private var kwsJob: Job? = null
    private var recordJob: Job? = null
    private var preRecordDrainJob: Job? = null
    private var throttleJob: Job? = null

    @Volatile
    private var currentDuration: PreRecordDuration = PreRecordDuration.DEFAULT

    @Volatile
    private var currentProfile: ResolutionProfile = ResolutionProfile.DEFAULT

    @Volatile
    private var currentOrientation: RecordOrientation = RecordOrientation.DEFAULT

    fun setPreRecordDuration(duration: PreRecordDuration) {
        currentDuration = duration
        preRecordManager.setDuration(duration)
    }

    fun setResolutionProfile(profile: ResolutionProfile) {
        currentProfile = profile
        preRecordManager.setProfile(profile)
    }

    fun setRecordOrientation(orientation: RecordOrientation) {
        currentOrientation = orientation
        preRecordManager.setOrientation(orientation)
    }

    /**
     * 进入待机模式
     *
     * 启动前台服务 + WakeLock + 热管理 + KWS 监听 + 预录环形缓冲编码。
     */
    fun enterStandby() {
        kwsJob?.cancel()
        recordJob?.cancel()
        _appState.value = AppState.Standby

        // 启动前台服务：防止系统在灭屏后杀死进程
        startForegroundService()

        // 获取 WakeLock：保持 CPU 运行，允许屏幕关闭（最省电的 WakeLock 类型）
        powerStateManager.acquireStandbyWakeLock()

        // 连接帧管线，首帧到达后 feedFrame 中自动创建编码器
        preRecordManager.markReadyToCreate()
        framePipeline.setEncoder(preRecordManager)

        // 待机帧率节流：预录使用 30fps，与录制帧率一致，保证合成视频流畅
        val throttleConfig = thermalThrottler.config.value
        framePipeline.setTargetFps(throttleConfig.preRecordFps)

        Log.d(TAG, "预录管线已连接，等待首帧自动创建编码器 (fps=${throttleConfig.preRecordFps})")

        // 启动 drain 循环
        preRecordDrainJob = scope.launch {
            preRecordManager.drainLoop()
            Log.d(TAG, "预录 drain 协程结束")
        }

        // 启动 KWS 监听
        kwsJob = scope.launch {
            launch { kwsManager.startListening() }
            kwsManager.keywordFlow.collect { keyword ->
                if (kwsManager.matchStartRecording(keyword)) {
                    onWakeWordDetected()
                }
            }
        }

        // 热管理监听：自适应调整预录参数和 KWS 间隔
        throttleJob = scope.launch {
            thermalThrottler.config.collect { config ->
                Log.d(TAG, "热管理配置更新: fps=${config.preRecordFps}, bitrate=${config.preRecordBitrateBps}, kwsInterval=${config.kwsReadIntervalMs}ms")
                preRecordManager.updateThrottleConfig(config)
                framePipeline.setTargetFps(config.preRecordFps)
                kwsManager.updateReadInterval(config.kwsReadIntervalMs)
            }
        }
    }

    /**
     * 唤醒词检测到 → 开始录像流程
     */
    private fun onWakeWordDetected() {
        kwsManager.stopListening()
        throttleJob?.cancel()

        val rawPostDurationMs = currentDuration.postHalfMs
        val totalDurationMs = currentDuration.totalMs

        recordJob = scope.launch {
            try {
                // Phase 1: dump 预录缓冲帧（前半段 = preRecordDuration / 2）
                val preFrames = preRecordManager.dumpPreFrames()
                Log.d(TAG, "dump 预录帧: ${preFrames.size} 帧, 预计前半段=${currentDuration.preHalfMs}ms")

                // 计算预录段实际时长，用于补偿后录段
                val actualPreMs = preRecordManager.actualPreDurationMs(preFrames)
                val postDurationMs: Long = if (actualPreMs < rawPostDurationMs) {
                    val shortfall = rawPostDurationMs - actualPreMs
                    Log.d(TAG, "预录段不足 ${shortfall}ms，延长后录段补偿")
                    rawPostDurationMs + shortfall
                } else {
                    rawPostDurationMs
                }
                val initialElapsedMs = actualPreMs.coerceAtMost(rawPostDurationMs)
                _appState.value = AppState.Recording(initialElapsedMs, totalDurationMs)

                // Phase 1.5: 停止预录编码，断开帧管线
                framePipeline.setEncoder(null)
                preRecordDrainJob?.cancel()
                preRecordManager.stop()

                // 录制时恢复全帧率
                framePipeline.setTargetFps(currentProfile.fps)

                // Phase 2: 创建高质量编码器，使用相机实际分辨率
                val encW = preRecordManager.cameraWidth.takeIf { it > 0 } ?: currentProfile.width
                val encH = preRecordManager.cameraHeight.takeIf { it > 0 } ?: currentProfile.height
                val activeRecorder = ActiveRecorder(
                    width = encW,
                    height = encH,
                    fps = currentProfile.fps,
                    bitrateBps = currentProfile.bitrateBps,
                )
                activeRecorder.prepare()
                activeRecorder.startEncoder()

                // 连接帧管线到 ActiveRecorder
                framePipeline.setEncoder(activeRecorder)

                // Phase 2: 创建音频录制器
                val audioRecorder = AudioRecorder()
                audioRecorder.prepare()
                audioRecorder.start()

                val startTime = System.currentTimeMillis()

                // Phase 2.5: 录制后半段（5 次/秒进度更新，降低 UI 重组频率）
                val drainJob = launch { activeRecorder.drainEncoder() }
                val audioCaptureJob = launch { audioRecorder.captureAndEncode() }
                val audioDrainJob = launch { audioRecorder.drainEncoder() }
                while (System.currentTimeMillis() - startTime < postDurationMs) {
                    delay(200)
                    val postElapsed = System.currentTimeMillis() - startTime
                    _appState.value = AppState.Recording(
                        initialElapsedMs + postElapsed, totalDurationMs
                    )
                }

                // 安全关闭编码器
                activeRecorder.signalEndOfStream()

                // 停止音频录制
                audioRecorder.stop()

                val drainTimeout = withTimeoutOrNull(postDurationMs + 2000) {
                    drainJob.join()
                }
                if (drainTimeout == null) {
                    Log.w(TAG, "drain 编码器超时（${postDurationMs + 2000}ms），强制取消")
                    drainJob.cancel()
                }

                val postFrames = activeRecorder.getAllFrames()
                Log.d(TAG, "后录帧: ${postFrames.size} 帧")

                // 等待音频编码完成
                withTimeoutOrNull(3000) {
                    audioDrainJob.join()
                }
                val audioFrames = audioRecorder.getEncodedFrames()
                Log.d(TAG, "音频帧: ${audioFrames.size} 帧")

                // Phase 3: 断开帧管线，合成视频
                framePipeline.setEncoder(null)
                _appState.value = AppState.Saving(0f)

                if (preFrames.isEmpty() && postFrames.isEmpty()) {
                    Log.e(TAG, "前后帧均为空，跳过合成")
                    _appState.value = AppState.Error("录像数据为空")
                    delay(2000)
                    preRecordManager.clearBuffer()
                    enterStandby()
                    return@launch
                }

                val assembler = VideoAssembler(storageManager)
                val result = assembler.assemble(
                    preFrames = preFrames,
                    postFrames = postFrames,
                    audioFrames = audioFrames,
                    audioStartTimeUs = audioRecorder.startTimeUs,
                    duration = currentDuration,
                    profile = currentProfile,
                    orientation = currentOrientation,
                    cameraWidth = encW,
                    cameraHeight = encH,
                    csd0Data = activeRecorder.csd0Data,
                    csd1Data = activeRecorder.csd1Data,
                    onProgress = { progress ->
                        _appState.value = AppState.Saving(progress)
                    },
                )
                Log.d(TAG, "视频已保存: $result")

                // Phase 4: 清空缓冲，回到待机
                preRecordManager.clearBuffer()
                enterStandby()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "录像失败", e)
                framePipeline.setEncoder(null)
                _appState.value = AppState.Error(e.message ?: "录像失败")
                delay(2000)
                enterStandby()
            }
        }
    }

    fun stop() {
        kwsJob?.cancel()
        recordJob?.cancel()
        preRecordDrainJob?.cancel()
        throttleJob?.cancel()
        kwsManager.stopListening()
        preRecordManager.stop()
        framePipeline.setEncoder(null)
        powerStateManager.releaseWakeLock()
        stopForegroundService()
        _appState.value = AppState.Idle
    }

    fun release() {
        stop()
        kwsManager.release()
        preRecordManager.release()
        thermalThrottler.release()
    }

    // ---- 前台服务管理 ----

    private fun startForegroundService() {
        try {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
            Log.d(TAG, "前台服务已启动")
        } catch (e: Exception) {
            Log.w(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "停止前台服务失败: ${e.message}")
        }
    }
}

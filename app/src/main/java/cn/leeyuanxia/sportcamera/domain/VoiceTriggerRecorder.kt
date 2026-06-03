package cn.leeyuanxia.sportcamera.domain

import android.content.Context
import android.content.Intent
import cn.leeyuanxia.sportcamera.util.DebugLog
import androidx.core.content.ContextCompat
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.audio.AudioRecorder
import cn.leeyuanxia.sportcamera.hardware.audio.KwsManager
import cn.leeyuanxia.sportcamera.hardware.audio.RingBufferAudioRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.CameraFramePipeline
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder.Companion.isConfigFrame
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

    // 音频预录（待机时持续采集 AAC 到环形缓冲）
    private var preAudioRecorder: RingBufferAudioRecorder? = null
    private var preAudioCaptureJob: Job? = null
    private var preAudioDrainJob: Job? = null

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

        DebugLog.d(TAG, "预录管线已连接，等待首帧自动创建编码器 (fps=${throttleConfig.preRecordFps})")

        // 启动 drain 循环
        preRecordDrainJob = scope.launch {
            preRecordManager.drainLoop()
            DebugLog.d(TAG, "预录 drain 协程结束")
        }

        // 启动 KWS + 音频预录
        // 关键顺序：先在协程中完成 KWS 初始化（加载模型+创建stream），
        // 再启动 pre-audio 的 captureAndEncode。
        // 避免两个 AudioRecord 竞争麦克风（Android 只允许一个同时访问 MIC），
        // 所以 KWS 使用外部 PCM 模式，由 pre-audio 提供降采样后的数据。
        kwsManager.setExternalPcmMode(true)
        kwsJob = scope.launch {
            // 步骤 1: 初始化 KWS 模型（加载 onnx 文件，可能耗时几百毫秒）
            kwsManager.startListening()
            DebugLog.d(TAG, "KWS 初始化完成，stream 已创建")

            // 步骤 2: 现在启动音频预录（KWS 已就绪，feedPcm 能立即处理数据）
            val audioRec = RingBufferAudioRecorder(
                maxDurationSec = currentDuration.seconds,
                kwsManager = kwsManager,
            )
            audioRec.prepare()
            audioRec.start()
            preAudioRecorder = audioRec
            preAudioCaptureJob = launch { audioRec.captureAndEncode() }
            preAudioDrainJob = launch { audioRec.drainEncoder() }
            DebugLog.d(TAG, "音频预录已启动: ${currentDuration.seconds}s 环形缓冲, KWS外部PCM模式")

            // 步骤 3: 订阅唤醒词事件
            kwsManager.keywordFlow.collect { keyword ->
                if (kwsManager.matchStartRecording(keyword)) {
                    onWakeWordDetected()
                }
            }
        }

        // 热管理监听：自适应调整预录参数和 KWS 间隔
        throttleJob = scope.launch {
            thermalThrottler.config.collect { config ->
                DebugLog.d(TAG, "热管理配置更新: fps=${config.preRecordFps}, bitrate=${config.preRecordBitrateBps}, kwsInterval=${config.kwsReadIntervalMs}ms")
                preRecordManager.updateThrottleConfig(config)
                framePipeline.setTargetFps(config.preRecordFps)
                kwsManager.updateReadInterval(config.kwsReadIntervalMs)
            }
        }
    }

    /**
     * 唤醒词检测到 → 开始录像流程（纯预录模式）
     *
     * 核心设计：整个录像过程中编码器从不停止，触发只是"标记时间点"。
     * 到达总时长后从环形缓冲 dump 完整窗口帧，全程无编码器切换、无帧丢失。
     *
     * 与旧方案的关键区别：
     * - 旧：停止预录编码器 → 创建新编码器(ActiveRecorder) → 录制后半段 → 合成
     * - 新：预录编码器保持运行 → 等待 postHalfMs → dump 完整窗口 → 合成
     */
    private fun onWakeWordDetected() {
        kwsManager.stopListening()
        kwsManager.setExternalPcmMode(false)  // 唤醒后 KWS 不再需要 PCM 数据
        throttleJob?.cancel()

        val totalDurationMs = currentDuration.totalMs
        val postHalfMs = currentDuration.postHalfMs
        val preHalfMs = currentDuration.preHalfMs

        recordJob = scope.launch {
            try {
                // 前半段已经存在于环形缓冲中，UI 进度从一半开始
                _appState.value = AppState.Recording(preHalfMs, totalDurationMs)

                DebugLog.d(TAG, "录像开始（纯预录模式）: 总时长=${totalDurationMs}ms, 前段=${preHalfMs}ms, 后段=${postHalfMs}ms, 编码器持续运行")

                // 等待后半段时间 — 预录持续录制，零帧丢失
                val waitStartMs = System.currentTimeMillis()
                while (System.currentTimeMillis() - waitStartMs < postHalfMs) {
                    delay(200)
                    val postElapsed = System.currentTimeMillis() - waitStartMs
                    _appState.value = AppState.Recording(preHalfMs + postElapsed, totalDurationMs)
                }

                // 从环形缓冲 dump 完整时间窗口（前后段全部来自同一编码器，PTS 连续）
                val allFrames = preRecordManager.dumpRecentFrames(totalDurationMs)
                val allAudioFrames = preAudioRecorder?.dumpRecentFrames(totalDurationMs) ?: emptyList()
                val audioStartUs = preAudioRecorder?.startTimeUs ?: 0L
                val audioCsd = preAudioRecorder?.csdData

                val actualDurationMs = if (allFrames.size >= 2) {
                    val dataFrames = allFrames.filter { !it.isConfigFrame() }
                    if (dataFrames.size >= 2) {
                        (dataFrames.last().presentationTimeUs - dataFrames.first().presentationTimeUs) / 1000
                    } else 0L
                } else 0L

                DebugLog.d(TAG, "dump 完整窗口: 视频=${allFrames.size}帧(${actualDurationMs}ms), 音频=${allAudioFrames.size}帧")

                _appState.value = AppState.Saving(0f)

                if (allFrames.isEmpty()) {
                    DebugLog.e(TAG, "录像数据为空")
                    _appState.value = AppState.Error("录像数据为空")
                    delay(2000)
                    restartStandby()
                    return@launch
                }

                // 合成视频 — 所有帧来自同一编码器，无需 pre/post 分割和 junction 修正
                val encW = preRecordManager.cameraWidth.takeIf { it > 0 } ?: currentProfile.width
                val encH = preRecordManager.cameraHeight.takeIf { it > 0 } ?: currentProfile.height

                // 从 RingBufferRecorder 的 outputFormat 提取 CSD（与 ActiveRecorder 方式一致）
                val ringCsd0 = preRecordManager.getCsdData()
                val ringCsd1 = preRecordManager.getCsd1Data()
                DebugLog.d(TAG, "环形缓冲 CSD: csd-0=${ringCsd0?.size ?: 0}B, csd-1=${ringCsd1?.size ?: 0}B")

                val assembler = VideoAssembler(storageManager)
                val result = assembler.assemble(
                    preFrames = allFrames,
                    postFrames = emptyList(),        // 无后录段，全部来自环形缓冲
                    preAudioFrames = allAudioFrames,
                    preAudioStartUs = audioStartUs,
                    audioFrames = emptyList(),        // 无后录音频段
                    audioStartTimeUs = audioStartUs,
                    audioCsdData = audioCsd,
                    duration = currentDuration,
                    profile = currentProfile,
                    orientation = currentOrientation,
                    cameraWidth = encW,
                    cameraHeight = encH,
                    csd0Data = ringCsd0,             // 从 outputFormat 正确提取的 SPS
                    csd1Data = ringCsd1,             // 从 outputFormat 正确提取的 PPS
                    onProgress = { progress ->
                        _appState.value = AppState.Saving(progress)
                    },
                )
                DebugLog.d(TAG, "视频已保存: $result")

                // 回到待机状态（清空缓冲，重建编码器，重启 KWS 和热管理）
                restartStandby()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLog.e(TAG, "录像失败", e)
                _appState.value = AppState.Error(e.message ?: "录像失败")
                delay(2000)
                restartStandby()
            }
        }
    }

    /**
     * 录像完成后回到待机状态
     *
     * 先停止所有资源再调用 enterStandby() 重建一切。
     * 注意：recordJob 必须在调用 stop() 前置 null，防止 stop() 取消当前协程。
     * stop() 和 enterStandby() 之间加 delay 确保前台服务完全停止后再重启，
     * 避免 ForegroundServiceDidNotStartInTimeException。
     */
    private suspend fun restartStandby() {
        recordJob = null
        stop()
        delay(300)  // 等待前台服务完全停止
        enterStandby()
    }

    fun stop() {
        kwsJob?.cancel()
        recordJob?.cancel()
        preRecordDrainJob?.cancel()
        throttleJob?.cancel()
        // 清理音频预录
        preAudioCaptureJob?.cancel()
        preAudioDrainJob?.cancel()
        preAudioRecorder?.stop()
        preAudioRecorder?.release()
        preAudioRecorder = null
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
            DebugLog.d(TAG, "前台服务已启动")
        } catch (e: Exception) {
            DebugLog.w(TAG, "启动前台服务失败: ${e.message}")
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, CameraForegroundService::class.java).apply {
                action = CameraForegroundService.ACTION_STOP
            }
            context.startService(intent)
        } catch (e: Exception) {
            DebugLog.w(TAG, "停止前台服务失败: ${e.message}")
        }
    }
}

package cn.leeyuanxia.sportcamera.domain

import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.audio.KwsManager
import cn.leeyuanxia.sportcamera.hardware.camera.ActiveRecorder
import cn.leeyuanxia.sportcamera.hardware.camera.CameraFramePipeline
import cn.leeyuanxia.sportcamera.hardware.camera.RingBufferRecorder
import cn.leeyuanxia.sportcamera.hardware.storage.VideoStorageManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import android.util.Log

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
 */
class VoiceTriggerRecorder(
    private val kwsManager: KwsManager,
    private val preRecordManager: PreRecordManager,
    private val storageManager: VideoStorageManager,
    private val scope: CoroutineScope,
    private val framePipeline: CameraFramePipeline,
) {
    companion object {
        private const val TAG = "VoiceTrigger"
    }

    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private var kwsJob: Job? = null
    private var recordJob: Job? = null
    private var preRecordDrainJob: Job? = null

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
     * 启动 KWS 监听 + 预录环形缓冲编码。
     */
    fun enterStandby() {
        kwsJob?.cancel()
        recordJob?.cancel()
        _appState.value = AppState.Standby

        // 连接帧管线，首帧到达后 feedFrame 中自动创建编码器
        preRecordManager.markReadyToCreate()
        framePipeline.setEncoder(preRecordManager)
        Log.d(TAG, "预录管线已连接，等待首帧自动创建编码器")

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
    }

    /**
     * 唤醒词检测到 → 开始录像流程
     *
     * 流程：
     * 1. 从环形缓冲截取前半段帧（preRecordDuration / 2）
     * 2. 停止预录编码器
     * 3. 创建高质量编码器，录制后半段（preRecordDuration / 2）
     * 4. 合成前后半段 → 保存 MP4 → 回到待机
     */
    private fun onWakeWordDetected() {
        kwsManager.stopListening()

        // 后录段时长 = 预录总时长的一半
        val rawPostDurationMs = currentDuration.preHalfMs
        val totalDurationMs = currentDuration.totalMs

        recordJob = scope.launch {
            try {
                // Phase 1: dump 预录缓冲帧（前半段 = preRecordDuration / 2）
                val preFrames = preRecordManager.dumpPreFrames()
                Log.d(TAG, "dump 预录帧: ${preFrames.size} 帧")

                // 计算预录段实际时长，用于补偿后录段
                val actualPreMs = preRecordManager.actualPreDurationMs(preFrames)
                val postDurationMs: Long = if (actualPreMs < rawPostDurationMs) {
                    // 预录不足 → 延长后录段以保证总时长 = totalMs
                    val shortfall = rawPostDurationMs - actualPreMs
                    Log.d(TAG, "预录段不足 ${shortfall}ms，延长后录段补偿")
                    rawPostDurationMs + shortfall
                } else {
                    rawPostDurationMs
                }
                // 初始已录时长 = 实际预录时长（显示已录制的前半段）
                val initialElapsedMs = actualPreMs.coerceAtMost(rawPostDurationMs)
                _appState.value = AppState.Recording(initialElapsedMs, totalDurationMs)

                // Phase 1.5: 停止预录编码，断开帧管线
                framePipeline.setEncoder(null)
                preRecordDrainJob?.cancel()
                preRecordManager.stop()

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

                val startTime = System.currentTimeMillis()

                // Phase 2.5: 录制后半段（postDurationMs 毫秒，已含短fall补偿）
                // 进度从 initialElapsedMs（前半已录）涨到 totalMs（录制完成）
                val drainJob = launch { activeRecorder.drainEncoder() }
                while (System.currentTimeMillis() - startTime < postDurationMs) {
                    delay(50)
                    val postElapsed = System.currentTimeMillis() - startTime
                    // elapsed = 前半已有时长 + 后半已录时长
                    _appState.value = AppState.Recording(
                        initialElapsedMs + postElapsed, totalDurationMs
                    )
                }

                // 安全关闭编码器
                activeRecorder.signalEndOfStream()

                // 等待 drain 协程完成，加超时保护防止卡死
                // 超时时间 = postDurationMs + 2s 缓冲（编码器处理最后一帧的余量）
                val drainTimeout = withTimeoutOrNull(postDurationMs + 2000) {
                    drainJob.join()
                }
                if (drainTimeout == null) {
                    Log.w(TAG, "drain 编码器超时（${postDurationMs + 2000}ms），强制取消")
                    drainJob.cancel()
                }

                val postFrames = activeRecorder.getAllFrames()
                Log.d(TAG, "后录帧: ${postFrames.size} 帧")

                // Phase 3: 断开帧管线，合成视频
                framePipeline.setEncoder(null)
                _appState.value = AppState.Saving(0f)

                // 检查是否有帧可合成
                if (preFrames.isEmpty() && postFrames.isEmpty()) {
                    Log.e(TAG, "前后帧均为空，跳过合成")
                    _appState.value = AppState.Error("录像数据为空")
                    delay(2000)
                    preRecordManager.clearBuffer()
                    enterStandby()
                    return@launch
                }

                val assembler = VideoAssembler(storageManager)
                // 使用 ActiveRecorder 分离的 csd-0(SPS) 和 csd-1(PPS)
                val result = assembler.assemble(
                    preFrames = preFrames,
                    postFrames = postFrames,
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
        kwsManager.stopListening()
        preRecordManager.stop()
        framePipeline.setEncoder(null)
        _appState.value = AppState.Idle
    }

    fun release() {
        stop()
        kwsManager.release()
        preRecordManager.release()
    }
}

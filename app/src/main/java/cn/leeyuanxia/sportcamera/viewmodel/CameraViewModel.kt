package cn.leeyuanxia.sportcamera.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import cn.leeyuanxia.sportcamera.util.DebugLog
import android.view.TextureView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.leeyuanxia.sportcamera.di.AppContainer
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.VoiceTriggerRecorder
import cn.leeyuanxia.sportcamera.domain.isRecording
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.hardware.camera.CameraController
import cn.leeyuanxia.sportcamera.hardware.camera.CameraFramePipeline
import cn.leeyuanxia.sportcamera.hardware.audio.KwsManager
import cn.leeyuanxia.sportcamera.hardware.storage.VideoStorageManager
import cn.leeyuanxia.sportcamera.domain.PreRecordManager
import cn.leeyuanxia.sportcamera.data.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 摄像头 ViewModel — 连接 UI 和业务逻辑
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "CameraViewModel"
        /** 预览窥视持续时间 */
        private const val PEEK_DURATION_MS = 30_000L
    }

    private val container = AppContainer.getInstance()
    private val settingsRepo = SettingsRepository(application)

    // 从 AppContainer 获取共享实例
    private val framePipeline = container.framePipeline
    private val kwsManager = container.kwsManager
    private val preRecordManager = container.preRecordManager
    private val storageManager = container.storageManager
    private val cameraController = container.cameraController

    private val voiceTriggerRecorder = VoiceTriggerRecorder(
        context = application.applicationContext,
        kwsManager = kwsManager,
        preRecordManager = preRecordManager,
        storageManager = storageManager,
        scope = viewModelScope,
        framePipeline = framePipeline,
        powerStateManager = container.powerStateManager,
        thermalThrottler = container.thermalThrottler,
        cameraController = cameraController,
    )

    @Volatile
    private var isInitialized = false

    // 保存最后的绑定状态，用于恢复
    @Volatile
    private var lastTextureView: android.view.TextureView? = null

    @Volatile
    private var lastOrientation: RecordOrientation = RecordOrientation.PORTRAIT

    /** 正在绑定中标志，防止 LaunchedEffect 重启导致并发 bindCamera 调用 */
    @Volatile
    private var isBinding: Boolean = false

    // ---- 暴露给 UI 的 StateFlow ----

    val appState: StateFlow<AppState> = voiceTriggerRecorder.appState

    val preRecordDuration: StateFlow<PreRecordDuration> =
        settingsRepo.preRecordDuration.stateIn(viewModelScope, SharingStarted.Eagerly, PreRecordDuration.DEFAULT)

    val currentLens: StateFlow<CameraLens> = cameraController.currentLens

    val availableLenses: StateFlow<List<CameraLens>> = cameraController.availableLenses

    val resolutionProfile: StateFlow<ResolutionProfile> =
        settingsRepo.resolutionProfile.stateIn(viewModelScope, SharingStarted.Eagerly, ResolutionProfile.DEFAULT)

    val recordOrientation: StateFlow<RecordOrientation> =
        settingsRepo.recordOrientation.stateIn(viewModelScope, SharingStarted.Eagerly, RecordOrientation.DEFAULT)

    val batteryLevel: StateFlow<Int> = container.powerStateManager.batteryLevel

    /** 摄像头硬件支持的帧率（从 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 查询） */
    val supportedFps: StateFlow<Set<Int>> = cameraController.supportedFps

    /** 视频防抖 (EIS) — 用户设置，持久化到 DataStore */
    val videoStabilization: StateFlow<Boolean> =
        settingsRepo.videoStabilization.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 当前摄像头是否支持 EIS（硬件能力检测结果） */
    val eisSupported: StateFlow<Boolean> = cameraController.eisSupported

    /** 当前缩放倍率（1.0x = 无缩放） */
    val zoomRatio: StateFlow<Float> = cameraController.zoomRatio

    /** 最大缩放倍率 */
    val maxZoomRatio: StateFlow<Float> = cameraController.maxZoomRatio

    /** 最小缩放倍率（超广角设备 < 1.0，如 0.5x） */
    val minZoomRatio: StateFlow<Float> = cameraController.minZoomRatio

    /** 是否处于物理相机直连模式（超广角/长焦，不支持缩放） */
    val isPhysicalCameraMode: StateFlow<Boolean> = cameraController.isPhysicalCameraModeState

    /** 预览画面是否可见 — 待机时隐藏，录制时显示，支持 30s 窥视 */
    private val _previewVisible = MutableStateFlow(true)
    val previewVisible: StateFlow<Boolean> = _previewVisible.asStateFlow()

    /** UI 叠加层是否可见 — 待机时可隐藏以最大省电（OLED 全黑） */
    private val _uiVisible = MutableStateFlow(true)
    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    /** 是否需要请求电池优化白名单 */
    val needsBatteryOptimization: Boolean
        get() = !container.powerStateManager.isIgnoringBatteryOptimizations()

    /** 窥视预览的定时任务 */
    private var peekJob: Job? = null

    // 持续监听 Settings 变化 → 同步到 VoiceTriggerRecorder 和帧管线
    init {
        viewModelScope.launch {
            preRecordDuration.collect { duration ->
                voiceTriggerRecorder.setPreRecordDuration(duration)
            }
        }
        viewModelScope.launch {
            combine(resolutionProfile, recordOrientation) { profile, orientation ->
                Pair(profile, orientation)
            }.collect { (profile, orientation) ->
                voiceTriggerRecorder.setResolutionProfile(profile)
                voiceTriggerRecorder.setRecordOrientation(orientation)
                framePipeline.setTargetSize(profile.width, profile.height)
                // profile 变化时重新绑定摄像头，让帧率和分辨率生效
                cameraController.rebindWithProfile(profile.width, profile.height, profile.fps)
            }
        }
        // 根据 AppState 自动切换预览可见性
        viewModelScope.launch {
            appState.collect { state ->
                when (state) {
                    is AppState.Recording -> {
                        // 录制时显示预览
                        peekJob?.cancel()
                        _previewVisible.value = true
                    }
                    is AppState.Idle -> {
                        // 空闲时显示预览（用户配置阶段）
                        peekJob?.cancel()
                        _previewVisible.value = true
                    }
                    is AppState.Standby -> {
                        // 待机时隐藏预览（省电），除非正在窥视
                        if (peekJob?.isActive != true) {
                            _previewVisible.value = false
                        }
                    }
                    else -> {
                        // Saving, Error → 隐藏预览
                        if (peekJob?.isActive != true) {
                            _previewVisible.value = false
                        }
                    }
                }
            }
        }
        // 电池监控
        viewModelScope.launch {
            while (true) {
                container.powerStateManager.updateBatteryInfo()
                delay(30_000)
            }
        }
        // 视频防抖设置变化 → 同步到硬件
        viewModelScope.launch {
            videoStabilization.collect { enabled ->
                cameraController.setVideoStabilization(
                    enabled, isRecording = appState.value.isRecording
                )
            }
        }
    }

    // ---- UI 调用的方法 ----

    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        viewModelScope.launch {
            kwsManager.initialize()
            cameraController.initialize()
        }
    }

    /**
     * 绑定摄像头预览 + 帧分析
     *
     * 首次绑定时恢复用户上次保存的镜头选择。
     * 先绑定 WIDE 初始化缩放范围，再按持久化值切换到广角/长焦。
     */
    suspend fun bindCamera(
        textureView: TextureView,
        orientation: RecordOrientation,
    ) {
        // 防止并发绑定：如果已有绑定在进行中，跳过
        if (isBinding) {
            DebugLog.d(TAG, "bindCamera 已在执行中，跳过重复调用")
            return
        }
        isBinding = true
        try {
            // 保存绑定状态，用于暂停/恢复
            lastTextureView = textureView
            lastOrientation = orientation

            val profile = resolutionProfile.value
            framePipeline.setTargetSize(profile.width, profile.height)
            // 传递 TextureView 引用给 VoiceTriggerRecorder（Surface 模式需要）
            voiceTriggerRecorder.setTextureView(textureView)

            // 先绑定 WIDE 初始化缩放范围（initZoomFromCameraCharacteristics 需要）
            cameraController.bindPreview(
                textureView = textureView,
                lens = CameraLens.WIDE,
                orientation = orientation,
                framePipeline = framePipeline,
                encoderWidth = profile.width,
                encoderHeight = profile.height,
                fps = profile.fps,
            )
            cameraController.refreshEisCapability()

            // 恢复用户上次保存的镜头选择
            val restoredLens = settingsRepo.cameraLens.first()
            if (restoredLens != CameraLens.WIDE && restoredLens != CameraLens.FRONT) {
                val switched = cameraController.switchLens(restoredLens)
                if (switched != null) {
                    cameraController.refreshEisCapability()
                    DebugLog.d(TAG, "启动时恢复镜头: ${restoredLens.name}")
                }
            }
        } finally {
            isBinding = false
        }
    }

    fun startStandby() {
        viewModelScope.launch {
            voiceTriggerRecorder.enterStandby()
            // Surface 模式可能已激活（4K@60fps），刷新 EIS 能力
            cameraController.refreshEisCapability()
        }
    }

    fun stopStandby() {
        voiceTriggerRecorder.stop()
        // 恢复预览和 UI
        _previewVisible.value = true
        _uiVisible.value = true
        // stop() 内部调用了 stopCamera2Session()，需要重新绑定预览
        viewModelScope.launch {
            lastTextureView?.let { textureView ->
                val profile = resolutionProfile.value
                cameraController.bindPreview(
                    textureView = textureView,
                    lens = cameraController.currentLens.value,
                    orientation = lastOrientation,
                    framePipeline = framePipeline,
                    encoderWidth = profile.width,
                    encoderHeight = profile.height,
                    fps = profile.fps,
                )
            }
        }
    }

    /**
     * 切换预览窥视 — 再次点击立即关闭
     */
    fun peekPreview() {
        if (appState.value !is AppState.Standby) return
        if (_previewVisible.value) {
            // 预览正在显示 → 立即关闭
            peekJob?.cancel()
            _previewVisible.value = false
            DebugLog.d(TAG, "窥视预览手动关闭")
        } else {
            // 预览隐藏 → 显示，30s 后自动关闭
            _previewVisible.value = true
            peekJob?.cancel()
            peekJob = viewModelScope.launch {
                delay(PEEK_DURATION_MS)
                _previewVisible.value = false
                DebugLog.d(TAG, "窥视预览 ${PEEK_DURATION_MS / 1000}s 到期，自动关闭")
            }
            DebugLog.d(TAG, "窥视预览开始，${PEEK_DURATION_MS / 1000}s 后自动关闭")
        }
    }

    /**
     * 切换 UI 叠加层可见性
     */
    fun toggleUi() {
        _uiVisible.value = !_uiVisible.value
    }

    /**
     * 显示 UI（点击屏幕时调用）
     */
    fun showUi() {
        _uiVisible.value = true
    }

    /**
     * 请求电池优化白名单
     */
    fun requestBatteryOptimization(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
            DebugLog.d(TAG, "已请求电池优化白名单")
        } catch (e: Exception) {
            DebugLog.w(TAG, "请求电池优化白名单失败: ${e.message}")
        }
    }

    fun switchLens(lens: CameraLens) {
        viewModelScope.launch {
            // 持久化设置
            settingsRepo.setCameraLens(lens)
            // 实际切换摄像头
            val switched = cameraController.switchLens(lens)
            if (switched != null) {
                cameraController.refreshEisCapability()
                DebugLog.d(TAG, "镜头已切换: $switched")
            } else {
                DebugLog.w(TAG, "镜头切换失败 — 相机尚未绑定")
            }
        }
    }

    /**
     * 应用缩放增量（双指捏合时调用）
     */
    fun applyZoomDelta(delta: Float) {
        cameraController.applyZoomDelta(delta)
    }

    fun setPreRecordDuration(duration: PreRecordDuration) {
        viewModelScope.launch {
            settingsRepo.setPreRecordDuration(duration)
        }
    }

    fun setResolutionProfile(profile: ResolutionProfile) {
        viewModelScope.launch {
            settingsRepo.setResolutionProfile(profile)
        }
    }

    fun setRecordOrientation(orientation: RecordOrientation) {
        viewModelScope.launch {
            settingsRepo.setRecordOrientation(orientation)
        }
    }

    fun setVideoStabilization(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.setVideoStabilization(enabled)
        }
    }

    /**
     * 暂停预览（应用进入后台）
     *
     * 如果正在待机或录像，停止录制和预览，释放相机资源
     */
    suspend fun pausePreview() {
        val currentState = appState.value
        if (currentState is AppState.Standby || currentState.isRecording) {
            DebugLog.d(TAG, "应用进入后台，停止预览")
            voiceTriggerRecorder.stop()
            cameraController.stopCamera2Session()
        }
    }

    /**
     * 恢复预览（应用回到前台）
     *
     * 重新绑定相机预览，如果之前在待机状态则自动恢复待机
     */
    suspend fun resumePreview() {
        DebugLog.d(TAG, "应用回到前台，恢复预览")
        // 只恢复预览，不自动进入待机（需用户手动点击待机按钮）
        lastTextureView?.let { textureView ->
            bindCamera(textureView, lastOrientation)
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceTriggerRecorder.release()
        cameraController.release()
    }
}

package cn.leeyuanxia.sportcamera.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.leeyuanxia.sportcamera.di.AppContainer
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.VoiceTriggerRecorder
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 摄像头 ViewModel — 连接 UI 和业务逻辑
 */
class CameraViewModel(application: Application) : AndroidViewModel(application) {

    private val container = AppContainer.getInstance()
    private val settingsRepo = SettingsRepository(application)

    // 从 AppContainer 获取共享实例（PowerStateManager、ThermalThrottler 等）
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
    )

    @Volatile
    private var isInitialized = false

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

    private val _batteryLevel = kotlinx.coroutines.flow.MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel

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
            monitorBattery()
        }
    }

    /**
     * 绑定摄像头预览 + 帧分析
     */
    suspend fun bindCamera(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: androidx.camera.view.PreviewView,
        orientation: RecordOrientation,
    ) {
        val profile = resolutionProfile.value
        framePipeline.setTargetSize(profile.width, profile.height)
        cameraController.bindPreview(
            lifecycleOwner, previewView, currentLens.value, orientation,
            framePipeline = framePipeline,
            encoderWidth = profile.width,
            encoderHeight = profile.height,
        )
    }

    fun startStandby() {
        viewModelScope.launch {
            voiceTriggerRecorder.enterStandby()
        }
    }

    fun stopStandby() {
        voiceTriggerRecorder.stop()
    }

    fun switchLens(lens: CameraLens) {
        viewModelScope.launch {
            settingsRepo.setCameraLens(lens)
        }
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

    /**
     * 持续电池监控 — 每 30 秒从 PowerStateManager 更新一次
     */
    private fun monitorBattery() {
        viewModelScope.launch {
            while (true) {
                container.powerStateManager.updateBatteryInfo()
                _batteryLevel.value = container.powerStateManager.batteryLevel.value
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceTriggerRecorder.release()
        cameraController.release()
    }
}

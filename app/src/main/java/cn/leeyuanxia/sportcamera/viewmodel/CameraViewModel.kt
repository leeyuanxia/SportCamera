package cn.leeyuanxia.sportcamera.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val settingsRepo = SettingsRepository(application)

    // 帧管线：连接 CameraX ImageAnalysis 和编码器
    private val framePipeline = CameraFramePipeline()

    private val kwsManager = KwsManager(application.assets)
    private val preRecordManager = PreRecordManager()
    private val storageManager = VideoStorageManager(application)
    private val cameraController = CameraController(application)

    private val voiceTriggerRecorder = VoiceTriggerRecorder(
        kwsManager = kwsManager,
        preRecordManager = preRecordManager,
        storageManager = storageManager,
        scope = viewModelScope,
        framePipeline = framePipeline,
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

    // 持续监听 Settings 变化 → 同步到 VoiceTriggerRecorder 和帧管线（含初始加载）
    // 解决 StateFlow stateIn 初始值与磁盘加载值之间的时序不匹配问题
    init {
        viewModelScope.launch {
            preRecordDuration.collect { duration ->
                voiceTriggerRecorder.setPreRecordDuration(duration)
            }
        }
        // 分辨率和方向变化时同步更新 VoiceTriggerRecorder 和帧管线
        viewModelScope.launch {
            combine(resolutionProfile, recordOrientation) { profile, orientation ->
                Pair(profile, orientation)
            }.collect { (profile, orientation) ->
                voiceTriggerRecorder.setResolutionProfile(profile)
                voiceTriggerRecorder.setRecordOrientation(orientation)
                // 使用相机原生 landscape 尺寸作为帧管线目标。
                // 相机产出 landscape 帧（如 3840×2160），不做旋转直接编码，
                // MP4 中设置 rotation=90° 让播放器旋转显示为竖屏。
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
            // 设置同步已由 init {} 中的 collect 处理
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
        // 使用相机原生 landscape 尺寸（不做旋转），MP4 中用 rotation 元数据旋转
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

    /**
     * 以下 set 方法只写入 Settings（持久化），
     * VoiceTriggerRecorder 的同步由 init {} 中的 collect 自动完成。
     * 这样即使用户保存的设置从磁盘异步加载，也能正确同步到编码器。
     */
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

    private fun monitorBattery() {
        val bm = getApplication<Application>().getSystemService(android.os.BatteryManager::class.java)
        _batteryLevel.value = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    override fun onCleared() {
        super.onCleared()
        voiceTriggerRecorder.release()
        cameraController.release()
    }
}

package cn.leeyuanxia.sportcamera.di

import android.content.Context
import cn.leeyuanxia.sportcamera.domain.PreRecordManager
import cn.leeyuanxia.sportcamera.domain.VoiceTriggerRecorder
import cn.leeyuanxia.sportcamera.hardware.audio.KwsManager
import cn.leeyuanxia.sportcamera.hardware.camera.CameraController
import cn.leeyuanxia.sportcamera.hardware.camera.CameraFramePipeline
import cn.leeyuanxia.sportcamera.hardware.storage.VideoStorageManager
import cn.leeyuanxia.sportcamera.power.PowerStateManager
import cn.leeyuanxia.sportcamera.power.ThermalThrottler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动 DI 容器 — 集中管理所有模块实例
 *
 * 单 Activity 架构下，手动 DI 比 Hilt 更简单高效。
 * 在 SportCameraApp 中初始化，ViewModel 中引用。
 */
class AppContainer private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        fun initialize(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: AppContainer(context.applicationContext).also { instance = it }
            }
        }

        fun getInstance(): AppContainer = instance ?: throw IllegalStateException("AppContainer 未初始化")
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val kwsManager = KwsManager(context.assets)
    val preRecordManager = PreRecordManager()
    val cameraController = CameraController(context)
    val storageManager = VideoStorageManager(context)
    val powerStateManager = PowerStateManager(context)
    val thermalThrottler = ThermalThrottler(context)
    val framePipeline = CameraFramePipeline()

    val voiceTriggerRecorder = VoiceTriggerRecorder(
        kwsManager = kwsManager,
        preRecordManager = preRecordManager,
        storageManager = storageManager,
        scope = appScope,
        framePipeline = framePipeline,
    )
}
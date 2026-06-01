package cn.leeyuanxia.sportcamera.domain.model

/**
 * 录制配置 — 聚合用户的所有偏好设置
 */
data class RecordConfig(
    val preRecordDuration: PreRecordDuration = PreRecordDuration.DEFAULT,
    val cameraLens: CameraLens = CameraLens.DEFAULT,
    val resolutionProfile: ResolutionProfile = ResolutionProfile.DEFAULT,
    val recordOrientation: RecordOrientation = RecordOrientation.DEFAULT,
)
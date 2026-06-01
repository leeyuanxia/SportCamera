package cn.leeyuanxia.sportcamera.domain

/**
 * 应用状态机 — 驱动 UI 显示和业务逻辑流转
 *
 * 流程：Idle → Standby → Recording → Saving → Standby（循环）
 */
sealed interface AppState {

    /** 未启动，初始状态 */
    data object Idle : AppState

    /** KWS 监听中 + 预录环形缓冲运行 */
    data object Standby : AppState

    /** 唤醒后正在录制 post 段 */
    data class Recording(
        val elapsedMs: Long,
        val targetMs: Long,
    ) : AppState

    /** 正在合成/保存视频文件 */
    data class Saving(val progress: Float) : AppState

    /** 错误状态 */
    data class Error(val message: String) : AppState
}

/** 便于 UI 显示的状态标签 */
val AppState.label: String
    get() = when (this) {
        AppState.Idle -> "待机"
        AppState.Standby -> "监听中"
        is AppState.Recording -> "录制中 ${elapsedMs / 1000}s / ${targetMs / 1000}s"
        is AppState.Saving -> "保存中 ${(progress * 100).toInt()}%"
        is AppState.Error -> "错误: $message"
    }

/** 录制中时为 true */
val AppState.isRecording: Boolean
    get() = this is AppState.Recording

/** 活跃状态（Standby 或 Recording）时为 true */
val AppState.isActive: Boolean
    get() = this is AppState.Standby || this is AppState.Recording
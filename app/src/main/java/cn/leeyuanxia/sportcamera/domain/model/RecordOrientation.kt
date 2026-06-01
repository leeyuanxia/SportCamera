package cn.leeyuanxia.sportcamera.domain.model

/**
 * 录制方向 — 控制输出视频的宽高比
 *
 * PORTRAIT:  竖屏录制（9:16），默认模式，与 App 竖屏方向一致
 * LANDSCAPE: 横屏录制（16:9），交换 width/height
 *
 * App 以竖屏全屏运行（Manifest screenOrientation=portrait），
 * 此配置控制录制输出视频的宽高比：
 * - PORTRAIT：编码器直接使用 profile.width × profile.height（如 1080×1920）
 * - LANDSCAPE：编码器交换宽高（如 1920×1080），预览旋转90°
 */
enum class RecordOrientation(val label: String) {
    /** 竖屏录制（默认） */
    PORTRAIT("竖屏"),

    /** 横屏录制 */
    LANDSCAPE("横屏"),
    ;

    companion object {
        val DEFAULT = PORTRAIT
    }

    /**
     * 根据方向计算实际录制宽度
     * 竖屏时交换 width/height
     */
    fun effectiveWidth(profile: ResolutionProfile): Int = when (this) {
        LANDSCAPE -> profile.width
        PORTRAIT -> profile.height
    }

    /**
     * 根据方向计算实际录制高度
     * 竖屏时交换 width/height
     */
    fun effectiveHeight(profile: ResolutionProfile): Int = when (this) {
        LANDSCAPE -> profile.height
        PORTRAIT -> profile.width
    }
}
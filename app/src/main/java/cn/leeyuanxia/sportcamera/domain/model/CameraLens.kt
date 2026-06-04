package cn.leeyuanxia.sportcamera.domain.model

/**
 * 摄像头镜头类型
 */
enum class CameraLens(val label: String) {
    /** 标准后摄（默认） */
    WIDE("标准"),

    /** 超广角后摄（部分设备可用） */
    ULTRA_WIDE("广角"),

    /** 长焦后摄（部分设备可用） */
    TELEPHOTO("长焦"),

    /** 前置摄像头 */
    FRONT("前置");

    companion object {
        val DEFAULT = WIDE
    }
}

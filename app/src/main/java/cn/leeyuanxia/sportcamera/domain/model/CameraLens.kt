package cn.leeyuanxia.sportcamera.domain.model

/**
 * 摄像头镜头类型
 */
enum class CameraLens(val label: String) {
    /** 普通后摄（默认） */
    WIDE("标准"),

    /** 广角/超广角后摄 */
    ULTRA_WIDE("广角"),
    ;

    companion object {
        val DEFAULT = WIDE
    }
}
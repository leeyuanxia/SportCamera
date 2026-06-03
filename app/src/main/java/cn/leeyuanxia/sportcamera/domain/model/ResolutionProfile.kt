package cn.leeyuanxia.sportcamera.domain.model

/**
 * 录像分辨率/帧率档位（用户可选）
 *
 * 待机时统一使用低功耗 720p@30fps，
 * 唤醒后切换到用户选择的档位。
 */
enum class ResolutionProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val label: String,
    val shortLabel: String,
) {
    /** 平衡画质与功耗 */
    HD_720P_30(1280, 720, 30, 4_000_000, "720p 30fps", "720p"),

    /** 高清流畅 */
    HD_720P_60(1280, 720, 60, 7_000_000, "720p 60fps", "720p60"),

    /** 高清高帧率 */
    HD_720P_90(1280, 720, 90, 10_000_000, "720p 90fps", "720p90"),

    /** 全高清标准 */
    FHD_1080P_30(1920, 1080, 30, 8_000_000, "1080p 30fps", "1080p"),

    /** 高帧率运动场景 */
    FHD_1080P_60(1920, 1080, 60, 12_000_000, "1080p 60fps", "1080p60"),

    /** 超高清 */
    UHD_4K_30(3840, 2160, 30, 20_000_000, "4K 30fps", "4K"),

    /** 超高清高帧率 */
    UHD_4K_60(3840, 2160, 60, 50_000_000, "4K 60fps", "4K60"),
    ;

    /** 待机模式参数（省电） */
    companion object {
        val STANDBY_WIDTH = 1280
        val STANDBY_HEIGHT = 720
        val STANDBY_FPS = 30
        val STANDBY_BITRATE = 3_000_000
        val DEFAULT = FHD_1080P_30
    }
}
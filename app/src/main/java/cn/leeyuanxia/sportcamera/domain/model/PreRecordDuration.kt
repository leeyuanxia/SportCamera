package cn.leeyuanxia.sportcamera.domain.model

/**
 * 预录时长 — 支持预设快捷值和自定义秒数
 *
 * 当语音触发时，总时长 = 此值，前后各一半。
 * 例：10s → 前5s + 后5s = 10s 总时长
 *
 * 使用 @JvmInline value class 避免对象分配开销，
 * 直接用 Int 表示秒数，1~120s 范围。
 */
@JvmInline
value class PreRecordDuration(val seconds: Int) {

    init {
        require(seconds in MIN..MAX) { "预录时长必须在 ${MIN}s~${MAX}s 之间" }
    }

    /** 前半时长（毫秒） — 环形缓冲中获取 */
    val preHalfMs: Long get() = seconds * 500L

    /** 后半时长（毫秒） — 唤醒后继续录制 */
    val postHalfMs: Long get() = seconds * 500L

    /** 总时长（毫秒） */
    val totalMs: Long get() = seconds * 1000L

    /** 显示标签 */
    val label: String get() = "${seconds}s"

    companion object {
        const val MIN = 1
        const val MAX = 120
        val DEFAULT = PreRecordDuration(10)

        /** 预设快捷值 — UI 上的 Chips */
        val PRESETS = listOf(5, 10, 15, 30, 60).map { PreRecordDuration(it) }
    }
}
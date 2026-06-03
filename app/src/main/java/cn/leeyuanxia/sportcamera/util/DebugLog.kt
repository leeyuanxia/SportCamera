package cn.leeyuanxia.sportcamera.util

/**
 * 调试日志工具 — 仅在 Debug 包下输出日志，Release 包下静默
 *
 * 使用 BuildConfig.DEBUG 判断构建类型（需要 buildFeatures.buildConfig = true）。
 *
 * 使用方式：
 *   import cn.leeyuanxia.sportcamera.util.DebugLog
 *   DebugLog.d(TAG, "message")
 *   DebugLog.e(TAG, "error", exception)
 */
object DebugLog {

    private val enabled: Boolean get() = cn.leeyuanxia.sportcamera.BuildConfig.DEBUG

    fun d(tag: String, msg: String) {
        if (enabled) android.util.Log.d(tag, msg)
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.d(tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        if (enabled) android.util.Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.e(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        if (enabled) android.util.Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.w(tag, msg, tr)
    }

    fun w(tag: String, tr: Throwable) {
        if (enabled) android.util.Log.w(tag, tr)
    }

    fun i(tag: String, msg: String) {
        if (enabled) android.util.Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable) {
        if (enabled) android.util.Log.i(tag, msg, tr)
    }
}

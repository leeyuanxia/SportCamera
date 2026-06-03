package cn.leeyuanxia.sportcamera.power

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 省电管理器 — WakeLock 和电池状态监控
 *
 * 策略：
 * - 待机模式：PARTIAL_WAKE_LOCK（保持 CPU 运行） + 屏幕常亮最低亮度
 * - 录制模式：屏幕常亮正常亮度
 * - WakeLock 无超时限制，由 stop() 主动释放
 */
class PowerStateManager(private val context: Context) {

    companion object {
        private const val TAG = "PowerStateManager"
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    /**
     * 是否已获得电池优化白名单
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 获取待机 WakeLock（无超时）
     *
     * PARTIAL_WAKE_LOCK：CPU 保持运行。
     * 不设超时 — 运动相机场景需要长时间待机，由 stop() 主动释放。
     */
    fun acquireStandbyWakeLock() {
        releaseWakeLock()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SportCamera:StandbyWakeLock"
        ).apply {
            acquire() // 无超时，由 releaseWakeLock() 释放
        }
        DebugLog.d(TAG, "WakeLock 已获取（无超时）")
    }

    /**
     * 释放 WakeLock
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                DebugLog.d(TAG, "WakeLock 已释放")
            }
        }
        wakeLock = null
    }

    /**
     * 更新电池信息
     */
    fun updateBatteryInfo() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        _batteryLevel.value = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        _isCharging.value = bm.isCharging
    }

    fun release() = releaseWakeLock()
}

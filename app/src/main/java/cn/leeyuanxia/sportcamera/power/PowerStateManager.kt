package cn.leeyuanxia.sportcamera.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 省电管理器 — WakeLock 和电池状态监控
 *
 * 策略：
 * - 待机模式：PARTIAL_WAKE_LOCK（保持 CPU，允许屏幕关闭）
 * - 录制模式：PARTIAL_WAKE_LOCK + FLAG_KEEP_SCREEN_ON（屏幕保持亮）
 * - 30分钟安全阀：防止 WakeLock 无限持有
 */
class PowerStateManager(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    /**
     * 获取待机 WakeLock
     *
     * PARTIAL_WAKE_LOCK：CPU 保持运行，屏幕和键盘可以关闭。
     * 这是最省电的 WakeLock 类型，适合长时间待机场景。
     */
    fun acquireStandbyWakeLock() {
        releaseWakeLock()
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SportCamera:StandbyWakeLock"
        ).apply {
            acquire(30 * 60 * 1000L) // 30分钟安全阀
        }
    }

    /**
     * 释放 WakeLock
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
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
package cn.leeyuanxia.sportcamera.power

import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 温度自适应降频器
 *
 * 监听 Android ThermalService 回调，自动调整预录参数：
 * - Normal → 30fps / 3Mbps（默认）
 * - Moderate → 20fps / 2Mbps（降低编码负载）
 * - Severe → 10fps / 1Mbps（大幅降低）
 * - Emergency → 4fps / 400Kbps（最低功耗保命）
 */
class ThermalThrottler(private val context: Context) {

    companion object {
        private const val TAG = "ThermalThrottler"
    }

    data class ThrottleConfig(
        val preRecordFps: Int,
        val preRecordBitrateBps: Int,
        val kwsReadIntervalMs: Long,
    )

    private val _config = MutableStateFlow(ThrottleConfig(30, 3_000_000, 100))
    val config: StateFlow<ThrottleConfig> = _config.asStateFlow()

    private val _thermalLevel = MutableStateFlow("Normal")
    val thermalLevel: StateFlow<String> = _thermalLevel.asStateFlow()

    init {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // API 34+ addThermalStatusListener
        pm.addThermalStatusListener { status ->
            val newConfig = when (status) {
                PowerManager.THERMAL_STATUS_LIGHT -> ThrottleConfig(24, 2_400_000, 120)
                PowerManager.THERMAL_STATUS_MODERATE -> ThrottleConfig(20, 2_000_000, 150)
                PowerManager.THERMAL_STATUS_SEVERE -> ThrottleConfig(10, 1_000_000, 300)
                PowerManager.THERMAL_STATUS_CRITICAL -> ThrottleConfig(6, 600_000, 500)
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThrottleConfig(4, 400_000, 800)
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThrottleConfig(2, 200_000, 1000)
                else -> ThrottleConfig(30, 3_000_000, 100) // Normal / None
            }
            _config.value = newConfig
            _thermalLevel.value = thermalStatusToString(status)
        }
    }

    private fun thermalStatusToString(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
        else -> "Unknown($status)"
    }

    /**
     * 释放热管理监听器
     */
    fun release() {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.removeThermalStatusListener { }
        } catch (e: Exception) {
            Log.w(TAG, "移除热管理监听器失败: ${e.message}")
        }
    }
}
package cn.leeyuanxia.sportcamera.power

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 温度自适应降频器
 *
 * 监听 Android ThermalService 回调，自动调整预录参数：
 * - Normal → 15fps / 1.5Mbps（默认）
 * - Moderate → 10fps / 1Mbps（降低编码负载）
 * - Severe → 5fps / 500Kbps（大幅降低）
 * - Emergency → 3fps / 300Kbps（最低功耗保命）
 */
class ThermalThrottler(private val context: Context) {

    data class ThrottleConfig(
        val preRecordFps: Int,
        val preRecordBitrateBps: Int,
        val kwsReadIntervalMs: Long,
    )

    private val _config = MutableStateFlow(ThrottleConfig(15, 1_500_000, 100))
    val config: StateFlow<ThrottleConfig> = _config.asStateFlow()

    private val _thermalLevel = MutableStateFlow("Normal")
    val thermalLevel: StateFlow<String> = _thermalLevel.asStateFlow()

    init {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // API 34+ addThermalStatusListener
        pm.addThermalStatusListener { status ->
            val newConfig = when (status) {
                PowerManager.THERMAL_STATUS_LIGHT -> ThrottleConfig(12, 1_200_000, 120)
                PowerManager.THERMAL_STATUS_MODERATE -> ThrottleConfig(10, 1_000_000, 150)
                PowerManager.THERMAL_STATUS_SEVERE -> ThrottleConfig(5, 500_000, 300)
                PowerManager.THERMAL_STATUS_CRITICAL -> ThrottleConfig(3, 300_000, 500)
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThrottleConfig(2, 200_000, 800)
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThrottleConfig(1, 100_000, 1000)
                else -> ThrottleConfig(15, 1_500_000, 100) // Normal / None
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
}
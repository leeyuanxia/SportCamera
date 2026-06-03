package cn.leeyuanxia.sportcamera.power

import android.content.Context
import android.os.PowerManager
import cn.leeyuanxia.sportcamera.util.DebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 温度自适应降频器
 *
 * 监听 Android ThermalService 回调，自动调整预录参数。
 * 以用户选择的 profileFps/profileBitrate 为基准，按热等级比例降频：
 * - Normal → 100%（用户选择的帧率和码率）
 * - Light → 80%
 * - Moderate → 67%
 * - Severe → 33%
 * - Critical → 20%
 * - Emergency → 13%
 * - Shutdown → 7%
 *
 * 典型值（以 60fps / 12Mbps 为例）：
 * - Normal → 60fps / 12Mbps
 * - Moderate → 40fps / 8Mbps
 * - Severe → 20fps / 4Mbps
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

    /** 用户选择的 profile 帧率（默认 30） */
    @Volatile
    private var profileFps: Int = 30

    /** 用户选择的 profile 码率（默认 3Mbps） */
    @Volatile
    private var profileBitrateBps: Int = 3_000_000

    /** 当前热等级 */
    private var currentThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE

    private val _config = MutableStateFlow(ThrottleConfig(30, 3_000_000, 100))
    val config: StateFlow<ThrottleConfig> = _config.asStateFlow()

    private val _thermalLevel = MutableStateFlow("Normal")
    val thermalLevel: StateFlow<String> = _thermalLevel.asStateFlow()

    /**
     * Surface 模式下帧率变更回调 — 通知 CameraController 更新 AE FPS Range
     *
     * Surface 模式无法通过 skipPattern 跳帧，帧率控制只能通过
     * Camera2 的 CONTROL_AE_TARGET_FPS_RANGE 实现。
     * 此回调在 recalculateConfig() 中触发。
     */
    var onSurfaceFpsChanged: ((fps: Int) -> Unit)? = null

    /**
     * 设置用户的 profile 参数
     *
     * 当用户切换分辨率/帧率档位时调用，热管理配置会基于新参数重新计算。
     * 例如从 1080P@30fps 切换到 1080P@60fps，Normal 状态下的 preRecordFps
     * 会从 30 变为 60。
     */
    fun setProfileParams(fps: Int, bitrateBps: Int) {
        if (profileFps == fps && profileBitrateBps == bitrateBps) return
        profileFps = fps
        profileBitrateBps = bitrateBps
        DebugLog.d(TAG, "Profile 更新: ${fps}fps / ${bitrateBps / 1000}kbps, 重新计算热管理配置")
        recalculateConfig(currentThermalStatus)
    }

    init {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        // API 34+ addThermalStatusListener
        pm.addThermalStatusListener { status ->
            currentThermalStatus = status
            recalculateConfig(status)
        }
    }

    /**
     * 根据当前热等级和用户 profile 参数，重新计算预录配置
     */
    private fun recalculateConfig(status: Int) {
        val ratio = when (status) {
            PowerManager.THERMAL_STATUS_LIGHT -> 0.8
            PowerManager.THERMAL_STATUS_MODERATE -> 0.667
            PowerManager.THERMAL_STATUS_SEVERE -> 0.333
            PowerManager.THERMAL_STATUS_CRITICAL -> 0.2
            PowerManager.THERMAL_STATUS_EMERGENCY -> 0.133
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 0.067
            else -> 1.0 // Normal / None
        }

        val fps = maxOf(2, (profileFps * ratio).toInt())
        val bitrateBps = maxOf(200_000, (profileBitrateBps * ratio).toInt())

        // KWS 间隔不按比例，按热等级固定值（避免高频时延迟过大）
        val kwsIntervalMs = when (status) {
            PowerManager.THERMAL_STATUS_LIGHT -> 120L
            PowerManager.THERMAL_STATUS_MODERATE -> 150L
            PowerManager.THERMAL_STATUS_SEVERE -> 300L
            PowerManager.THERMAL_STATUS_CRITICAL -> 500L
            PowerManager.THERMAL_STATUS_EMERGENCY -> 800L
            PowerManager.THERMAL_STATUS_SHUTDOWN -> 1000L
            else -> 100L
        }

        val newConfig = ThrottleConfig(fps, bitrateBps, kwsIntervalMs)
        val levelName = thermalStatusToString(status)
        DebugLog.d(TAG, "热管理配置: level=$levelName, ratio=${"%.1f".format(ratio)}, " +
                "${fps}fps / ${bitrateBps / 1000}kbps (profile: ${profileFps}fps / ${profileBitrateBps / 1000}kbps)")
        _config.value = newConfig
        _thermalLevel.value = levelName
        // Surface 模式下通知 CameraController 更新 AE FPS Range
        onSurfaceFpsChanged?.invoke(fps)
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
            DebugLog.w(TAG, "移除热管理监听器失败: ${e.message}")
        }
    }
}

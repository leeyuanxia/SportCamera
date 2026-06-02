package cn.leeyuanxia.sportcamera.hardware.camera

import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * 相机专用 LifecycleOwner — 待机模式下保持 STARTED 状态
 *
 * 问题背景：
 * CameraX 的 bindToLifecycle() 绑定 Activity 生命周期。
 * 锁屏 → Activity.onStop() → CameraX 停止相机 → 预录缓冲无帧 → 录像黑屏。
 *
 * 解决方案：
 * 用此类包装 Activity 的 LifecycleOwner：
 * - 正常模式：镜像 Activity 生命周期，相机跟随 Activity 启停
 * - 待机模式：即使 Activity 进入 STOPPED（锁屏），仍向 CameraX 报告 STARTED
 * - Activity 销毁时：无论是否待机，都报告 DESTROYED（安全释放相机）
 *
 * 用法：
 * 1. bindCamera() 时将此类传给 CameraController.bindToLifecycle() 而非 Activity
 * 2. enterStandby() 时调用 enterStandby()
 * 3. stopStandby() 时调用 exitStandby()
 */
class CameraLifecycleOwner : LifecycleOwner {

    companion object {
        private const val TAG = "CameraLifecycle"
    }

    private val registry = LifecycleRegistry(this)

    /** 是否处于待机模式 */
    @Volatile
    private var standbyMode = false

    /** 被包装的 Activity LifecycleOwner */
    @Volatile
    private var wrappedOwner: LifecycleOwner? = null

    /** 监听 Activity 生命周期变化 */
    private val activityObserver = LifecycleEventObserver { _, _ ->
        syncState()
    }

    /**
     * 绑定 Activity 的 LifecycleOwner
     *
     * 必须在 bindToLifecycle() 之前调用，且 Activity 至少处于 CREATED 状态。
     * 重复调用会自动解绑旧的 Owner 并绑定新的（处理 Activity 重建场景）。
     */
    fun setWrappedOwner(owner: LifecycleOwner) {
        wrappedOwner?.lifecycle?.removeObserver(activityObserver)
        wrappedOwner = owner
        owner.lifecycle.addObserver(activityObserver)
        syncState()
        Log.d(TAG, "已绑定 Activity 生命周期，当前状态: ${owner.lifecycle.currentState}")
    }

    /**
     * 进入待机模式
     *
     * 调用后，即使 Activity 进入 STOPPED（锁屏），相机仍保持运行。
     */
    fun enterStandby() {
        if (standbyMode) return
        standbyMode = true
        syncState()
        Log.d(TAG, "进入待机模式 — 相机生命周期锁定为 STARTED")
    }

    /**
     * 退出待机模式
     *
     * 恢复镜像 Activity 生命周期。如果当前 Activity 已停止，相机会立即停止。
     */
    fun exitStandby() {
        if (!standbyMode) return
        standbyMode = false
        syncState()
        Log.d(TAG, "退出待机模式 — 恢复 Activity 生命周期镜像")
    }

    /**
     * 销毁 — 释放所有观察者，报告 DESTROYED
     *
     * 在 ViewModel.onCleared() 中调用，确保相机被释放。
     */
    fun destroy() {
        wrappedOwner?.lifecycle?.removeObserver(activityObserver)
        wrappedOwner = null
        standbyMode = false
        registry.currentState = Lifecycle.State.DESTROYED
        Log.d(TAG, "已销毁")
    }

    override val lifecycle: Lifecycle get() = registry

    /**
     * 同步生命周期状态
     *
     * 核心逻辑：
     * - Activity DESTROYED → 始终 DESTROYED（安全释放）
     * - 待机模式 + Activity ≥ CREATED → STARTED（锁屏不断相机）
     * - 其他情况 → 镜像 Activity 状态
     */
    private fun syncState() {
        val owner = wrappedOwner
        if (owner == null) {
            registry.currentState = Lifecycle.State.DESTROYED
            return
        }

        val activityState = owner.lifecycle.currentState

        val newState = when {
            // Activity 已销毁 → 无论如何都释放相机
            activityState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED

            // 待机模式 + Activity 至少 CREATED → 保持 STARTED（锁屏不断相机）
            standbyMode && activityState >= Lifecycle.State.CREATED -> Lifecycle.State.STARTED

            // 正常模式 → 镜像 Activity 状态
            else -> activityState
        }

        if (registry.currentState != newState) {
            Log.d(TAG, "状态变更: ${registry.currentState} → $newState (Activity=$activityState, standby=$standbyMode)")
            registry.currentState = newState
        }
    }
}

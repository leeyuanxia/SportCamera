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
 * 生命周期：
 * 此类是 AppContainer 中的单例，跨 ViewModel 复用。
 * 当 ViewModel.onCleared() 调用 destroy() 后，下一个 ViewModel 调用 setWrappedOwner()
 * 时会自动重建 LifecycleRegistry（因为 DESTROYED 状态不可逆）。
 */
class CameraLifecycleOwner : LifecycleOwner {

    companion object {
        private const val TAG = "CameraLifecycle"
    }

    /**
     * LifecycleRegistry — 必须是 var，因为 DESTROYED 不可逆，需要整体替换
     */
    private var registry = LifecycleRegistry(this)

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
     * 必须在 bindToLifecycle() 之前调用。
     * 支持重复调用（Activity 重建、ViewModel 更换时自动解绑旧 Owner 绑定新的）。
     * 如果上一次 LifecycleRegistry 已 DESTROYED，自动创建新实例。
     */
    fun setWrappedOwner(owner: LifecycleOwner) {
        wrappedOwner?.lifecycle?.removeObserver(activityObserver)
        wrappedOwner = owner

        // DESTROYED 不可逆 → 必须创建新 Registry
        if (registry.currentState == Lifecycle.State.DESTROYED) {
            registry = LifecycleRegistry(this)
            Log.d(TAG, "LifecycleRegistry 已重建（之前为 DESTROYED）")
        }

        owner.lifecycle.addObserver(activityObserver)
        syncState()
        Log.d(TAG, "已绑定 Activity 生命周期，当前状态: ${owner.lifecycle.currentState}")
    }

    /**
     * 进入待机模式
     */
    fun enterStandby() {
        if (standbyMode) return
        standbyMode = true
        syncState()
        Log.d(TAG, "进入待机模式 — 相机生命周期锁定为 STARTED")
    }

    /**
     * 退出待机模式
     */
    fun exitStandby() {
        if (!standbyMode) return
        standbyMode = false
        syncState()
        Log.d(TAG, "退出待机模式 — 恢复 Activity 生命周期镜像")
    }

    /**
     * 销毁 — 释放观察者，报告 DESTROYED
     *
     * 在 ViewModel.onCleared() 中调用。下次 setWrappedOwner() 会自动重建。
     */
    fun destroy() {
        wrappedOwner?.lifecycle?.removeObserver(activityObserver)
        wrappedOwner = null
        standbyMode = false
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
        Log.d(TAG, "已销毁")
    }

    override val lifecycle: Lifecycle get() = registry

    /**
     * 同步生命周期状态
     */
    private fun syncState() {
        // 跳过已 DESTROYED 的 Registry（等待 setWrappedOwner 重建）
        if (registry.currentState == Lifecycle.State.DESTROYED) return

        val owner = wrappedOwner
        if (owner == null) {
            registry.currentState = Lifecycle.State.DESTROYED
            return
        }

        val activityState = owner.lifecycle.currentState

        val newState = when {
            activityState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
            standbyMode && activityState >= Lifecycle.State.CREATED -> Lifecycle.State.STARTED
            else -> activityState
        }

        if (registry.currentState != newState) {
            Log.d(TAG, "状态变更: ${registry.currentState} → $newState (Activity=$activityState, standby=$standbyMode)")
            registry.currentState = newState
        }
    }
}

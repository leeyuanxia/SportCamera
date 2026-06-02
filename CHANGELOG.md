# 更新日志

所有重要更改均记录在此文件中。

## [2026-06-02] 省电优化 & 锁屏黑屏修复

### 修复

- **预录视频前半段帧率低**：待机预录帧率从 15fps 提升至 30fps，码率从 1.5Mbps 提升至 3Mbps，消除合成视频前后半段帧率不一致导致的卡顿感
- **锁屏后录像黑屏**：新增 `CameraLifecycleOwner` 包装类，待机模式下拦截 Activity 的 `onStop()` 事件，使 CameraX 相机在锁屏后持续采集帧数据
- **WakeLock 30 分钟超时**：移除 `PowerManager.PARTIAL_WAKE_LOCK` 的 30 分钟超时限制，改为由 `stop()` 主动释放，支持长时间待机

### 新增

- **待机时预览自动隐藏**：进入待机后相机预览画面自动隐藏（黑色覆盖，OLED 屏幕不发光省电），CameraX 内部持续采集不受影响
- **录制时自动显示预览**：语音唤醒开始录制时预览画面自动恢复显示
- **窥视预览 30s**：待机模式下点击「👁 预览」按钮可临时查看相机画面，30 秒后自动关闭
- **UI 叠加层显隐切换**：待机模式下点击「🌑 隐藏界面」按钮隐藏所有 UI 控件（OLED 全黑最省电），点击屏幕任意位置可重新唤醒
- **待机时屏幕常亮最低亮度**：待机模式自动将屏幕亮度降至最低并保持常亮，防止 Activity 进入 `onStop()` 导致相机断流；录制时恢复系统默认亮度
- **电池优化白名单**：新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，待机/空闲状态下显示「🔋 省电」按钮引导用户关闭电池优化
- **待机精简控制栏**：待机模式下底部控制栏简化为四个按钮：窥视预览 / 隐藏界面 / 省电 / 停止

### 变更

- 热管理降频表所有等级 FPS 和 bitrate 按比例翻倍（Normal: 30fps/3Mbps, Light: 24fps/2.4Mbps, ... , Shutdown: 2fps/200Kbps）
- 环形缓冲区内存从 ~5.6MB 增至 ~11.25MB（30s@3Mbps），现代设备完全可接受

### 涉及文件

**新增文件：**
- `hardware/camera/CameraLifecycleOwner.kt` — 待机感知的 LifecycleOwner
- `ui/component/StandbyOverlay.kt` — 待机状态叠加层（预览隐藏时的最小信息显示）

**核心修改：**
- `domain/model/ResolutionProfile.kt` — STANDBY_FPS/BITRATE 常量
- `hardware/camera/RingBufferRecorder.kt` — 编码器常量 + KDoc 修正
- `power/ThermalThrottler.kt` — 降频表全量更新
- `power/PowerStateManager.kt` — WakeLock 无超时 + 电池优化查询
- `di/AppContainer.kt` — 添加 CameraLifecycleOwner 实例
- `viewmodel/CameraViewModel.kt` — previewVisible/uiVisible 状态 + peek + 电池优化
- `ui/screen/MainScreen.kt` — 条件预览 + 亮度控制 + UI 显隐
- `ui/component/CameraPreview.kt` — 支持 isVisible 参数
- `ui/component/ControlBar.kt` — 待机精简模式 + 新按钮
- `AndroidManifest.xml` — 电池优化权限

**文档同步：**
- `CLAUDE.md`、`docs/performance-and-power.md`、`docs/architecture.md`、`docs/video-pipeline.md`

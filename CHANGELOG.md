# 更新日志

所有重要更改均记录在此文件中。

## [2026-06-03] 视频防抖 (EIS)

### 新增

- **电子防抖 (EIS)**：通过 Camera2 `CONTROL_VIDEO_STABILIZATION_MODE` 开启硬件电子防抖，骑行/跑步等运动场景画面更稳定
- **双路径覆盖**：CameraX 路径（Camera2Interop）和 Camera2 Surface 路径（CaptureRequest）均支持
- **能力检测**：启动时自动查询 `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`，不支持的设备自动隐藏开关
- **录制保护**：录制中禁止切换防抖，避免视频中途画面跳动
- **热管理兼容**：Surface 模式降频时通过 `rebuildSurfaceCaptureRequest()` 保留 EIS 设置不丢失

### 涉及文件

- `data/SettingsRepository.kt` — `booleanPreferencesKey("video_stabilization")` 持久化
- `hardware/camera/CameraController.kt` — EIS 检测、设置、CameraX/Camera2 双路径应用
- `viewmodel/CameraViewModel.kt` — `videoStabilization`/`eisSupported` StateFlow
- `ui/component/ControlBar.kt` — 设置面板「视频防抖」关闭/开启 Chip
- `ui/screen/MainScreen.kt` — 接线

---

## [2026-06-03] 4K@60fps Surface 模式 & 双路径编码

### 新增

- **4K@60fps Camera2 Surface 模式**：使用 Camera2 API 直接将相机输出写入编码器 InputSurface（零拷贝），绕过 CameraX ImageAnalysis 的 ISP YUV 带宽瓶颈，实现真正 60fps
- **4K 零拷贝路径 (feedFrameDirect)**：4K@30fps 时 YUV 直接写入编码器输入缓冲区，省去 ~12MB ByteArray 中转，减少 3-4ms/帧
- **ResolutionProfile.UHD_4K_60**：新增 4K 60fps 档位（3840×2160, 60fps, 50Mbps）
- **DebugLog 统一日志工具**：`util/DebugLog.kt` 封装 `android.util.Log`，Debug 包输出、Release 包完全静默，统一 TAG 前缀 `SportCameraLogger`
- **CameraController 双模式**：CameraX（非 4K）和 Camera2 Surface（4K@60fps）双模式切换，含 FPS Range 解析、热管理降频
- **编码器 Level 自动选择**：`mbPerSec > 1,000,000` 使用 Level 5.2（4K@60fps），其他使用 Level 4
- **Surface 模式降级机制**：Camera2 绑定失败时自动降级回 ByteBuffer 模式
- **音频预录 (RingBufferAudioRecorder)**：待机时持续采集麦克风 PCM → AAC 编码写入环形缓冲（~480KB/30s），唤醒时与后段音频拼接，确保前半段也有声音
- **音频录制 (AudioRecorder)**：录制时采集麦克风并编码 AAC-LC，通过 `VideoStorageManager` 与视频合成 MP4
- **KWS 音频双路采集**：KwsManager 与 RingBufferAudioRecorder 共享一个 AudioRecord 实例，节省音频硬件资源
- **热管理降频策略改进**：从固定数值表改为用户 profile 百分比降频（Normal=100%, Light=80%, Moderate=67%, Severe=33%, Critical=20%, Emergency=13%, Shutdown=7%），不同分辨率档位自适应

### 修复

- **1080p/720p 画面绿色覆盖 + 左半有画面右半没有**：重构 `YuvConverter.imageToNv12()` 提取 `interleaveUv()` 方法时 `dstOffset` 传了 `0` 而非 `width * height`，导致 UV 数据覆盖 Y 平面前半部分。4K 因走零拷贝路径未受影响
- **全项目日志统一**：所有 `android.util.Log` 调用替换为 `DebugLog`

### 变更

- `RingBufferRecorder` 新增 Surface 输入模式（`prepareWithSurface()`、`inputSurface`、`useSurfaceInput`）
- `FrameConsumer` 接口新增 `feedFrameDirect()` 方法（默认返回 false）
- `CameraFramePipeline` 新增 `surfaceMode` 标志和 `cameraFps` 帧率跟踪
- `PreRecordManager` 新增 `createSurfaceEncoder()` 和 `encoderSurfaceReady` 回调
- `VoiceTriggerRecorder` 新增 PreviewView 引用传递和 Surface 模式分支
- 热管理 Surface 模式：不重建编码器，通过 Camera2 AE FPS Range 控制帧率

### 涉及文件

**新增文件：**
- `util/DebugLog.kt` — 日志封装工具（Debug 输出/Release 静默）
- `hardware/audio/RingBufferAudioRecorder.kt` — 音频环形缓冲录制器（待机预录音频）
- `hardware/audio/AudioRecorder.kt` — 音频录制编码器（录制时 AAC 编码）

**核心修改：**
- `hardware/camera/CameraController.kt` — Camera2 Surface 模式（`bindPreviewWithSurface`、`updateSurfaceFps`、`resolveSurfaceFpsRange`）
- `hardware/camera/CameraFramePipeline.kt` — Surface 模式标志、4K 零拷贝路径、`cameraFps` 帧率跟踪
- `hardware/camera/FrameConsumer.kt` — `imageToNv12Direct()` 零拷贝、`interleaveUvToBuffer()`、`feedFrameDirect()` 接口
- `hardware/camera/RingBufferRecorder.kt` — Surface 输入模式、Level 自动选择、`feedFrameDirect()`
- `domain/VoiceTriggerRecorder.kt` — Surface 模式分支、PreviewView 传递、热管理适配
- `domain/PreRecordManager.kt` — `createSurfaceEncoder()`、`encoderSurfaceReady` 回调、Surface 模式短路
- `domain/model/ResolutionProfile.kt` — 新增 `UHD_4K_60` 枚举值
- `viewmodel/CameraViewModel.kt` — `supportedFps` 暴露、PreviewView 传递
- `hardware/audio/KwsManager.kt` — 双路音频采集（KWS + AAC 编码共享 AudioRecord）
- `hardware/storage/VideoStorageManager.kt` — AAC 音轨合成支持（`assembleToMp4` 改为 `assembleToMp4WithAudio`）
- `domain/VideoAssembler.kt` — 合成流程新增音频帧整合
- `domain/VoiceTriggerRecorder.kt` — 音频预录集成（`ringAudioRecorder` + `audioRecorder`）
- `power/ThermalThrottler.kt` — 百分比降频策略 + 用户 profile 基准
- `ui/theme/Color.kt`、`Theme.kt`、`Type.kt` — 主题配色与字体更新
- `ui/component/RecordIndicator.kt` — 录制指示器组件改进

---

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

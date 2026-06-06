# 修改日志

> 从 CLAUDE.md 迁移的历史修改记录。最新在前。

---

## 2026-06-06

### 精简 CLAUDE.md + 横屏 UI 适配

**CLAUDE.md 精简**：将修改日志从 CLAUDE.md 分离到 `docs/changelog.md`，CLAUDE.md 从 1316 行精简到 ~110 行，保留核心编码规范。

**横屏 UI 修复**：

修改文件：
- `ui/screen/MainScreen.kt` — 横屏检测 + 右侧控制面板布局
- `ui/component/ControlBar.kt` — 横屏紧凑模式（可滚动设置面板、缩小间距）
- `ui/component/StatusBar.kt` — 横屏紧凑模式（缩小镜头 Chip 防止换行）

改动内容：

1. **横屏右侧面板布局**（MainScreen）：
   - 使用 `LocalConfiguration` 检测横屏方向
   - 横屏时：相机预览全屏 + 右侧 220dp 半透明控制面板（StatusBar 在上，ControlBar 在下）
   - 竖屏时：保持原有 TopCenter/BottomCenter 层级布局

2. **ControlBar 横屏紧凑模式**：
   - `isLandscape` 参数控制紧凑/标准布局
   - SettingsPanel 使用 `verticalScroll` + `weight(1f)` 确保可滚动不溢出
   - Chip 缩小 padding（12/6 → 8/4）和字体（labelLarge → labelSmall）
   - 间距全面缩小（12dp → 6dp，8dp → 4dp）
   - 横屏时隐藏 Slider 下方的说明文字

3. **StatusBar 横屏紧凑模式**：
   - 外层 padding 缩小（16/8 → 10/4）
   - 镜头 Chip 缩小（padding 10/4 → 5/2，字体 labelLarge → labelSmall，间距 6dp → 3dp）
   - 添加 `maxLines = 1` 防止文字换行

---

## 2026-06-05

### 修复魅族 20 等设备 60fps 灰色不可选（高速视频 FPS 检测）

**问题**：FPS 检测只读了 AE 普通模式，魅族 20 HAL 在普通模式下只报告 max=30fps，但系统相机通过 `HIGH_SPEED_VIDEO` 支持 60fps。

**修改**：`CameraController.kt` — 新增 `collectAllSupportedFps()` + `collectHighSpeedFps()` 综合扫描 FPS，更新 `resolveActualFpsFromCameraId()` 合并普通+高速视频 FPS。

### 修复 4K@30fps 帧率过低 + 停止待机后预览卡住（第七轮修复）

**问题**：4K@30fps 走 ByteBuffer 路径，上采样每帧 15-20ms 导致只有 16fps；停止待机后预览卡住。

**修改**：`VoiceTriggerRecorder.kt` — 4K@30fps 改用 Surface 模式；`RingBufferRecorder.kt` — 条件改为 `width >= 3840`；`CameraViewModel.kt` — 停止待机后重新 `bindPreview()`。

### 修复 4K@30fps 帧率计算错误（第三轮）

**修改**：`resolveActualFpsFromCameraId()` 改用用户请求帧率而非相机最大帧率，避免跳帧过度。

### 修复帧率节流计算 + 录制时长问题（第四轮）

**修改**：`CameraFramePipeline.setTargetFps()` 从整数除法改为 `ceil()` 浮点除法。

### 修复预览模式错误设置固定 AE FPS Range（第五轮）

**修改**：三个绑定方法只在 `previewRange != null` 时设置 AE Range，预览模式不设置。

### 添加实际帧率测量机制（第六轮）

**修改**：`CameraFramePipeline` 新增 `measuredFps` 字段，`onImageAvailable()` 中每 30 帧测量实际帧率，`setTargetFps()` 使用 `measuredFps` 计算 skipPattern。

### 修复 90fps 录制仅 30fps + 后台闪退 + 全面并发审计

**提交**: `b775b27`（10个修复）+ `338a8af`（8个修复）

#### 90fps 录制只有 30fps
- `collectAllSupportedFps()` 限制自动补全范围到 AE 最大帧率
- `resolveActualFpsFromCameraId()` 超过 AE 上限时 clamp
- `setTargetFps()` 重新添加 `ceil`

#### 后台回来闪退（CameraPreview 双重启动）
- `CameraPreview.kt` 移除 `NonCancellable`，新增去重守卫
- `CameraViewModel.kt` 新增 `isBinding` 并发守卫

#### 8 个严重/高危并发问题
1. **drainLoop 热管理竞态** — `PreRecordManager` 先递增 `ringBufferGeneration` 再 stop
2. **useSurfaceInput 硬编码** — 改为 `@Volatile var`，降级时更新
3. **inputSurface 释放顺序** — 先 stop/release encoder 再 release surface
4. **ensureEncoder 竞态** — 新增 `encoderLock` synchronized 保护
5. **rebuildCaptureRequest 无锁** — 新增 `requestLock` synchronized 保护
6. **setTargetFps/setCameraFps 顺序** — `setCameraFps()` 自动重新计算 skipPattern
7. **fpsRangeWasSetOnBind** — 添加 `@Volatile`
8. **Surface 降级标志覆盖** — 检查 `isSurfaceMode` 后才设标志

#### 8 个中低优先级修复
1. AudioRecorder `frames` → `ConcurrentLinkedDeque`
2. `PreRecordManager.encoderSurfaceReady` 添加 `@Volatile`
3. `ThermalThrottler.currentThermalStatus` 添加 `@Volatile`
4. 4 处 `CancellationException` 正确重抛
5. `cropAndScaleNv12` 除零防御
6. `resolveSurfaceFpsRange` 空数组兜底
7. `resolveCameraId` 兜底 ID
8. 录像数据为空 delay 2s→1s

---

## 2026-06-04

### CameraX → Camera2 完整迁移

CameraX 双 Use Case 裁切 FOV，迁移到纯 Camera2 + ImageReader 方案。移除全部 CameraX 依赖（CameraLifecycleOwner、ProcessCameraProvider 等），`PreviewView` 替换为 `TextureView`。

### 修复 Camera2 预览画面变形、FOV、帧率与画质

1. **分辨率分离** — 预览用 ~5MP，编码用传感器最大分辨率
2. **SCALER_CROP_REGION = 全幅** — 最大 FOV
3. **TextureView 变换矩阵** — 参考 Camera2Basic 处理方向
4. **预览不设 AE FPS Range** — 避免触发 HAL 传感器模式切换
5. **EIS 默认关闭** — 减小 FOV 裁切

### 修复广角预览模糊 + 多项优化

1. 预览模式不设 FPS Range，HAL 保持最佳画质
2. 缩放范围初始化补全
3. 镜头选择启动恢复
4. `cropAndScaleNv12` 升级双线性插值 + 修复宽高比 bug
5. ActiveRecorder AVC Level 动态选择

### 修复 4K@30fps 视频只有 10fps + 第二次录制数据为空（两轮）

**第一轮**：`resolveActualFpsFromCameraId()` 返回值修复，`restartStandby()` 加 `join()` 等待。
**第二轮**：`stop()` 不停止相机（保持帧到达），三个绑定方法补充 `setCameraFps()` 调用。

### 修复编译错误 - framePipeline 引用

三个绑定方法中 `framePipeline` 改为 `lastFramePipeline`。

### 超广角/长焦镜头支持（多轮迭代）

1. 物理 Camera2 直连 → FOV 裁切 → 逻辑相机 + `CONTROL_ZOOM_RATIO`（最终方案）
2. 双指捏合缩放 + 超广角切换
3. 混合缩放方案（逻辑相机缩放 + 物理相机 rebind）

### 缩放↔镜头按钮联动（已废弃）

初始方案在 `applyZoomDelta` 中直接修改 `currentLens` 导致异常，改为混合方案。

---

## 2026-06-03

### 新增长焦 (TELEPHOTO) 镜头支持（已移除）

初始实现通过 TELEPHOTO 枚举 + CameraSelector 直接绑定，因切换卡顿改为双指缩放。

### 新增双指捏合缩放（限制单摄数字缩放）

- `setLinearZoom()` FOV 线性映射
- 最大 3.0x 限制
- 镜头切换/重新绑定时自动重置
- Surface 模式下不支持缩放

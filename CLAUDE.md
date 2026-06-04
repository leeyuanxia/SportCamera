# SportCamera — 项目编码规范

> 本文件是 Claude Code 在此项目中编写代码时必须遵循的规范。
> 所有新代码和修改都应遵守以下约定。
> 思维必须使用中文

## 项目概述

- **包名**: `cn.leeyuanxia.sportcamera`
- **定位**: 运动相机 Android App，语音唤醒词自动触发录像，支持视频防抖 (EIS)
- **最低版本**: Android 14 (API 34) | **目标版本**: Android 16 (API 36)
- **技术栈**: Kotlin 100%, Jetpack Compose, Camera2 API, MediaCodec H.264, sherpa-onnx KWS
- **架构**: 单 Activity + MVVM + 手动 DI (`AppContainer`)

## 目录结构

```
app/src/main/java/cn/leeyuanxia/sportcamera/
├── domain/          # 业务逻辑层（状态机、编排器、管理器）
├── hardware/        # 硬件抽象层（摄像头、音频、存储）
├── power/           # 省电管理（WakeLock、热管理）
├── service/         # 前台服务
├── data/            # DataStore 设置持久化
├── viewmodel/       # ViewModel
├── ui/              # Compose UI
├── util/            # 工具类（DebugLog）
└── di/              # AppContainer 手动 DI
```

详细架构说明见 `docs/architecture.md`。

---

## 编码约定

### 1. 语言与注释

- 所有代码注释、文档说明使用**中文**
- 类和公开方法的 KDoc 使用中文描述
- Log tag 使用类名缩写（如 `TAG = "PreRecordManager"`）
- Log 消息使用中文，便于 Logcat 排查

### 2. Kotlin 风格

- 优先使用 `data class` / `value class` / `sealed interface` 建模
- 使用 `StateFlow` / `SharedFlow` 进行响应式数据传递，不用 LiveData
- 协程作用域：ViewModel 用 `viewModelScope`，AppContainer 用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`
- `CancellationException` 必须正确处理：catch 中区分 CancellationException 和其他异常，CancellationException 不应被吞掉

```kotlin
// ✅ 正确
catch (e: Exception) {
    if (e is CancellationException) {
        Log.d(TAG, "协程被取消")
    } else {
        Log.e(TAG, "异常: ${e.message}")
    }
}

// ❌ 错误 — CancellationException 被吞掉
catch (e: Exception) {
    Log.e(TAG, "异常: ${e.message}")
}
```

### 3. 线程安全规范（最重要）

本项目的数据流涉及三个线程，**必须严格遵守跨线程可见性规则**：

| 线程 | 身份 | 主要操作 |
|------|------|---------|
| `FrameAnalyzer` | 相机分析线程 | `feedFrame()`、`createBuffer()` |
| `Dispatchers.Main` | 主线程 | `drainLoop()`、`dumpPreFrames()`、KWS collect |
| `Dispatchers.IO` | IO 线程池 | `drainEncoder()`（withContext 切换） |

**规则**：

1. **跨线程读写的字段必须标记 `@Volatile`** — 不例外，不依赖"实践中可能可见"
2. 单线程内部使用的字段可以不加 `@Volatile`（如 `readyToCreate` 只在 feedFrame 中读写）
3. `ConcurrentLinkedDeque` 等并发集合只保证自身操作原子性，不提供字段级可见性
4. 状态重置方法（如 `stop()`、`release()`）必须重置所有标志位（`drainStarted`、`readyToCreate` 等）
5. **`MediaCodec encoder` 引用必须 `@Volatile`** — feed 在 FrameAnalyzer 线程，drain 在 IO 线程

```kotlin
// ✅ 正确 — 跨线程字段标记 @Volatile
@Volatile
private var ringBuffer: RingBufferRecorder? = null  // 相机线程写、主线程读

@Volatile
private var drainStarted: Boolean = false            // 同上

// ✅ 关键 — MediaCodec 编码器引用
// prepare()/start() 在 FrameAnalyzer 线程，drainEncoder() 在 IO 线程
@Volatile
private var encoder: MediaCodec? = null

// ✅ 正确 — 单线程字段不需要 @Volatile
private var readyToCreate: Boolean = false  // 只在 feedFrame（相机线程）中读写
```

> **历史教训（三次迭代）**:
> - 第一轮：`PreRecordManager.ringBuffer` 缺少 `@Volatile`，修复后仍然不工作
> - 第二轮（真正根因）：`RingBufferRecorder.encoder` 缺少 `@Volatile`
>   - `prepare()` 在 FrameAnalyzer 线程设置 `encoder = MediaCodec.create(...)`
>   - `drainEncoder()` 在 IO 线程读取 `val codec = encoder ?: return`
>   - 没有 `@Volatile`，IO 线程始终看到 `null`，drainEncoder 立即返回
>   - 结果：编码器接收了 249 帧输入但输出从未被 drain，环形缓冲永远为空
>   - **教训：对象引用的内部字段跨线程访问也需要 @Volatile，不能仅靠外层容器的 @Volatile**
> - 第三轮（YUV 偏移量 bug）：重构 `YuvConverter.imageToNv12()` 提取 `interleaveUv()` 时 `dstOffset` 传了 `0` 而非 `width * height`
>   - UV 数据从字节 0 开始写入，覆盖了 Y 平面的前半部分
>   - 症状：1080p/720p 画面绿色覆盖 + 左半有画面右半没有；4K 正常（走零拷贝路径）
>   - **教训：重构 NV12/YUV 代码时必须验证 UV 偏移量（`width * height`），分离方法时参数不要硬编码为 0**

### 4. 日志规范

- 使用项目封装的 `DebugLog` 工具类（`util/DebugLog.kt`），**不再直接使用 `android.util.Log`**
- `DebugLog` 统一 TAG 前缀 `SportCameraLogger`，消息格式 `"$tag  ---->$msg"`
- **Debug 包输出日志，Release 包完全静默**（通过 `BuildConfig.DEBUG` 控制）
- 关键状态变化必须记录日志：
  - 编码器创建/启动/停止
  - 协程启动/结束
  - 帧计数里程碑（每 150 帧记录一次）
  - 异常和超时
- 异常日志记录完整信息：异常类型 + 消息
- 错误路径用 `DebugLog.e`，正常流程用 `DebugLog.d`，罕见/可疑情况用 `DebugLog.w`

### 5. MediaCodec 使用规范

- **双输入模式**：
  - **ByteBuffer 模式**（默认）：通过 `dequeueInputBuffer` / `queueInputBuffer` 送入 NV12 数据
  - **Surface 模式**（4K@60fps）：通过 `createInputSurface()` 获取 Surface，Camera2 直接输出到编码器
- ByteBuffer 模式下 `dequeueInputBuffer` 超时使用 **1000μs (1ms)**，不用 0（部分设备 timeout=0 抛异常）
- 4K 零拷贝路径（`feedFrameDirect`）超时使用 **5000μs (5ms)**
- `dequeueOutputBuffer` 超时使用 **10_000μs (10ms)**
- 编码器启动顺序：`encoder.start()` → `isRunning = true`（防止 feedFrame 在未启动时调用）
- EOS 发送：
  - ByteBuffer 模式：`queueInputBuffer + BUFFER_FLAG_END_OF_STREAM`，需要重试循环
  - Surface 模式：释放 `inputSurface` 自动触发 EOS
- `drainEncoder()` 在 `withContext(Dispatchers.IO)` 中运行，`delay(1)` 作为 TRY_AGAIN_LATER 的退避

### 6. 编码器配置

- RingBufferRecorder（预录）：High Profile, 关键帧间隔 2s
- ActiveRecorder（录制）：High Profile, 关键帧间隔 1s
- Level 选择策略：
  - `mbPerSec = (width/16) * (height/16) * fps`
  - `mbPerSec > 1,000,000` → Level 5.2（4K@60fps 需要）
  - 其他 → Level 4（1080p 及以下足够）
- 两者使用相同 Profile 以确保 SPS/PPS 兼容（前后段拼接）
- Surface 模式（4K@60fps）：`KEY_COLOR_FORMAT` 使用 `COLOR_FormatSurface`，通过 `createInputSurface()` 零拷贝

### 7. 视频合成规范

- `preFrames + postFrames` 拼接时，过滤掉 Config 帧（SPS/PPS）
- CSD 数据优先从 `ActiveRecorder` 提取（通过 `INFO_OUTPUT_FORMAT_CHANGED`）
- PTS 归一化：`baseTimeUs = dataFrames.first().presentationTimeUs`
- 非单调 PTS 保护：`writePtsUs = max(ptsUs, lastPtsUs + 1)`
- 前段不足时自动延长后段：`postDurationMs = postHalfMs + shortfall`

### 8. 省电设计原则

- 待机模式：30fps 预录 + 100ms KWS 间隔（默认）
- 帧率节流在 YUV 转换**之前**执行（跳帧零开销）
- 缓冲区复用：`fullNv12Buffer` / `scaledNv12Buffer` 消除每帧 ~16MB 分配
- 热管理通过 `ThermalThrottler` 自适应降频，参数变化时才重建编码器
- **统一 Camera2 架构**：
  - 非 Surface 模式：Camera2 ImageReader (YUV_420_888) → YUV → NV12 → ByteBuffer → 编码器
  - 4K@60fps Surface 模式：Camera2 API → 编码器 InputSurface（零拷贝，绕过 ISP YUV 带宽瓶颈）
  - 判断条件：`width >= 3840 && fps > 30` 使用 Surface 模式
- **Surface 模式热管理**：不重建编码器，通过 Camera2 `CONTROL_AE_TARGET_FPS_RANGE` 控制帧率

### 9. UI / Compose

- 状态通过 `AppState` sealed interface 驱动 UI
- 设置项通过 `SettingsRepository` (DataStore) 持久化
- ViewModel 中使用 `stateIn(SharingStarted.Eagerly, ...)` 暴露 Flow 给 UI
- 不在 Composable 中直接调用硬件 API，一律通过 ViewModel

---

## 测试与验证

### 编译

```bash
./gradlew assembleDebug
```

### 关键 Logcat 过滤器

排查预录问题时使用：

```
adb logcat -s SportCameraLogger:D
```

排查 4K Surface 模式问题时使用：

```
adb logcat -s SportCameraLogger:D | grep -E "CameraController|Surface|Camera2"
```

### 常见问题排查

详见 `docs/performance-and-power.md` 第七节「故障排查」。

---

## 文档索引

重要：每次修改新增完代码请记录修改日志

| 文件 | 内容 |
|------|------|
| `docs/architecture.md` | 项目架构、模块职责、数据流 |
| `docs/video-pipeline.md` | 视频管线详解（YUV转换、编码、合成） |
| `docs/performance-and-power.md` | 省电优化、热管理、故障排查 |

---

## 修改日志

### 2026-06-04：修复广角预览模糊 + 多项优化

**修改文件：**
- `hardware/camera/CameraController.kt` — FPS Range 策略统一、缩放范围初始化、rebuildCaptureRequest 行为修正
- `viewmodel/CameraViewModel.kt` — 镜头选择启动恢复
- `hardware/camera/FrameConsumer.kt` — cropAndScaleNv12 升级双线性插值
- `hardware/camera/ActiveRecorder.kt` — AVC Level 动态选择

**改动内容：**

1. **广角预览模糊修复（根因）**：
   - `bindLogicalCameraWithZoom` 和 `bindPhysicalCameraInternal`（预览模式）原先使用 `resolveSurfaceFpsRange()` 显式设置 `CONTROL_AE_TARGET_FPS_RANGE`，导致 HAL 切换到低分辨率传感器模式
   - 改为使用 `resolveActualFpsFromCameraId()` 返回 null Range（与 WIDE 模式 `bindPreviewInternal` 一致），不设 FPS Range，HAL 使用默认值保持最佳画质
   - `bindPhysicalCameraInternal` 在录制模式（`encoderSurface != null`）时仍设置宽 FPS Range 确保帧率正确
   - 新增 `fpsRangeWasSetOnBind` 标志跟踪初始绑定策略，`rebuildCaptureRequest` 在缩放/EIS 切换时也遵循此策略
   - 热管理 `updateSurfaceFps()` 显式传入 Range，不受此策略影响

2. **缩放范围初始化补全**：
   - `bindLogicalCameraWithZoom` 和 `bindPhysicalCameraInternal` 添加了缺失的 `initZoomFromCameraCharacteristics()` 调用

3. **镜头选择启动恢复**：
   - `CameraViewModel.bindCamera()` 读取 `SettingsRepository` 持久化的镜头选择
   - 先绑定 WIDE 初始化缩放范围，再按持久化值 `switchLens` 恢复广角/长焦

4. **cropAndScaleNv12 双线性插值升级 + 修复宽高比 Bug**：
   - Y 平面和 UV 平面均从最近邻插值升级为 16.16 定点双线性插值
   - 减少传感器分辨率→编码器目标分辨率缩放时的锯齿和细节丢失
   - 修复 `dstAspect` 使用 `srcH` 而非 `dstH` 的 bug：原代码 `dstW/srcH`→修正为 `dstW/dstH`
   - **该 bug 导致裁剪计算错误（srcH 当 dstH），画面被横向压缩**
   - 每帧额外 ~2-3ms，非 Surface 模式路径可接受

5. **ActiveRecorder AVC Level 动态选择**：
   - 从硬编码 `AVCLevel4` 改为根据 `mbPerSec` 动态选择 Level（4K@60fps 使用 Level 5.2）

### 2026-06-03：新增长焦 (TELEPHOTO) 镜头支持（已移除）

初始实现通过 TELEPHOTO 枚举 + CameraSelector 直接绑定长焦物理子相机，
但因切换需 unbindAll → rebind 导致画面卡顿，改为通过双指缩放在逻辑相机上使用
`CameraControl.setZoomRatio()` 实现，CameraX 内部自动处理多摄切换。

已移除：TELEPHOTO 枚举、checkFocalLengthForTelephoto()、resolveCameraSelector TELEPHOTO 分支。

### 2026-06-03：新增双指捏合缩放（限制单摄数字缩放）

**修改文件：**
- `hardware/camera/CameraController.kt` — 新增缩放控制
- `viewmodel/CameraViewModel.kt` — 暴露缩放状态和方法
- `ui/screen/MainScreen.kt` — 添加双指捏合缩放手势
- `ui/component/StatusBar.kt` — 缩放倍率指示器（非 1.0x 时显示）

**改动内容：**
- 使用 `setLinearZoom()` 而非 `setZoomRatio()`，FOV 线性映射手感更自然
- 最大缩放限制在 `DEFAULT_ZOOM_CAP = 3.0x`，避免触发物理相机切换导致 FOV 中心跳变
- `applyZoomDelta()` 从 `zoomState.linearZoom` 读取实际值作为基准，避免缓存过期
- 镜头切换/重新绑定时自动重置为 1.0x
- Surface 模式（4K@60fps）下不支持缩放

### 2026-06-04：新增双指缩放切换超广角镜头

**修改文件：**
- `hardware/camera/CameraController.kt` — 新增 `minZoomRatio` StateFlow，修复缩放范围下限
- `viewmodel/CameraViewModel.kt` — 暴露 `minZoomRatio` 给 UI 层
- `ui/screen/MainScreen.kt` — 手势 key 和守卫条件支持 minZoomRatio < 1.0
- `ui/component/StatusBar.kt` — 缩放指示器支持 < 1.0x 显示

**改动内容：**
- 新增 `_minZoomRatio` StateFlow，从 `ZoomState.minZoomRatio` 读取（超广角设备为 0.5x 等）
- `applyZoomDelta()` 中 `coerceIn` 下限从硬编码 `1.0f` 改为 `_minZoomRatio.value`
- 双指捏合可缩小到超广角（如 0.5x），CameraX 逻辑相机自动切换物理子相机
- `initZoomFromCamera()` 同时读取 min/max zoomRatio 并记录日志
- `release()` 中重置 `_minZoomRatio`
- 手势 `pointerInput` key 包含 `minZoomRatio, maxZoomRatio`，守卫条件放宽为 `maxZoomRatio > 1.0f || minZoomRatio < 1.0f`
- 缩放指示器条件从 `zoomRatio > 1.05f` 改为 `abs(zoomRatio - 1.0f) > 0.05f`，0.5x 时显示 `"0.5x"` 徽章
- 保留 `ULTRA_WIDE` 枚举作为独立切换路径（缩放切换更流畅，枚举切换作为兜底）
- Surface 模式（4K@60fps）下不支持缩放（已有守卫，不受影响）

### 2026-06-04：缩放↔镜头按钮联动 + 广角按钮可用性修复（已废弃）

初始方案在 `applyZoomDelta` 中直接修改 `currentLens`，导致 `rebindWithProfile` 传入 ULTRA_WIDE
走独立 CameraSelector rebind 异常。已改为混合方案（见下）。

### 2026-06-04：混合缩放方案（逻辑相机缩放 + 超广角物理相机 rebind）

**修改文件：**
- `hardware/camera/CameraController.kt` — 混合缩放逻辑
- `viewmodel/CameraViewModel.kt` — 缩放触发镜头 rebind
- `ui/component/StatusBar.kt` — 镜头选中状态支持 ULTRA_WIDE

**改动内容：**
- `applyZoomDelta()` 返回值改为 `CameraLens?`：
  - 标准模式下缩到 minZoomRatio 底限后继续缩小 → 返回 `ULTRA_WIDE`（触发 rebind）
  - 超广角模式下放大超过 1.0x → 返回 `WIDE`（触发 rebind）
  - 正常缩放范围内返回 null
- `switchLens()` 恢复完整 rebind 路径（不再用缩放模拟）
- ViewModel `applyZoomDelta()` 检测返回值，非 null 时异步 `switchLens` + `refreshEisCapability`
- StatusBar 选中状态：`currentLens == ULTRA_WIDE` 时直接选中广角按钮，标准模式看 zoomRatio < 0.95

### 2026-06-04：Camera2 直连物理相机（超广角 + 长焦）

**问题背景**：
CameraX 不暴露物理子相机（如超广角 camera 3、长焦 camera 4），`validateLensesAgainstCameraX` 将其移除。
逻辑相机的 minZoomRatio=0.6x 只是物理超广角的裁切版，FOV 不如系统相机原生广角。

**修改文件：**
- `domain/model/CameraLens.kt` — 新增 `TELEPHOTO("长焦")` 枚举
- `hardware/camera/CameraController.kt` — 核心改动：物理相机 ID 映射、检测、绑定、切换
- `hardware/camera/RingBufferRecorder.kt` — 新增 `forceSurfaceInput` 构造参数
- `domain/PreRecordManager.kt` — `createSurfaceEncoder()` 传递 `forceSurfaceInput`
- `domain/VoiceTriggerRecorder.kt` — `enterStandby()` 支持物理相机 Surface 模式
- `viewmodel/CameraViewModel.kt` — 暴露 `isPhysicalCameraMode` StateFlow
- `ui/screen/MainScreen.kt` — 物理相机模式下禁用缩放手势

**改动内容：**

CameraController:
- 新增 `physicalCameraIds` 映射表（`ULTRA_WIDE → "3"`, `TELEPHOTO → "4"`），从 Camera2 物理子相机检测时记录
- `validateLensesAgainstCameraX()` 中，物理相机有映射时跳过验证（不删除）
- 新增 `bindPhysicalCamera(cameraId, previewView, fps, encoderSurface?)` 方法：
  复用已有 Camera2 基础设施直接打开物理相机，idle 模式只传 preview，standby 模式传 preview + encoder
- 新增 `stopPhysicalCamera()` 停止物理相机会话
- `switchLens()` 中 ULTRA_WIDE/TELEPHOTO 走 `bindPhysicalCamera`，WIDE/FRONT 走 CameraX
- `applyZoomDelta()` 在 `isPhysicalCameraMode` 时返回 null（禁用缩放）
- `isPhysicalCameraMode` 标志 + StateFlow 供 UI/ViewModel 观测

RingBufferRecorder / PreRecordManager:
- `forceSurfaceInput` 参数强制使用 Surface 输入（物理相机无 ImageAnalysis，不能 ByteBuffer）

VoiceTriggerRecorder:
- `needsSurfaceMode` 条件增加 `|| isPhysicalCamera`
- `encoderSurfaceReady` 回调中区分物理相机模式和 4K@60fps Surface 模式
- 热管理监听中物理相机模式也通过 AE FPS Range 控制

### 2026-06-04：修复物理超广角 FOV 裁切问题

**问题背景**：
Camera2 直连物理超广角相机时，预览 FOV 比系统相机窄。
根因：`bindPhysicalCamera()` 未配置 SurfaceTexture 缓冲区大小和 SCALER_CROP_REGION。
Camera2 使用 TextureView 布局尺寸（如 9:20 竖屏）作为输出 Surface，
物理超广角传感器原生比例为 4:3 或 16:9，宽高比不匹配导致 Camera2 居中裁切传感器输出，
水平 FOV 大量损失。另外 `SCALER_CROP_REGION` 未显式设置，部分 HAL 可能使用非全幅默认值。

**修改文件：**
- `hardware/camera/CameraController.kt` — SurfaceTexture 缓冲区配置 + SCALER_CROP_REGION

**改动内容：**

`getPreviewViewSurface()`:
- 新增 `cameraId` 参数，查询 `SENSOR_INFO_ACTIVE_ARRAY_SIZE`
- 调用 `surfaceTexture.setDefaultBufferSize(sensorWidth, sensorHeight)` 匹配传感器宽高比
- 不匹配时 Camera2 会居中裁切传感器输出，导致 FOV 损失

`bindPhysicalCamera()`:
- 传递 `cameraId` 给 `getPreviewViewSurface()` 以配置 SurfaceTexture
- 显式设置 `SCALER_CROP_REGION` 为传感器 `activeArray` 全幅，确保最大 FOV
- 添加诊断日志：传感器 activeArray、preCorrectionArray、physicalSize、focalLengths

`bindPreviewWithSurface()` (4K@60fps):
- 同步修复：传递 `cameraId` 给 `getPreviewViewSurface()`

### 2026-06-04：改用逻辑相机 + CONTROL_ZOOM_RATIO 实现超广角（第二轮修复）

**问题背景**：
物理相机直连方案（Camera2 openCamera("3")）的 FOV 仍然不如系统相机。
分析：AOSP 文档规定"HAL 可将物理相机流裁切到与逻辑相机 FOV 对齐"，
第三方 App 直连物理相机时 HAL 会做 FOV 裁切，而系统相机走的是逻辑相机 + CONTROL_ZOOM_RATIO 路径。

**修改文件：**
- `hardware/camera/CameraController.kt` — 新增逻辑相机缩放方案

**改动内容：**

`switchLens()`:
- ULTRA_WIDE 切换策略改为三级优先：
  1. 逻辑相机 + CONTROL_ZOOM_RATIO（与系统相机一致）
  2. 物理相机直连（降级兜底）
  3. 失败返回 null
- 新增 `resolveTargetZoomRatio()` 解析目标缩放值

新增 `bindLogicalCameraWithZoom()`:
- Camera2 打开逻辑后置相机（cameraIdList 中的 camera 0）
- 设置 `CONTROL_ZOOM_RATIO = minZoomRatio`（如 0.6x）
- HAL 内部切换到超广角物理相机（与系统相机相同路径）
- SCALER_CROP_REGION 保持全幅

新增 `resolveLogicalBackCameraId()`:
- 从 cameraIdList 中找 LENS_FACING_BACK 的逻辑相机 ID

### 2026-06-04：CameraX → Camera2 完整迁移

**问题背景**：
CameraX 在选择 Preview + ImageAnalysis 双 Use Case 的传感器配置时会裁切 FOV，
导致 1x 预览比系统相机和第三方 App（纯 Camera2）窄。通过反编译第三方相机 App 确认，
纯 Camera2 + ImageReader 方案的 FOV 与系统相机一致。

**修改文件：**
- `hardware/camera/CameraController.kt` — 核心重写：移除全部 CameraX 代码，统一 Camera2 API
- `hardware/camera/CameraFramePipeline.kt` — 接口从 `ImageAnalysis.Analyzer` 改为 `ImageReader.OnImageAvailableListener`
- `hardware/camera/CameraLifecycleOwner.kt` — 删除（Camera2 不需要 LifecycleOwner 代理）
- `ui/component/CameraPreview.kt` — `PreviewView` 替换为原生 `TextureView`
- `ui/screen/MainScreen.kt` — `onBindCamera` 回调签名适配
- `viewmodel/CameraViewModel.kt` — 移除 `CameraLifecycleOwner` 依赖，`bindCamera()` 改用 `TextureView`
- `domain/VoiceTriggerRecorder.kt` — `PreviewView` 引用改为 `TextureView`
- `di/AppContainer.kt` — 移除 `CameraLifecycleOwner` 实例
- `gradle/libs.versions.toml` — 移除 CameraX 版本和库定义 + `kotlinx-coroutines-guava`
- `app/build.gradle.kts` — 移除 CameraX 和 coroutines-guava 依赖

**CameraController 核心改动：**
- 移除：`ProcessCameraProvider`, `CameraSelector`, `Preview`, `ImageAnalysis`, `Camera2Interop`, `Camera2CameraInfo`, `analyzerExecutor`, `boundCamera`, `lastLifecycleOwner`
- 新增：`imageReader: ImageReader?` — Camera2 帧数据输出
- `initialize()` — 移除 `ProcessCameraProvider.getInstance().await()`，纯 Camera2 镜头检测
- `bindPreview()` — 核心重写：Camera2 `openCamera()` + `ImageReader` + `SurfaceTexture` + `CaptureSession`
- `resolveCameraId(lens)` — 替代 `resolveCameraSelector()`，直接映射 CameraLens → camera ID 字符串
- `selectBestResolution(cameraId, w, h)` — 从 `StreamConfigurationMap.getOutputSizes(YUV_420_888)` 选最佳分辨率
- `initZoomFromCameraCharacteristics(cameraId)` — 替代 `initZoomFromCamera(camera)`，从 `CONTROL_ZOOM_RATIO_RANGE` 读取
- `applyZoomDelta()` — 改用 `CONTROL_ZOOM_RATIO` + 重建 `CaptureRequest`
- `rebuildCaptureRequest()` — 通用方法，统一缩放/EIS/FPS 的请求重建逻辑
- `resolveActualFpsFromCameraId()` — 替代 `resolveActualFps(cameraSelector)`，不依赖 CameraX
- 删除：`resolveCameraSelector()`, `resolveCamera2Id()`, `validateLensesAgainstCameraX()`, `getPreviewViewSurface()`

**CameraFramePipeline 改动：**
- `analyze(image: ImageProxy)` → `onImageAvailable(reader: ImageReader)`
- `reader.acquireLatestImage()` 直接返回 `android.media.Image`，无需 `image.image` 解包
- `image.timestamp / 1000` 替代 `image.imageInfo.timestamp / 1000`

**统一后的架构：**
- 所有模式共享 Camera2 基础设施（`openCamera2Device`, `createCaptureSession`）
- 标准/缩放模式：SurfaceTexture + ImageReader
- 4K@60fps Surface 模式：SurfaceTexture + Encoder InputSurface
- 超广角/长焦：逻辑相机 + CONTROL_ZOOM_RATIO（优先）或物理相机直连（降级）

### 2026-06-04：修复 Camera2 预览画面变形、FOV、帧率与画质问题

**问题背景**：
CameraX → Camera2 迁移后存在多个问题：
1. **画面左右压缩变形**：传感器 4:3 输出在竖屏 TextureView 上被拉伸
2. **FOV 比系统相机小**：中间尝试过 SCALER_CROP_REGION 居中裁剪导致传感器面积损失
3. **60fps 预览 FOV 比 30fps 小**：尝试过使预览 fps=30 固定规避裁切，但用户需要帧率跟随
4. **画面卡顿**：最大传感器分辨率同时输出到预览 + ImageReader，ISP 带宽不足
5. **画质模糊**：预览分辨率过低（~2.7MP）在 3.2MP 屏幕上欠采样

**修改文件：**
- `hardware/camera/CameraController.kt` — 核心修复
- `viewmodel/CameraViewModel.kt` — 预览 fps 跟随 profile
- `data/SettingsRepository.kt` — EIS 默认关闭

**最终方案：**

1. **分辨率分离** — 预览和捕获用不同分辨率：
   - 新增 `selectMaxSensorResolution(cameraId)` — 传感器全幅最大分辨率，给 ImageReader（编码画质）
   - 新增 `selectPreviewResolution(cameraId, sensorAspect)` — ~5MP 中等分辨率，给 SurfaceTexture（流畅预览）
   - 两者与 activeArray 同宽高比，配合全幅 SCALER_CROP_REGION，HAL 不拉伸

2. **SCALER_CROP_REGION = 全幅 activeArray** — 传感器全幅读出，最大 FOV（不裁剪）

3. **TextureView 变换矩阵** — 新增 `applyPreviewTransform()`：
   - 参考 Google Camera2Basic 示例，交换缓冲区宽高 + `setRectToRect(FILL)`
   - 根据 `SENSOR_ORIENTATION` 和屏幕旋转处理方向
   - 竖屏效果：宽撑满 + 上下居中（完整 FOV 显示）

4. **预览 AE_TARGET_FPS_RANGE 不设置**（null）：
   - `resolveActualFpsFromCameraId` 始终返回 `Pair(bestFps, null)`
   - HAL 用默认 FPS 范围（如 [15,60]），光线充足时自然跑到高帧率
   - **不设置即不触发 HAL 传感器模式切换，全幅 FOV 不变**
   - 30fps/60fps 切换：FOV 一致，帧率由 HAL 默认 + 光线条件决定

5. **EIS 默认关闭** — CameraController + SettingsRepository 默认 false
   - EIS 开启会裁切传感器 ~10-15% 作为防抖余量，显著减小 FOV

6. **rebuildCaptureRequest** — 使用保存的 `currentCropRegion` 而非重新计算

7. **rebindWithProfile 跳过优化** — 参数未变时不重建会话

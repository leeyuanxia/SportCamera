# 4K@60fps Surface 模式 — 架构设计文档

> **状态**: 已实现（2026-06-03）
> **相关文档**: `docs/video-pipeline.md`、`docs/performance-and-power.md`、`CLAUDE.md`

---

## 一、问题背景

### 1.1 为什么需要 Surface 模式？

项目默认使用 CameraX **ImageAnalysis** 用例获取帧数据：

```
相机传感器 → ISP 输出 YUV_420_888 → imageToNv12Direct() → 编码器 ByteBuffer
                ↑
          4K 分辨率下 ISP YUV 输出带宽瓶颈（~50fps 上限）
```

ImageAnalysis 的 YUV_420_888 路径在 4K 分辨率下受 ISP 带宽限制，实测最高约 50fps，无法达到真正的 60fps。这是硬件层面的瓶颈，软件优化无法解决。

### 1.2 解决思路

绕过 ISP 的 YUV 输出，让相机硬件直接输出到编码器的 Input Surface：

```
相机传感器 → ISP → 编码器 InputSurface（零拷贝）→ MediaCodec 硬件编码 → drain → 环形缓冲
```

相机通过 Camera2 API 直接将画面写入编码器 InputSurface，完全跳过 YUV_420_888 → ByteArray → ByteBuffer 的数据搬运路径。由硬件编码器直接消费相机的原始输出，无 ISP YUV 带宽瓶颈。

---

## 二、双路径架构

### 2.1 路径选择

```
分辨率判断: width >= 3840 && fps > 30 (即 UHD_4K_60)?
   │
   ├── YES → Camera2 Surface 模式（零拷贝）
   │          相机 → 编码器 InputSurface（硬件零拷贝）
   │          CameraFramePipeline.setSurfaceMode(true) → analyze() 跳过编码
   │          帧率控制: Camera2 CONTROL_AE_TARGET_FPS_RANGE
   │
   └── NO  → CameraX ImageAnalysis 模式
             │
             ├── width >= 3840 (4K@30fps): feedFrameDirect 零拷贝路径
             │   YUV 直接写入编码器 ByteBuffer，省去 ~12MB ByteArray 中转
             │
             └── 其他分辨率: 普通路径
                 YUV → NV12 ByteArray → 编码器 ByteBuffer
                 帧率控制: skipPattern 跳帧
```

### 2.2 判断条件

```kotlin
// RingBufferRecorder.kt
val useSurfaceInput: Boolean
    get() = width >= 3840 && fps > 30
```

当前仅 `UHD_4K_60` (3840×2160, 60fps) 触发 Surface 模式。4K@30fps 走 `feedFrameDirect` 零拷贝路径（不经过 ByteArray 中转，但仍通过 ByteBuffer 输入编码器）。

---

## 三、组件设计

### 3.1 RingBufferRecorder — 双输入模式

RingBufferRecorder 是预录核心组件，同时支持两种输入模式：

| 模式 | 触发条件 | 输入方式 | 编码器配置 |
|------|---------|---------|-----------|
| ByteBuffer | 默认 | `feedFrame(NV12)` → `dequeueInputBuffer`/`queueInputBuffer` | `COLOR_FormatYUV420Flexible` |
| ByteBuffer 零拷贝 | `width >= 3840 && fps <= 30` | `feedFrameDirect(Image)` → YUV 直写编码器 ByteBuffer | `COLOR_FormatYUV420Flexible` |
| Surface | `width >= 3840 && fps > 30` | Camera2 → `encoder.inputSurface`（零拷贝） | `COLOR_FormatSurface` |

**关键字段**（全部 `@Volatile`）：

```kotlin
@Volatile private var encoder: MediaCodec? = null       // 编码器实例
@Volatile private var inputSurface: Surface? = null      // Surface 模式输入 Surface
@Volatile private var isRunning: Boolean = false         // 编码器运行状态
@Volatile private var isPrepared: Boolean = false        // 编码器准备状态
@Volatile var csd0Data: ByteArray? = null                // SPS（输出格式变更时提取）
@Volatile var csd1Data: ByteArray? = null                // PPS（输出格式变更时提取）
```

**方法概览**：

| 方法 | 模式 | 说明 |
|------|------|------|
| `prepare()` | ByteBuffer | 配置 YUV420 输入编码器 |
| `prepareWithSurface(): Surface` | Surface | 配置 Surface 输入编码器，返回 inputSurface |
| `start()` | 通用 | 启动编码器 + 设置 isRunning |
| `feedFrame(nv12, ts, w, h)` | ByteBuffer | 送入 NV12 数据 |
| `feedFrameDirect(image, ts, w, h)` | ByteBuffer 零拷贝 | 4K YUV 直写编码器缓冲区 |
| `drainEncoder()` | 通用 | IO 线程 drain 输出帧到环形缓冲 |
| `stop()` | 通用 | 停止编码器（Surface 模式释放 inputSurface 触 EOS） |
| `dumpRecentFrames(ms)` | 通用 | 获取最近 N 毫秒的编码帧 |

**Surface 模式 EOS 处理**：

```kotlin
fun stop() {
    isRunning = false
    // ByteBuffer 模式：queueInputBuffer + BUFFER_FLAG_END_OF_STREAM（重试循环）
    // Surface 模式：释放 inputSurface 自动触发 EOS
    inputSurface?.release()
    inputSurface = null
    encoder?.stop()
    encoder?.release()
    encoder = null
}
```

**Level 自动选择**：

```kotlin
val mbPerSec = (width / 16) * (height / 16) * fps
val avcLevel = when {
    mbPerSec > 1_000_000 -> AVCLevel52  // 4K@60fps（MaxMBps=2,073,600 ≥ 1,944,000）
    else -> AVCLevel4                   // 其他档位（1080p 以下足够）
}
```

### 3.2 CameraController — Camera2 会话管理

CameraController 同时管理 CameraX（非 4K）和 Camera2（4K@60fps）两套相机管线。

**Camera2 会话生命周期**：

```
bindPreviewWithSurface(previewView, lens, fps, encoderSurface)
  │
  ├── ① 释放 CameraX (provider.unbindAll())
  ├── ② 启动 Camera2 HandlerThread ("Camera2Session")
  ├── ③ 获取 cameraId + CameraManager
  ├── ④ 获取 PreviewView 内部 TextureView Surface (COMPATIBLE 模式)
  ├── ⑤ resolveSurfaceFpsRange(cameraId, fps) → 解析硬件支持的 FPS Range
  ├── ⑥ openCamera2Device() → CameraDevice
  ├── ⑦ createCaptureSession() → CameraCaptureSession（双 Surface: 预览 + 编码器）
  ├── ⑧ 构建 CaptureRequest (TEMPLATE_RECORD) + FPS Range + AF/AE
  └── ⑨ session.setRepeatingRequest() + isSurfaceMode = true

stopCamera2Session()
  ├── stopRepeating() → close() session
  ├── close() device
  └── quitSafely() HandlerThread
```

**关键方法**：

| 方法 | 说明 |
|------|------|
| `bindPreviewWithSurface()` | 创建 Camera2 会话，绑定 previewSurface + encoderSurface |
| `stopCamera2Session()` | 停止 Camera2 会话，释放资源 |
| `updateSurfaceFps(fps)` | 热管理降频：重建 CaptureRequest 更新 AE FPS Range |
| `resolveSurfaceFpsRange(cameraId, fps)` | 从硬件能力解析最佳 FPS Range（精确匹配或包含区间） |
| `getPreviewViewSurface(previewView)` | 从 PreviewView 的 TextureView 提取 Surface |

**FPS Range 解析策略**：

```kotlin
// 策略：优先精确匹配 [fps, fps]
// 其次找包含目标值的区间 [lower, upper] where lower <= fps <= upper
// 都没找到时使用硬件支持的最大区间
```

### 3.3 PreRecordManager — 编码器创建编排

PreRecordManager 负责编码器的生命周期管理，自动选择 Surface/ByteBuffer 模式：

```kotlin
private fun createBuffer(w: Int, h: Int, fps: Int, bitrateBps: Int) {
    val recorder = RingBufferRecorder(maxDurationSec, w, h, fps, bitrateBps)

    if (recorder.useSurfaceInput) {
        try {
            val surface = recorder.prepareWithSurface()
            recorder.start()
            drainStarted = true
            ringBuffer = recorder
            encoderSurfaceReady?.invoke(surface)  // 通知 VoiceTriggerRecorder 绑定 Camera2
        } catch (e: Exception) {
            // 自动降级：Surface 模式失败 → ByteBuffer 模式
            DebugLog.w(TAG, "Surface 模式失败，降级到 ByteBuffer: ${e.message}")
            recorder.prepare()
            recorder.start()
            drainStarted = true
            ringBuffer = recorder
        }
    } else {
        recorder.prepare()  // ByteBuffer 模式
        recorder.start()
        drainStarted = true
        ringBuffer = recorder
    }
}
```

**Surface 模式专用入口**：

`createSurfaceEncoder()` 方法专为进入待机时的 Surface 模式提供。由于 Surface 模式下相机帧不经过 `CameraFramePipeline.analyze()`，无法通过 `feedFrame → ensureEngine → createBuffer` 路径触发编码器创建。此方法使用用户 profile 的分辨率参数直接创建编码器。

### 3.4 CameraFramePipeline — Surface 模式短路

```kotlin
@Volatile
private var surfaceMode: Boolean = false

fun setSurfaceMode(enabled: Boolean) {
    surfaceMode = enabled
}

override fun analyze(image: ImageProxy) {
    if (surfaceMode) {
        // Surface 模式下相机帧直接进入编码器 Surface
        // analyze() 仅做帧计数和调试日志，不做 YUV 转换和编码
        frameCount++
        if (frameCount % 150 == 0) logFrameMilestone()
        image.close()
        return
    }
    // 正常 YUV → NV12 → feedFrame 路径 ...
}
```

### 3.5 VoiceTriggerRecorder — Surface 模式集成

VoiceTriggerRecorder 是顶层编排器，负责：

- **进入待机时**：检测 `useSurfaceInput`，走 Surface 分支
  - Surface 分支：`preRecordManager.createSurfaceEncoder()` → 通过 `encoderSurfaceReady` 回调获取 Surface → `cameraController.bindPreviewWithSurface()`
  - ByteBuffer 分支：正常 CameraX 绑定 → `cameraFramePipeline.setSurfaceMode(false)`
- **热管理降频时**：Surface 模式下调用 `cameraController.updateSurfaceFps()` 而非重建编码器
- **唤醒录制时**：Surface 模式需额外处理相机切换（Camera2 → CameraX for 录制）

### 3.6 ThermalThrottler — Surface 模式降频

热管理在 Surface 模式下的行为与 ByteBuffer 模式不同：

| 操作 | ByteBuffer 模式 | Surface 模式 |
|------|----------------|--------------|
| 降帧率 | 修改 `skipPattern` | `updateSurfaceFps(fps)` 修改 AE FPS Range |
| 降码率 | 重建编码器 | **不重建编码器**（代价太大，需重建 Camera2 会话） |
| 降频响应时间 | ~100ms（编码器重建） | ~30ms（修改 CaptureRequest） |

**Surface 模式热管理流程**：

```
ThermalThrottler 检测热等级变化
  → VoiceTriggerRecorder.onThrottleConfigChanged()
  → if (isSurfaceMode):
      cameraController.updateSurfaceFps(newFps)
      // 注：Surface 模式当前不降码率（重建编码器代价太大）
      // 仅通过降低 FPS 减少编码器负载
```

---

## 四、数据流对比

### 4.1 ByteBuffer 路径（非 4K@60fps）

```
CameraX ImageAnalysis (YUV_420_888)
  → CameraFramePipeline.analyze()
  → 帧率节流 (skipPattern)
  → YUV → NV12 (imageToNv12 / imageToNv12Direct)
  → feedFrame(feedFrameDirect)
  → RingBufferRecorder
  → dequeueInputBuffer → queueInputBuffer → MediaCodec
  → drainEncoder() → EncodedFrame → ConcurrentLinkedDeque
```

### 4.2 Surface 路径（4K@60fps）

```
Camera2 CaptureSession
  ├→ PreviewView Surface (预览)
  └→ Encoder InputSurface (编码)
       → MediaCodec 硬件编码（相机零拷贝输入）
       → drainEncoder() → EncodedFrame → ConcurrentLinkedDeque

CameraFramePipeline.analyze() → surfaceMode 短路（仅帧计数）
```

> **关键差异**：Surface 模式下 `drainEncoder()` 逻辑**完全不变**。
> 两种模式的差异只在输入端（ByteBuffer vs Surface），输出端（drain → 环形缓冲）统一。

---

## 五、PreviewView 集成

### 5.1 为什么需要 COMPATIBLE 模式？

Surface 模式需要从 PreviewView 获取底层的 TextureView Surface 传给 Camera2 API。这要求 PreviewView 使用 `COMPATIBLE` 实现模式（而非 `SURFACE_VIEW`）：

```kotlin
// CameraController.kt
private fun getPreviewViewSurface(previewView: PreviewView): Surface? {
    // COMPATIBLE 模式下 PreviewView 内部使用 TextureView
    val textureView = previewView.getChildAt(0) as? TextureView
    return textureView?.surfaceTexture?.let { Surface(it) }
}
```

`SURFACE_VIEW` 模式下 Surface 由系统管理，无法直接访问，因此 Surface 模式强制要求 COMPATIBLE 模式。

### 5.2 PreviewView 引用传递链

```
MainScreen (Compose)
  → AndroidView(factory = { PreviewView(context) })
  → CameraPreview (设置 implementationMode = COMPATIBLE)
  → CameraViewModel.bindPreview(previewView)
  → CameraController.bindPreview / bindPreviewWithSurface(previewView)
```

---

## 六、降级策略

### 6.1 自动降级

Camera2 绑定失败时的降级流程：

```
createBuffer() 判断 useSurfaceInput = true
  → recorder.prepareWithSurface()  ← 可能抛出异常
  → try 块内：成功 → encoderSurfaceReady(surface)
  → catch 块内：降级
      recorder.prepare()          ← ByteBuffer 模式
      DebugLog.w("Surface 模式失败，降级到 ByteBuffer: ${e.message}")
      ringBuffer = recorder       ← 安全发布
```

降级后的行为：
- 分辨率仍为 4K，但帧率受 ISP YUV 带宽限制（~50fps）
- 用户无感知（视频正常保存，仅帧率略低）
- 日志中可见降级原因

### 6.2 降级触发条件

| 场景 | 处理 |
|------|------|
| 设备不支持多 Surface 输出 | `createCaptureSession` 失败 → catch 降级 |
| FPS Range 不支持 60fps | `resolveSurfaceFpsRange` 返回最佳可用范围 |
| 编码器不支持 Surface 输入 | `createInputSurface()` 抛异常 → catch 降级 |
| PreviewView 未就绪 | `getPreviewViewSurface` 返回 null → 抛异常降级 |
| Camera2 设备忙 | `openCamera` 超时 → catch 降级 |

---

## 七、实现要点与教训

### 7.1 Camera2 和 CameraX 互斥

Camera2 和 CameraX 不能同时持有同一相机设备。进入 Surface 模式前必须先 `provider.unbindAll()`；退出 Surface 模式后需重新绑定 CameraX。

### 7.2 Surface 模式下不调用 feedFrame

Surface 模式下相机帧由硬件直接写入编码器，`RingBufferRecorder.feedFrame()` 和 `feedFrameDirect()` 不会被调用。`drainEncoder()` 从编码器输出端读取已编码帧，逻辑完全不变。

### 7.3 热管理不重建编码器

ByteBuffer 模式下热管理降频会重建编码器（~100ms）。Surface 模式下重建编码器还需要重建 Camera2 CaptureSession（~200-300ms），代价太大。因此 Surface 模式仅通过修改 `CONTROL_AE_TARGET_FPS_RANGE` 控制帧率，不重建编码器。

### 7.4 编码器创建的安全发布模式

```kotlin
// ✅ 正确：先在局部变量完成所有初始化，最后原子赋值给 volatile 字段
val recorder = RingBufferRecorder(...)
recorder.prepare()
recorder.start()
ringBuffer = recorder  // 安全发布

// ❌ 错误：先赋值再初始化，drainLoop 可能在初始化完成前就读取到非 null 的 ringBuffer
ringBuffer = recorder
recorder.prepare()  // drainLoop 可能已看到非 null ringBuffer，drainEncoder 读 encoder=null
recorder.start()
```

### 7.5 @Volatile 规则（跨线程可见性）

Surface 模式引入了 Camera2 HandlerThread 作为新线程，加上已有的 FrameAnalyzer、Main、IO 线程，跨线程字段必须标记 `@Volatile`：

| 字段 | 写入线程 | 读取线程 |
|------|---------|---------|
| `RingBufferRecorder.encoder` | FrameAnalyzer | IO (drainEncoder) |
| `RingBufferRecorder.inputSurface` | FrameAnalyzer | IO (drainEncoder) |
| `CameraController.isSurfaceMode` | Camera2Thread | Main |
| `PreRecordManager.ringBuffer` | FrameAnalyzer | Main (drainLoop) |

---

## 八、已知限制

1. **Surface 模式仅支持 4K@60fps**：其他分辨率/帧率组合走 ByteBuffer 路径，受 ISP 带宽限制
2. **热管理不降码率**：Surface 模式下降码率需要重建编码器，暂不实施，仅降帧率
3. **PreviewView 依赖 COMPATIBLE 模式**：`SURFACE_VIEW` 模式不兼容，需在初始化时检查
4. **录制切换开销**：从 Surface 待机切换到 ByteBuffer 录制需要重建相机管线（Camera2 → CameraX），有 ~200ms 的帧丢失窗口
5. **部分设备不支持**：低端设备的 Camera2 硬件层可能不支持 `[60, 60]` FPS Range 或多 Surface 输出，需依赖降级策略

---

## 九、故障排查

详见 `docs/performance-and-power.md` 第七节「故障排查 → 4K@60fps Surface 模式问题」。

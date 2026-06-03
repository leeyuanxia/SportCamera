# 4K@60fps 真正 60fps 方案

## 问题根因

当前项目使用 CameraX **ImageAnalysis** 用例获取帧数据：

```
相机传感器 → ISP 输出 YUV_420_888 → imageToNv12Direct() → 编码器 ByteBuffer
                ↑
          4K 分辨率下 ISP 带宽瓶颈 (~50fps 上限)
```

ImageAnalysis 的 YUV_420_888 输出路径在 4K 分辨率下受 ISP 带宽限制，
无法达到 60fps。这不是软件优化能解决的——瓶颈在硬件 YUV 输出带宽。

## 目标方案：Surface 输入模式

```
相机传感器 → ISP → 直接输出到编码器 Surface（零拷贝）
                        ↓
                  MediaCodec 硬件编码
                        ↓
                  drain → 环形缓冲区
```

相机通过 Camera2 API 直接将画面输出到编码器的 InputSurface，
完全跳过 YUV_420_888 → ByteArray → ByteBuffer 的数据搬运，
由硬件编码器直接消费相机的原始输出，无 ISP 带宽瓶颈。

---

## 架构变更概览

### 当前架构（需要改造的部分）

```
CameraFramePipeline (ImageAnalysis.Analyzer)
  ↓ analyze(ImageProxy)
  ↓ YUV → NV12 转换
  ↓ feedFrame / feedFrameDirect
PreRecordManager
  ↓ ringBuffer.feedFrame()
RingBufferRecorder
  ↓ ByteBuffer 输入
MediaCodec encoder
```

### 目标架构

```
Camera2 API
  ↓ 创建 CameraCaptureSession
  ↓ 输出目标包含 encoder.inputSurface
RingBufferRecorder (Surface 模式)
  ↓ MediaCodec.createInputSurface()
  ↓ 编码器直接消费相机输出
  ↓ drainEncoder() → 环形缓冲区（不变）

CameraFramePipeline (保留，但仅用于低分辨率 KWS 帧分析)
  ↓ ImageAnalysis 低分辨率运行
  ↓ 仅用于帧率节流、帧计数、调试日志
```

---

## 关键改动清单

### 1. RingBufferRecorder — 新增 Surface 输入模式

```kotlin
class RingBufferRecorder(...) : FrameConsumer {

    @Volatile
    private var encoder: MediaCodec? = null

    /** 编码器的输入 Surface（Surface 模式下使用） */
    @Volatile
    private var inputSurface: Surface? = null

    /** 是否使用 Surface 输入模式（4K@60fps 必须使用） */
    val useSurfaceInput: Boolean
        get() = width >= 3840 && fps > 30

    /**
     * 准备编码器 — Surface 输入模式
     *
     * 与 ByteBuffer 模式的区别：
     * - configure() 时传入 surface 参数（非 null）
     * - 通过 createInputSurface() 获取输入 Surface
     * - 不需要 feedFrame()，相机直接输出到此 Surface
     * - drainEncoder() 逻辑完全不变
     */
    fun prepareWithSurface(): Surface {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            val mbPerSec = (width / 16) * (height / 16) * fps
            val level = when {
                mbPerSec > 1_000_000 -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
                else -> MediaCodecInfo.CodecProfileLevel.AVCLevel4
            }
            setInteger(MediaFormat.KEY_LEVEL, level)
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // 关键：创建 InputSurface，相机直接输出到此 Surface
        val surface = codec.createInputSurface()
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        
        this.encoder = codec
        this.inputSurface = surface
        isPrepared = true
        
        return surface  // 返回给 CameraController 用于绑定相机
    }

    // start()、drainEncoder()、dumpRecentFrames() 等方法完全不变
    // feedFrame() 在 Surface 模式下不会被调用（但保留接口兼容）
}
```

### 2. CameraController — 双模式绑定

```kotlin
/**
 * bindPreview 增强：4K@60fps 使用 Surface 编码，其他用 ByteBuffer
 */
suspend fun bindPreview(..., encoderSurface: Surface? = null) {
    // ... 现有的 Preview + ImageAnalysis 绑定逻辑 ...

    if (encoderSurface != null) {
        // 4K Surface 模式：额外添加一个 Surface 输出到编码器
        // 方式：通过 Camera2Interop 设置 High-speed recording session
        // 或者使用 Camera2 API 直接创建 session
        bindWithEncoderSurface(encoderSurface, preview, imageAnalysis, cameraSelector)
    }
}
```

### 3. PreRecordManager — 双模式创建

```kotlin
/**
 * 创建编码器（自动选择 Surface/ByteBuffer 模式）
 */
private fun createBuffer(w: Int, h: Int, fps: Int, bitrateBps: Int) {
    val recorder = RingBufferRecorder(
        maxDurationSec = currentDuration.seconds,
        width = w, height = h, fps = fps, bitrateBps = bitrateBps,
    )

    if (recorder.useSurfaceInput) {
        // Surface 模式：获取 encoder Surface 并通知 CameraController
        val surface = recorder.prepareWithSurface()
        encoderSurfaceReady?.invoke(surface)  // 回调通知
    } else {
        // ByteBuffer 模式：现有逻辑不变
        recorder.prepare()
    }

    recorder.start()
    drainStarted = true
    ringBufferGeneration++
    ringBuffer = recorder
}
```

### 4. Camera2 直接会话（绕过 CameraX ImageAnalysis）

这是最核心的改动。CameraX ImageAnalysis 无法直接输出到自定义 Surface，
需要通过 Camera2 API 创建一个包含编码器 Surface 的 CaptureSession：

```kotlin
/**
 * 创建 Camera2 录制会话 — 相机直接输出到编码器 Surface
 */
private fun bindWithEncoderSurface(
    encoderSurface: Surface,
    preview: Preview,
    imageAnalysis: ImageAnalysis,
    cameraSelector: CameraSelector,
) {
    // 获取 Camera2 的 CameraDevice
    val cameraId = Camera2CameraInfo.from(
        cameraProvider!!.availableCameraInfos.first {
            cameraSelector.filter(listOf(it)).isNotEmpty()
        }
    ).cameraId

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    
    // 通过 Camera2Interop 获取 CameraDevice
    // 创建 CaptureSession 包含：
    //   - preview.surfaceProvider 的 Surface（预览显示）
    //   - encoderSurface（编码器输入）
    //   - imageAnalysis 的 Surface（低分辨率帧分析，可选）
    
    // 设置 AE FPS Range 为 [30, 60] 或 [fps, fps]
    captureRequestBuilder.set(
        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
        Range(actualFps, actualFps)
    )
}
```

---

## 实现步骤（按顺序）

| 步骤 | 内容 | 工作量 |
|------|------|--------|
| 1 | RingBufferRecorder 新增 `prepareWithSurface()` + `createInputSurface()` | 小 |
| 2 | RingBufferRecorder.start() / drainEncoder() 兼容 Surface 模式（基本不变） | 小 |
| 3 | CameraController 新增 Camera2 高速录制会话创建方法 | **中（核心）** |
| 4 | CameraController 绑定 encoder Surface + Preview + ImageAnalysis 三路输出 | 中 |
| 5 | PreRecordManager 自动选择 Surface/ByteBuffer 模式 | 小 |
| 6 | 帧率控制改为通过 AE FPS Range（不再依赖帧管线节流） | 小 |
| 7 | 相机切换/重新绑定兼容 Surface 模式 | 小 |
| 8 | 热管理降频：Surface 模式下通过修改 AE FPS Range 实现 | 小 |

---

## 关键注意点

### 1. Surface 模式下帧率控制

ByteBuffer 模式通过 `skipPattern` 在 YUV 转换前跳帧。
Surface 模式下无法跳帧（相机直出编码器），帧率控制只能通过：
- `CONTROL_AE_TARGET_FPS_RANGE`：设置目标帧率
- 热管理降频：修改 FPS Range（如从 [60,60] 改为 [30,30]）

```kotlin
// 热管理降频 — Surface 模式
fun updateFpsRange(fps: Int) {
    captureRequestBuilder.set(
        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
        Range(fps, fps)
    )
    captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null)
}
```

### 2. ImageAnalysis 保留用于低分辨率帧分析

Surface 模式下，ImageAnalysis 仍然运行（低分辨率），用于：
- 帧计数和调试日志
- 未来可能的帧分析功能
- 不再作为编码数据源

### 3. 相机会话重建时机

Surface 模式下，编码器重建（热管理触发）需要：
1. 停止旧编码器 → 旧 inputSurface 失效
2. 创建新编码器 → 获取新 inputSurface
3. 重建 Camera2 CaptureSession → 使用新 Surface
4. 会话重建期间会有短暂的帧丢失（不可避免）

### 4. MediaCodec Surface 模式不调 feedFrame

Surface 模式下，`RingBufferRecorder.feedFrame()` 和 `feedFrameDirect()` 
不会被调用。帧数据由相机硬件直接写入编码器。`drainEncoder()` 从编码器输出端
读取已编码帧，逻辑完全不变。

### 5. 停止编码器需要发 EOS 信号

Surface 模式下停止编码器：
```kotlin
fun stop() {
    isRunning = false
    inputSurface?.release()  // 释放 Surface 触发 EOS
    inputSurface = null
    encoder?.stop()
    encoder?.release()
    encoder = null
}
```

释放 inputSurface 会自动向编码器发送 `BUFFER_FLAG_END_OF_STREAM`，
drainEncoder 检测到 EOS 后退出循环。

---

## 风险和降级策略

| 风险 | 应对 |
|------|------|
| 部分 Camera2 实现不支持多 Surface 输出 | 检测 `SCALER_STREAM_CONFIGURATION_MAP`，不支持时退回 ByteBuffer 模式 |
| Surface 模式下无法做帧率节流 | 热管理改为调整 AE FPS Range |
| 编码器重建需要重建 Camera2 会话 | 接受短暂帧丢失（~100-200ms）|
| 录像旋转（竖屏）需在 Surface 端处理 | 通过 `MediaMuxer.setOrientationHint()` 或 EGL 旋转 |

## 降级策略

```kotlin
fun createBuffer(w: Int, h: Int, fps: Int, bitrateBps: Int) {
    val useSurface = w >= 3840 && fps > 30
    if (useSurface) {
        try {
            val surface = recorder.prepareWithSurface()
            // 通知 CameraController 绑定 Surface
            bindEncoderSurface(surface)
        } catch (e: Exception) {
            // Surface 模式不支持，退回 ByteBuffer 模式（~50fps）
            Log.w(TAG, "Surface 模式失败，退回 ByteBuffer: ${e.message}")
            recorder.prepare()
        }
    } else {
        recorder.prepare()  // 非 4K 分辨率，ByteBuffer 模式
    }
}
```


# CameraX → Camera2 迁移方案

> **目标**: 将摄像头预览和帧捕获从 CameraX 迁移到纯 Camera2 API，使 FOV 与系统相机和第三方相机 App 一致。

## 一、问题根因

通过反编译第三方相机 App（基于 boofcv 库）确认：

1. **CameraX 的 FOV 比直接 Camera2 窄** — CameraX 内部根据 Preview + ImageAnalysis 两个 Use Case 的分辨率/宽高比选择传感器配置，这个过程不可控
2. **第三方 App 使用纯 Camera2 + ImageReader** — 从 `StreamConfigurationMap.getOutputSizes(YUV_420_888)` 选最大匹配分辨率，传感器使用全幅模式
3. **实测确认**：同一设备上，CameraX 1x 预览比 Camera2 App 1x 预览的 FOV 窄

**关键参考**: boofcv `SimpleCamera2Activity.java` 的做法：
- `StreamConfigurationMap.getOutputSizes(YUV_420_888)` → 选最佳分辨率
- `ImageReader.newInstance(w, h, YUV_420_888, 2)` → 帧捕获
- `SurfaceTexture.setDefaultBufferSize(w, h)` → 预览匹配传感器
- `OutputConfiguration.setPhysicalCameraId()` → 物理相机直连
- `TEMPLATE_PREVIEW` → 模板

## 二、迁移范围

### 直接依赖 CameraX 的文件（必须改）

| 文件 | CameraX 依赖 | 改动内容 |
|------|-------------|---------|
| `hardware/camera/CameraController.kt` | ProcessCameraProvider, Preview, ImageAnalysis, CameraSelector, Camera2Interop, Camera2CameraInfo, PreviewView | 核心重写：bindPreview()、initialize()、resolveCameraSelector()、validateLensesAgainstCameraX()、resolveActualFps()、initZoomFromCamera()、applyZoomDelta() |
| `hardware/camera/CameraFramePipeline.kt` | ImageAnalysis.Analyzer, ImageProxy | 接口替换：从 `ImageAnalysis.Analyzer` 改为 `ImageReader.OnImageAvailableListener` |
| `ui/component/CameraPreview.kt` | PreviewView | 替换为 TextureView 或保留 PreviewView 仅用于显示 |

### 间接依赖（联动修改）

| 文件 | 依赖方式 | 改动内容 |
|------|---------|---------|
| `domain/VoiceTriggerRecorder.kt` | PreviewView 类型引用 | 类型可能变化 |
| `viewmodel/CameraViewModel.kt` | PreviewView 类型引用 | 类型可能变化 |
| `di/AppContainer.kt` | CameraController 实例化 | 构造参数可能变化 |

### 不需要改的文件

| 文件 | 原因 |
|------|------|
| `hardware/camera/RingBufferRecorder.kt` | FrameConsumer 接口不变，编码器逻辑不变 |
| `hardware/camera/ActiveRecorder.kt` | 同上 |
| `hardware/camera/FrameConsumer.kt` | 接口定义不变 |
| `domain/PreRecordManager.kt` | feedFrame/feedFrameDirect 调用不变 |
| `domain/VideoAssembler.kt` | 不涉及相机 |
| `power/*` | 不涉及相机 |
| `hardware/audio/*` | 不涉及相机 |

## 三、目标架构

### 当前架构（CameraX）

```
CameraX bindToLifecycle()
  ├── Preview → PreviewView.surfaceProvider
  └── ImageAnalysis → CameraFramePipeline.analyze(ImageProxy)
        → YuvConverter.imageToNv12()
        → cropAndScaleNv12()
        → FrameConsumer.feedFrame()
```

### 目标架构（Camera2）

```
Camera2 openCamera()
  ├── SurfaceTexture → PreviewView（预览显示）
  └── ImageReader (YUV_420_888) → CameraFramePipeline.onImageAvailable(ImageReader)
        → image.acquireLatestImage()
        → YuvConverter.imageToNv12()
        → cropAndScaleNv12()
        → FrameConsumer.feedFrame()
```

## 四、详细实现步骤

### 步骤 1: CameraFramePipeline — 接口替换

**文件**: `hardware/camera/CameraFramePipeline.kt`

**改动**:
```kotlin
// 当前
class CameraFramePipeline : ImageAnalysis.Analyzer {
    override fun analyze(image: ImageProxy) { ... }
}

// 改为
class CameraFramePipeline : ImageReader.OnImageAvailableListener {
    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            // 原有 analyze() 的逻辑，但直接用 android.media.Image（不再是 ImageProxy）
            processFrame(image, image.timestamp / 1000, image.width, image.height)
        } finally {
            image.close()
        }
    }
}
```

**关键差异**:
- `ImageProxy` → `android.media.Image`（直接 Camera2 的 ImageReader 输出）
- `image.image` 不再需要（ImageReader 直接给 Image，不像 CameraX 的 ImageProxy 包装）
- `image.imageInfo.timestamp` → `image.timestamp`
- `image.close()` 在 finally 中调用（和原来一样）
- 移除 `import androidx.camera.core.ImageAnalysis` 和 `import androidx.camera.core.ImageProxy`

### 步骤 2: CameraController.bindPreview() — 核心重写

**文件**: `hardware/camera/CameraController.kt`

**当前 bindPreview() 的职责**:
1. 获取/初始化 ProcessCameraProvider
2. 构建 Preview.Builder + ImageAnalysis.Builder
3. 通过 Camera2Interop 设置 FPS Range 和 EIS
4. `provider.bindToLifecycle()` 绑定两个 use case

**改为 Camera2 的职责**:
1. 通过 CameraManager.openCamera() 打开相机
2. 从 StreamConfigurationMap 选择最佳分辨率
3. 创建 SurfaceTexture（预览）+ ImageReader（帧捕获）
4. createCaptureSession() 创建会话
5. setRepeatingRequest() 开始预览

**新 bindPreview() 的伪代码**:
```kotlin
suspend fun bindPreview(
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,  // 保留，但只用其内部 TextureView
    lens: CameraLens,
    orientation: RecordOrientation,
    framePipeline: CameraFramePipeline?,
    encoderWidth: Int,
    encoderHeight: Int,
    fps: Int,
) {
    // 1. 释放旧的 Camera2 会话（如果有）
    stopCamera2Session()

    // 2. 解析目标 camera ID
    val cameraId = resolveCameraIdForLens(lens)

    // 3. 查询传感器信息
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val chars = cameraManager.getCameraCharacteristics(cameraId)
    val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

    // 4. 从传感器支持的输出尺寸中选择最佳分辨率
    //    策略：优先选与 PreviewView 宽高比匹配的最大分辨率
    val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
    val selectedSize = selectBestResolution(outputSizes, previewView, encoderWidth, encoderHeight)

    // 5. 创建 HandlerThread
    val thread = HandlerThread("Camera2Preview").apply { start() }
    val handler = Handler(thread.looper)
    camera2Thread = thread
    camera2Handler = handler

    // 6. 创建 ImageReader（帧捕获）
    val imageReader = ImageReader.newInstance(
        selectedSize.width, selectedSize.height,
        ImageFormat.YUV_420_888, 2
    )
    imageReader.setOnImageAvailableListener(framePipeline, handler)

    // 7. 获取预览 Surface
    val textureView = previewView.getChildAt(0) as TextureView
    textureView.surfaceTexture.setDefaultBufferSize(selectedSize.width, selectedSize.height)
    val previewSurface = Surface(textureView.surfaceTexture)

    // 8. 打开相机
    val device = openCamera2Device(cameraManager, cameraId, handler)
    camera2Device = device

    // 9. 创建 CaptureSession
    val surfaces = listOf(previewSurface, imageReader.surface)
    camera2Surfaces = surfaces
    val session = createCaptureSession(device, surfaces, handler)
    camera2Session = session

    // 10. 构建 CaptureRequest
    val fpsRange = resolveSurfaceFpsRange(cameraId, fps)
    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(previewSurface)
        addTarget(imageReader.surface)
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, currentVideoStabilizationMode)
    }
    camera2RequestBuilder = requestBuilder

    session.setRepeatingRequest(requestBuilder.build(), null, handler)
    _currentLens.value = lens

    // 11. 初始化缩放范围
    initZoomFromCameraCharacteristics(chars)
}
```

### 步骤 3: 分辨率选择策略

新增 `selectBestResolution()` 方法：

```kotlin
/**
 * 从传感器支持的输出尺寸中选择最佳分辨率
 *
 * 策略（参考 boofcv SimpleCamera2Activity）：
 * 1. 过滤掉太小的分辨率（< 640x480）
 * 2. 按面积降序排列
 * 3. 优先选与编码器目标宽高比一致的
 * 4. 如果没有匹配宽高比的，选最大分辨率
 */
private fun selectBestResolution(
    outputSizes: Array<Size>,
    previewView: PreviewView,
    encoderWidth: Int,
    encoderHeight: Int,
): Size {
    val targetAspect = encoderWidth.toDouble() / encoderHeight

    // 按面积降序
    val sorted = outputSizes.sortedByDescending { it.width.toLong() * it.height }

    // 优先选与编码器宽高比最接近的最大分辨率
    return sorted.firstOrNull {
        abs(it.width.toDouble() / it.height - targetAspect) < 0.1
    } ?: sorted.first()
}
```

### 步骤 4: 缩放控制改造

**当前**: 通过 CameraX `Camera.cameraInfo.zoomState` 和 `Camera.cameraControl.setZoomRatio()`

**改为**: 直接通过 Camera2 `CaptureRequest.CONTROL_ZOOM_RATIO`

```kotlin
// 缩放范围：从 CameraCharacteristics 读取
private fun initZoomFromCameraCharacteristics(chars: CameraCharacteristics) {
    val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
    if (zoomRange != null) {
        _minZoomRatio.value = zoomRange.lower
        _maxZoomRatio.value = zoomRange.upper
    }
}

// 应用缩放：重建 CaptureRequest
fun applyZoomDelta(delta: Float) {
    val session = camera2Session ?: return
    val device = camera2Device ?: return
    val handler = camera2Handler ?: return

    val newRatio = (_zoomRatio.value * delta)
        .coerceIn(_minZoomRatio.value, _maxZoomRatio.value)
    if (abs(newRatio - _zoomRatio.value) < 0.01f) return

    _zoomRatio.value = newRatio

    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        camera2Surfaces.forEach { addTarget(it) }
        set(CaptureRequest.CONTROL_ZOOM_RATIO, newRatio)
        // 复制其他参数...
        copyFrom(camera2RequestBuilder)
    }
    camera2RequestBuilder = requestBuilder
    session.setRepeatingRequest(requestBuilder.build(), null, handler)
}
```

### 步骤 5: 镜头切换改造

**当前**:
- WIDE/FRONT: CameraX `bindToLifecycle()`
- ULTRA_WIDE/TELEPHOTO: Camera2 `bindPhysicalCamera()` 或 `bindLogicalCameraWithZoom()`

**改为**:
- 全部使用 Camera2
- WIDE: `openCamera("0")` — 逻辑相机 ID
- FRONT: `openCamera("1")` — 前置相机 ID
- ULTRA_WIDE: `openCamera("0")` + `CONTROL_ZOOM_RATIO = minZoomRatio` — 逻辑相机 + 缩放
- TELEPHOTO: `openCamera("0")` + 高缩放比

```kotlin
private fun resolveCameraIdForLens(lens: CameraLens): String {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    return when (lens) {
        CameraLens.WIDE, CameraLens.ULTRA_WIDE, CameraLens.TELEPHOTO -> {
            // 后置逻辑相机（从 cameraIdList 找 LENS_FACING_BACK）
            cameraManager.cameraIdList.first {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        }
        CameraLens.FRONT -> {
            cameraManager.cameraIdList.first {
                cameraManager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        }
    }
}
```

### 步骤 6: initialize() — 镜头检测改造

**当前**: 依赖 `ProcessCameraProvider` 和 `availableCameraInfos`

**改为**: 纯 Camera2 API 检测（当前代码中已有大部分 Camera2 检测逻辑，只需移除 CameraX 依赖部分）

```kotlin
suspend fun initialize() {
    // 不再需要 ProcessCameraProvider.getInstance()
    // 直接使用 CameraManager 进行镜头检测
    if (_availableLenses.value.isEmpty()) {
        detectAvailableLenses()
    }
}

// validateLensesAgainstCameraX() → 删除或简化为纯 Camera2 验证
```

### 步骤 7: 生命周期管理

**当前**: 通过 `CameraLifecycleOwner` 让 CameraX 的 `bindToLifecycle()` 与 Activity 生命周期绑定

**改为**: 手动管理 Camera2 会话的打开/关闭

```kotlin
// CameraViewModel 中：
fun bindCamera(..., lifecycleOwner, ...) {
    // 不再需要 cameraLifecycleOwner.setWrappedOwner()
    cameraController.bindPreview(/* 不再需要 lifecycleOwner 参数 */, previewView, ...)
}

fun startStandby() {
    // 不再需要 cameraLifecycleOwner.enterStandby()
    // Camera2 会话不受 Activity 生命周期影响，无需特殊处理
}

fun stopStandby() {
    // 不再需要 cameraLifecycleOwner.exitStandby()
}
```

**CameraLifecycleOwner.kt**: 可以完全删除，或保留为空壳（不依赖 CameraX）

### 步骤 8: VoiceTriggerRecorder 适配

**改动点**:
1. `enterStandby()` 中：
   - ByteBuffer 模式：framePipeline 已改为 `OnImageAvailableListener`，但连接方式不变（`setEncoder(preRecordManager)`）
   - Surface 模式（4K@60fps）：`bindPreviewWithSurface()` 逻辑基本不变（已经用 Camera2）
   - 物理相机模式：`bindLogicalCameraWithZoom()` 逻辑基本不变

2. `encoderSurfaceReady` 回调中的 CameraController 调用方式可能微调（参数变化）

### 步骤 9: 预览显示

**当前**: `PreviewView`（CameraX 专用 View）

**方案 A（推荐）: 保留 PreviewView，只提取 TextureView**
```kotlin
// PreviewView COMPATIBLE 模式内部就是 TextureView
val textureView = previewView.getChildAt(0) as TextureView
```
- 优点：UI 层改动最小，MainScreen/CameraPreview 不用改
- 缺点：PreviewView 是 CameraX 库的一部分，虽然不调用 CameraX API 但仍依赖库

**方案 B: 替换为原生 TextureView**
```kotlin
// CameraPreview.kt 中：
AndroidView(factory = { context ->
    TextureView(context).apply { ... }
})
```
- 优点：完全去除 CameraX 依赖
- 缺点：需要手动处理 TextureView 的布局、旋转、缩放

**建议先用方案 A**，验证 FOV 修复后再考虑方案 B 去除 CameraX 依赖。

## 五、统一后的架构

迁移后，所有模式都使用 Camera2 API：

| 模式 | 当前实现 | 迁移后 |
|------|---------|--------|
| 标准 1x 预览 | CameraX Preview + ImageAnalysis | Camera2 + ImageReader |
| 超广角 | Camera2 bindPhysicalCamera / bindLogicalCameraWithZoom | Camera2 + CONTROL_ZOOM_RATIO |
| 长焦 | Camera2 bindPhysicalCamera | Camera2 + CONTROL_ZOOM_RATIO |
| 4K@60fps Surface | Camera2 bindPreviewWithSurface | Camera2 bindPreviewWithSurface（不变） |
| 缩放 | CameraX setZoomRatio | Camera2 CONTROL_ZOOM_RATIO |

**统一的 Camera2 基础设施**：
- 所有模式共享 `openCamera2Device()`、`createCaptureSession()`、`CaptureRequest.Builder`
- 区别只在 Surface 列表和 CaptureRequest 参数：
  - 标准/缩放模式：SurfaceTexture + ImageReader
  - 4K@60fps Surface 模式：SurfaceTexture + Encoder InputSurface

## 六、风险和注意事项

### 1. 性能
- CameraX 内部做了很多优化（如自动选择最佳分辨率、处理设备兼容性）
- 迁移到 Camera2 后需要自己处理这些，可能在某些设备上遇到兼容性问题
- 帧率节流逻辑从 CameraX 层移到 ImageReader 回调中，行为可能略有差异

### 2. 兼容性
- Camera2 的 StreamConfigurationMap 在不同设备上行为不同
- 需要充分测试：三星、小米、华为、OPPO、vivo 等主流品牌
- 某些设备的 Camera2 支持有限（LEGACY 设备），可能需要降级到 Camera1

### 3. EIS（视频防抖）
- 当前通过 Camera2Interop 设置 EIS
- 迁移后直接在 CaptureRequest 中设置，更直接
- 但不同设备的 EIS 实现不同，需要测试

### 4. 帧管线
- `CameraFramePipeline.analyze(ImageProxy)` → `onImageAvailable(ImageReader)`
- ImageProxy 和 Image 的 API 略有差异：
  - `ImageProxy.image` → 直接使用 Image
  - `ImageProxy.imageInfo.timestamp` → `Image.timestamp`
  - `ImageProxy.width/height` → `Image.width/height`
  - `ImageProxy.close()` → `Image.close()`
- `feedFrameDirect()` 使用 `Image`（已经是 `android.media.Image`），无需改动

### 5. CameraX 依赖去除
- 迁移后可以完全移除 CameraX 依赖（`build.gradle` 中的 `implementation`）
- 但 `PreviewView` 仍来自 CameraX 库（如果用方案 A）
- 最终目标：用原生 TextureView 替换 PreviewView，完全移除 CameraX

## 七、测试计划

1. **FOV 对比测试**: 在同一设备上，分别打开系统相机、第三方 App、我们的 App，对比 1x、0.6x、2x 的 FOV
2. **录制功能测试**: 确认预录 + 触发 + 合成 + 保存的完整流程正常
3. **镜头切换测试**: WIDE ↔ ULTRA_WIDE ↔ TELEPHOTO ↔ FRONT 切换
4. **缩放测试**: 双指缩放流畅度，0.6x ~ 10.0x 范围
5. **4K@60fps 测试**: Surface 模式录制
6. **热管理测试**: 高温下降频是否正常
7. **多设备测试**: 至少覆盖小米、三星、华为、OPPO

## 八、涉及文件完整清单

```
需要修改的文件：
  app/src/main/java/cn/leeyuanxia/sportcamera/
  ├── hardware/camera/
  │   ├── CameraController.kt          ← 核心重写
  │   └── CameraFramePipeline.kt       ← 接口替换
  ├── domain/
  │   └── VoiceTriggerRecorder.kt      ← 适配新的 bindPreview 参数
  ├── viewmodel/
  │   └── CameraViewModel.kt           ← 移除 CameraLifecycleOwner 依赖
  ├── ui/component/
  │   └── CameraPreview.kt             ← 可能需要调整（看方案 A/B）
  └── di/
      └── AppContainer.kt              ← CameraLifecycleOwner 实例可能移除

可能删除的文件：
  └── hardware/camera/CameraLifecycleOwner.kt  ← 不再需要

需要更新的文档：
  ├── CLAUDE.md                        ← 修改日志
  └── docs/architecture.md             ← 架构更新
```

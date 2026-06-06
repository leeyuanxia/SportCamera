# 运动相机 App — 项目架构文档

## 项目概述

**包名**: `cn.leeyuanxia.sportcamera`
**定位**: 运动相机 App，通过语音唤醒词自动触发录像
**最低版本**: Android 14 (API 34) | **目标版本**: Android 16 (API 36)
**架构**: 单 Activity + Compose UI + 手动 DI 容器 + MVVM
**语言**: Kotlin 100%
**构建**: Gradle 9.3.1 + AGP 9, Camera2 API（全模式统一，含 Surface 零拷贝）

---

## 核心用例

1. 用户点击「待机」→ App 开始预录（环形缓冲持续录制）+ KWS 语音监听
2. 用户说出「开始录像」→ 语音唤醒词被检测到
3. App 自动从环形缓冲截取前半段（预录帧）+ 继续录制后半段
4. 合成前后半段 → 保存 MP4 到相册 → 回到待机

---

## 目录结构

```
app/src/main/java/cn/leeyuanxia/sportcamera/
├── SportCameraApp.kt                    # Application 入口，初始化 DI 容器
├── MainActivity.kt                      # Compose 入口 Activity
│
├── di/
│   └── AppContainer.kt                  # 手动 DI 容器（单例管理所有模块实例）
│
├── domain/                              # 业务逻辑层
│   ├── AppState.kt                      # 应用状态机 (Idle/Standby/Recording/Saving/Error)
│   ├── VoiceTriggerRecorder.kt          # 语音触发录像编排器（核心状态机）
│   ├── PreRecordManager.kt              # 预录管理器（环形缓冲生命周期）
│   ├── VideoAssembler.kt                # 视频合成器（前后半段拼接 + 保存）
│   └── model/
│       ├── CameraLens.kt                # 镜头枚举（标准/广角/超广角）
│       ├── PreRecordDuration.kt          # 预录时长（1~120s，默认10s）
│       ├── RecordConfig.kt              # 录制配置聚合
│       ├── RecordOrientation.kt          # 录制方向（横屏/竖屏）
│       └── ResolutionProfile.kt          # 分辨率档位（720p/1080p30/1080p60/4K30/4K60）
│
├── hardware/                            # 硬件抽象层
│   ├── audio/
│   │   └── KwsManager.kt                # 语音唤醒管理器（sherpa-onnx KWS）
│   ├── camera/
│   │   ├── CameraController.kt          # Camera2 摄像头控制器（纯 Camera2，支持多镜头/缩放/Surface）
│   │   ├── CameraFramePipeline.kt       # 帧管线（ImageReader → 编码器，含 4K 零拷贝 + 实际帧率测量）
│   │   ├── FrameConsumer.kt             # FrameConsumer 接口 + YuvConverter 工具（双线性插值缩放）
│   │   ├── RingBufferRecorder.kt        # 环形缓冲录制器（预录核心，4K 全 Surface，其余 ByteBuffer）
│   │   └── ActiveRecorder.kt           # 高质量活跃录制器（AVC Level 动态选择）
│   └── storage/
│       └── VideoStorageManager.kt       # 视频存储（MediaStore + MediaMuxer）
│
├── power/                               # 省电管理
│   ├── PowerStateManager.kt             # WakeLock + 电池监控
│   └── ThermalThrottler.kt              # 温度自适应降频
│
├── util/                                # 工具类
│   └── DebugLog.kt                      # 日志封装（Debug 输出/Release 静默）
│
├── service/
│   └── CameraForegroundService.kt       # 前台服务（防系统杀）
│
├── data/
│   └── SettingsRepository.kt            # DataStore 设置持久化
│
├── viewmodel/
│   └── CameraViewModel.kt               # ViewModel（UI ↔ 业务逻辑桥梁）
│
└── ui/                                  # Compose UI 层
    ├── screen/MainScreen.kt             # 主界面
    └── component/
        ├── CameraPreview.kt             # 摄像头预览
        ├── ControlBar.kt                # 底部控制栏
        ├── RecordIndicator.kt           # 录制指示器
        └── StatusBar.kt                 # 顶部状态栏
```

---

## 应用状态机

```
         ┌──────────┐
         │   Idle   │ ← 初始状态
         └────┬─────┘
              │ 点击待机
              ▼
         ┌──────────┐
    ┌───►│ Standby  │◄──────────────────┐
    │    └────┬─────┘                    │
    │         │ 检测到唤醒词              │
    │         ▼                          │
    │    ┌──────────┐                    │
    │    │Recording │──── 保存完成 ────┐  │
    │    └────┬─────┘                  │  │
    │         │ 录制结束                │  │
    │         ▼                        │  │
    │    ┌──────────┐                  │  │
    │    │  Saving  │──────────────────┘  │
    │    └──────────┘                     │
    │         │ 异常                       │
    │         ▼                           │
    │    ┌──────────┐  2秒后自动回到Standby │
    │    │  Error   │──────────────────┘
    │    └──────────┘
    │         │ 用户手动停止
    └─────────┘
```

---

## 核心数据流

### 待机模式 (Standby)

#### 非 4K 分辨率（Camera2 ImageReader 路径）

```
┌─────────────────────────────────────────────────────────────────┐
│ Camera2 ImageReader (~30fps, YUV_420_888)                       │
│     │                                                           │
│     ▼                                                           │
│ CameraFramePipeline.onImageAvailable()                          │
│     │ ① 帧率节流: 30fps（与相机输出一致，不跳帧）                    │
│     │ ② imageToNv12(): YUV_420_888 → NV12 (复用缓冲区)           │
│     │ ③ cropAndScaleNv12(): 居中裁剪+缩放到目标分辨率 (复用缓冲区)  │
│     ▼                                                           │
│ PreRecordManager.feedFrame()                                    │
│     │ 首帧时自动创建 RingBufferRecorder                           │
│     ▼                                                           │
│ RingBufferRecorder (内存环形缓冲, ~11.25MB)                       │
│     │ 编码: H.264 High Profile, 30fps, 3Mbps                    │
│     │ 热管理可动态调整: fps ↓ bitrate ↓                           │
│     └── 保留最近 N 秒的关键帧完整数据                               │
└─────────────────────────────────────────────────────────────────┘
```

#### 4K 全系列（Camera2 Surface 路径，零拷贝）

```
┌─────────────────────────────────────────────────────────────────┐
│ Camera2 API (直接控制相机传感器)                                  │
│     │ 创建 CaptureSession:                                       │
│     │   Surface 1 = TextureView (预览)                           │
│     │   Surface 2 = Encoder InputSurface (编码器直入)             │
│     │ AE FPS Range = [60, 60]                                    │
│     ▼                                                           │
│ RingBufferRecorder (Surface 输入模式)                             │
│     │ 编码器通过 createInputSurface() 获取输入 Surface              │
│     │ 相机硬件零拷贝写入 → MediaCodec 硬件编码                     │
│     │ drainEncoder() 从编码器输出端读取已编码帧 → 环形缓冲          │
│     │ 热管理: 修改 AE FPS Range（不重建编码器）                     │
│     └── 保留最近 N 秒的关键帧完整数据                               │
│                                                                 │
│ CameraFramePipeline.setSurfaceMode(true)                        │
│     └── onImageAvailable() 直接跳过（不参与编码数据流）             │
└─────────────────────────────────────────────────────────────────┘
```

#### 语音唤醒（两种模式共用）

```
AudioRecord (16kHz, Mono, PCM16)
    │ 每 100ms 读取 1600 采样 → Float 转换 → sherpa-onnx 推理
    │ 热管理可动态调整间隔: 100ms → 300ms → 800ms
    └── 检测到「开始录像」→ keywordFlow.emit()
```

### 录制模式 (Recording)

```
┌─────────────────────────────────────────────────────────────────┐
│ 1. dump预录帧: RingBufferRecorder → dumpRecentFrames() → preFrames│
│                                                                 │
│ 2. 切换编码器: 停止预录 → 创建 ActiveRecorder(用户分辨率/帧率/码率)  │
│    帧率恢复全帧率 (如30fps/60fps)                                  │
│                                                                 │
│ 3. 录制后半段: Camera2 → Pipeline → ActiveRecorder → postFrames    │
│                                                                 │
│ 4. 合成: preFrames + postFrames → VideoAssembler                 │
│    → MediaMuxer 写入 MP4 (含旋转元数据)                           │
│    → MediaStore 注册到相册                                        │
└─────────────────────────────────────────────────────────────────┘
```

---

## 模块职责详述

### VoiceTriggerRecorder — 核心编排器

管理整个应用的状态流转。负责：
- 启动/停止前台服务、WakeLock、热管理监听
- 编排预录 dump → 编码器切换 → 录制 → 合成 → 保存的完整流程
- **双模式选择**：根据 `ResolutionProfile` 判断使用 ImageReader（非 4K）或 Camera2 Surface（4K 全系列）
- 处理异常后自动回到待机

### CameraController — 摄像头控制器

Camera2 摄像头管理：
- **统一 Camera2 API**：所有模式均通过 Camera2 API 实现，`bindPreview()` 创建 CaptureSession
- **非 Surface 模式**：TextureView（预览）+ ImageReader（帧捕获 YUV_420_888），用于非 4K 分辨率
- **Surface 模式**（4K 全系列）：双 Surface 输出（TextureView + 编码器 InputSurface），相机零拷贝
- **多镜头支持**：WIDE/ULTRA_WIDE/TELEPHOTO/FRONT，超广角通过逻辑相机 + `CONTROL_ZOOM_RATIO` 实现
- **缩放控制**：双指捏合缩放，`CONTROL_ZOOM_RATIO` + `rebuildCaptureRequest()`，物理相机模式禁用
- `updateSurfaceFps()`：Surface 模式热管理降频（修改 AE FPS Range，不重建编码器）
- **视频防抖 (EIS)**：通过 `CONTROL_VIDEO_STABILIZATION_MODE` 开启，直接在 CaptureRequest 中设置
- **并发保护**：`requestLock` 保护 rebuildCaptureRequest/stopCamera2Session，`synchronized` 防止多线程崩溃

### CameraFramePipeline — 帧路由中心

连接 Camera2 ImageReader 和编码器，单线程处理（`FrameAnalyzer` daemon 线程）。

双路径支持：
- **4K 零拷贝路径**：`feedFrameDirect()` — YUV 直接写入编码器输入缓冲区，省去 ~12MB ByteArray 中转
- **普通路径**：`feedFrame()` — YUV → NV12 ByteArray → 编码器 ByteBuffer

五大优化：
- **实际帧率测量**：`measuredFps` 动态跟踪相机实际输出帧率，`skipPattern` 基于真实值计算
- **帧率节流**: `skipPattern = ceil(measuredFps / targetFps)`，待机/热降频时自适应跳帧
- **缓冲区复用**: `fullNv12Buffer` + `scaledNv12Buffer` 消除每帧 ~16MB 分配
- **双线性插值缩放**: `cropAndScaleNv12()` 使用 16.16 定点双线性插值，保持宽高比不变形
- **Surface 模式**: 4K 全系列 `setSurfaceMode(true)`，`onImageAvailable()` 跳过编码数据流

### YuvConverter — 格式转换工具

- `imageToNv12()`: YUV_420_888 → NV12 ByteArray，支持 `reuse` 参数复用输出缓冲区
- `imageToNv12Direct()`: YUV_420_888 → 直接写入 ByteBuffer（4K 零拷贝路径）
- `cropAndScaleNv12()`: 居中裁剪 + 缩放，定点整数运算（16.16 格式），支持 `reuse`
- UV 平面：批量 ByteBuffer → ByteArray（2 次 JNI），再在 ByteArray 上交错
- **关键注意**: `interleaveUv()` 的 `dstOffset` 必须为 `width * height`（NV12 格式 UV 跟在 Y 之后）

### RingBufferRecorder — 预录编码器

- **双输入模式**：
  - ByteBuffer 模式（默认）：`feedFrame()` 送入 NV12 ByteArray
  - Surface 模式（4K@60fps）：`createInputSurface()` 获取 Surface，相机零拷贝直入
- MediaCodec H.264 High Profile
- 环形缓冲区 = `ConcurrentLinkedDeque<EncodedFrame>`
- 容量 = 码率 × 时长 / 8（如 3Mbps × 30s ≈ 11.25MB）
- 溢出时从关键帧边界丢弃旧帧（保证 H.264 可解码）
- fps/bitrate 由 ThermalThrottler 动态调整
- `useSurfaceInput` 判断条件：`width >= 3840`（所有 4K 分辨率均使用 Surface 模式）

#### 跨线程安全（重要）

`RingBufferRecorder` 实例由 `PreRecordManager.ringBuffer` 持有，在多个线程间共享：

| 操作 | 线程 | 说明 |
|------|------|------|
| `createBuffer()` 创建 | `FrameAnalyzer`（相机分析线程） | 首帧到达时触发 |
| `feedFrame()` 喂帧 | `FrameAnalyzer` | ImageReader 回调 |
| `drainLoop()` 轮询 | `Dispatchers.Main`（主线程） | 等待 ringBuffer 非 null |
| `drainEncoder()` drain | `Dispatchers.IO` | withContext 切换到 IO |
| `dumpPreFrames()` 读取 | `Dispatchers.Main` | 唤醒词触发时调用 |

因此 `PreRecordManager` 中的以下字段**必须**标记 `@Volatile`：
- `ringBuffer` — 相机线程写、主线程读
- `drainStarted` — 相机线程写、主线程读（updateThrottleConfig）
- `encoderSurfaceReady` — PreRecordManager 中，跨线程标记
- `cameraWidth` / `cameraHeight` — 相机线程写、主线程读

同样，`RingBufferRecorder` 内部的 `encoder` 字段也**必须**标记 `@Volatile`：
- `encoder` — FrameAnalyzer 线程写（prepare/start），IO 线程读（drainEncoder），任意线程写 null（stop）
- `inputSurface` — FrameAnalyzer 线程写（prepareWithSurface），任意线程释放（stop）

> **历史教训（三次迭代）**:
> - 第一轮：`ringBuffer` 缺少 `@Volatile`，主线程看不到相机线程写入。修复后仍不工作。
> - 第二轮（真正根因）：`RingBufferRecorder.encoder` 缺少 `@Volatile`。
>   `prepare()` 在 FrameAnalyzer 线程设置 encoder，`drainEncoder()` 在 IO 线程读取。
>   没有 `@Volatile` 时 IO 线程始终看到 null → drainEncoder 立即返回 → 环形缓冲永远为空。
>   **关键认知：对象引用的内部字段跨线程访问也需要 `@Volatile`，仅外层容器的 `@Volatile` 不够。**
> - 第三轮（YUV 偏移量 bug）：重构 `imageToNv12()` 提取 `interleaveUv()` 时 `dstOffset` 传了 `0` 而非 `width * height`。UV 数据覆盖 Y 平面前半部分，导致 1080p/720p 绿色画面。4K 因走零拷贝路径未受影响。
>   **关键认知：重构 NV12/YUV 代码时必须验证 UV 偏移量，分离方法时参数不要硬编码为 0。**

### ActiveRecorder — 高质量录制器

- 使用用户选择的分辨率/帧率/码率
- 所有编码帧收集到 `frames` 列表（不丢弃）
- 支持 `signalEndOfStream()` 安全关闭（重试 10 次）
- AVC Level 动态选择：`mbPerSec > 1,000,000` → Level 5.2（4K@60fps），其余 → Level 4
- 从 `INFO_OUTPUT_FORMAT_CHANGED` 提取 CSD-0 (SPS) 和 CSD-1 (PPS)

### KwsManager — 语音唤醒

- sherpa-onnx Zipformer2 Transducer 模型（~3.3M 参数）
- 单线程 CPU 推理，100ms 读取间隔（省电）
- 模型文件在 `assets/onnx-kws/`
- 读取间隔由 ThermalThrottler 动态调整（100ms → 1000ms）

### VideoStorageManager — 视频存储

- MediaStore API + Scoped Storage 兼容
- 文件保存在 `DCIM/SportCamera/`
- 使用 `IS_PENDING` 标记防止未完成文件出现在相册
- MediaMuxer 写入 H.264 MP4，竖屏时设置 rotation=90°

### PowerStateManager — 省电管理

- `PARTIAL_WAKE_LOCK`：CPU 保持运行，允许屏幕关闭
- 30 分钟安全阀防止无限持有
- 电池信息通过 StateFlow 暴露（30 秒轮询更新）

### ThermalThrottler — 热管理

监听 Android `PowerManager.addThermalStatusListener` 回调：

| 热等级 | 预录FPS | 预录码率 | KWS间隔 | 说明 |
|--------|---------|---------|---------|------|
| Normal | 30 | 3Mbps | 100ms | 默认 |
| Light | 24 | 2.4Mbps | 120ms | 轻微降频 |
| Moderate | 20 | 2.0Mbps | 150ms | 中度降频 |
| Severe | 10 | 1Mbps | 300ms | 大幅降低 |
| Critical | 6 | 600Kbps | 500ms | 最低功耗 |
| Emergency | 4 | 400Kbps | 800ms | 保命模式 |
| Shutdown | 2 | 200Kbps | 1000ms | 接近停止 |

热管理降频实现方式：
- **ByteBuffer 模式**：重建 RingBufferRecorder（新 fps/bitrate） + 修改 Pipeline skipPattern
- **Surface 模式**：通过 `CameraController.updateSurfaceFps()` 修改 Camera2 AE FPS Range（不重建编码器）

---

## 依赖关系图

```
MainActivity
  └── MainScreen (Compose)
        ├── CameraPreview ← CameraController
        ├── StatusBar ← appState, batteryLevel
        ├── RecordIndicator ← appState
        └── ControlBar ← settings, profile, orientation
              │
        CameraViewModel
          ├── AppContainer (DI 单例)
          │     ├── KwsManager
          │     ├── CameraController
          │     ├── CameraFramePipeline
          │     ├── PreRecordManager
          │     ├── VideoStorageManager
          │     ├── PowerStateManager
          │     └── ThermalThrottler
          ├── VoiceTriggerRecorder (scope = viewModelScope)
          └── SettingsRepository (DataStore)
```

---

## 权限清单

| 权限 | 用途 |
|------|------|
| `CAMERA` | 摄像头预览和帧捕获 |
| `RECORD_AUDIO` | 麦克风语音唤醒 |
| `FOREGROUND_SERVICE` | 前台服务保活 |
| `FOREGROUND_SERVICE_CAMERA` | 前台服务摄像头类型 |
| `FOREGROUND_SERVICE_MICROPHONE` | 前台服务麦克风类型 |
| `WAKE_LOCK` | CPU 保持运行 |
| `POST_NOTIFICATIONS` | 前台服务通知 (Android 13+) |

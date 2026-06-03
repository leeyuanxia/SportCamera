# 性能与省电优化指南

## 优化概览

运动相机 App 的核心场景是**长时间待机**（语音监听 + 环形缓冲预录），因此省电和散热是最关键的性能指标。本文档记录了已实施的优化策略及其原理。

---

## 一、省电架构

### 1.1 三层省电保障

```
┌─────────────────────────────────────────────┐
│ Layer 3: ThermalThrottler (自适应降频)        │
│   监听设备温度 → 动态调整预录/KWS 参数         │
├─────────────────────────────────────────────┤
│ Layer 2: PowerStateManager (CPU 保持)        │
│   PARTIAL_WAKE_LOCK: 灭屏后 CPU 仍运行        │
├─────────────────────────────────────────────┤
│ Layer 1: CameraForegroundService (进程保活)   │
│   前台服务: 防止系统杀掉后台进程                │
└─────────────────────────────────────────────┘
```

**为什么需要全部三层？**

| 层 | 解决的问题 | 不加的后果 |
|----|-----------|-----------|
| 前台服务 | Android 14+ 积极杀后台进程 | 待机几分钟后进程被杀，KWS 和预录停止 |
| WakeLock | CPU 深度休眠 | 灭屏后 CPU 挂起，音频录制中断，预录停止 |
| 热管理 | 长时间运行导致设备过热 | 设备降频保护性关机，或用户因发烫主动关闭 |

### 1.2 WakeLock 策略

```
PARTIAL_WAKE_LOCK（部分唤醒锁）
├── CPU: 保持运行 ✓
├── 屏幕: 允许关闭 ✓（最省电的 WakeLock 类型）
├── 键盘: 允许关闭 ✓
└── 安全阀: 30 分钟自动释放（防止应用崩溃后无限持有）
```

只在待机时持有 WakeLock。录制时依赖 `FLAG_KEEP_SCREEN_ON`（由 MainScreen 管理）。

### 1.3 前台服务

- 类型: `camera | microphone`（Android 14+ 要求明确声明）
- 通知: 低优先级，不发出声音
- 生命周期: `enterStandby()` 启动，`stop()` 停止
- `START_STICKY`: 被杀后自动重启

---

## 二、帧处理优化

### 2.1 双路径架构

```
分辨率判断:
  width >= 3840 && fps > 30 (4K@60fps)?
     │
     ├── YES → Camera2 Surface 模式（零拷贝）
     │          相机 → 编码器 InputSurface（硬件零拷贝）
     │          CameraFramePipeline.setSurfaceMode(true) → analyze() 跳过
     │          帧率控制: Camera2 AE FPS Range
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

### 2.2 帧率节流（省 50% CPU）

```
相机输出: ~30fps / ~60fps
                │
     ┌──────────┴──────────┐
     │                     │
 待机模式 (30fps)       录制模式 (全帧率)
 skipPattern = 1       skipPattern = 1
 全部处理              全部处理
     │                     │
     │ 完整 YUV→NV12→编码   │ 完整 YUV→NV12→编码
     │                     │
     ▼                     ▼
 与录制帧率一致        全帧率录制

 Surface 模式 (4K@60fps):
 不走帧管线，通过 Camera2 AE FPS Range 控制帧率
```

**跳帧时机很关键**：在 `analyze()` 最开始（YUV 转换之前）跳帧，避免浪费任何 CPU 在即将被丢弃的帧上。

### 2.3 缓冲区复用（消除 GC 停顿）

优化前每帧的内存分配：

```
imageToNv12():       ByteArray(~13.3MB) ← 每帧分配，30fps = 400MB/s
cropAndScaleNv12():  ByteArray(~3.1MB)  ← 每帧分配
KwsManager:          FloatArray(6.4KB)   ← 每100ms分配
                                          总计: ~400+ MB/s 垃圾
```

优化后：

```
fullNv12Buffer:      复用，仅分辨率变化时重新分配
scaledNv12Buffer:    复用，仅目标尺寸变化时重新分配
uBytesCache/vBytesCache: 复用 UV 中间缓冲区
KwsManager samplesBuffer: 复用 Float 数组
                          总计: 稳定状态 0 分配/帧

4K 零拷贝路径 (feedFrameDirect):
  不经过 ByteArray 中转，YUV 直接写入编码器输入缓冲区
  省去 ~12MB 的 fullNv12Buffer 分配
```

### 2.3 UV 平面优化

原方案（慢）：
```
每像素: position(idx) + get() → 2次 JNI 调用
1488×1488 = 2.2M 像素 → 4.4M JNI 调用/帧
```

优化方案（快）：
```
整块: uBuffer.get(uBytes) → 1次 JNI 调用
交错: ByteArray 纯内存操作 → 0次 JNI 调用
总计: 2次 JNI 调用/帧（从 4.4M 降低）
```

### 2.4 定点整数缩放

`cropAndScaleNv12()` 使用 16.16 定点格式代替浮点：

```kotlin
val xStep = (cropW shl 16) / dstW   // 一次计算
val srcX = (x * xStep) shr 16       // 循环内只做移位和乘法
```

比 `(x * xRatio).toInt()`（浮点乘法 + 转换）快得多，在内层循环中效果显著。

---

## 三、热管理（自适应降频）

### 3.1 工作原理

```
Android ThermalService
     │
     ▼
ThermalThrottler (init 中注册监听器)
     │ 收到热等级变化
     ▼
更新 ThrottleConfig StateFlow
     │
     ├──→ PreRecordManager.updateThrottleConfig()
     │      └── 重建 RingBufferRecorder (新 fps/bitrate)
     │
     ├──→ CameraFramePipeline.setTargetFps()
     │      └── 调整 skipPattern（更多跳帧）
     │
     └──→ KwsManager.updateReadInterval()
            └── 增加读取间隔（减少 CPU 唤醒频率）
```

### 3.2 降频效果

| 场景 | Normal | Moderate | Severe |
|------|--------|----------|--------|
| 预录帧率 | 30fps | 20fps | 10fps |
| 预录码率 | 3Mbps | 2Mbps | 1Mbps |
| KWS 间隔 | 100ms | 150ms | 300ms |
| CPU 估算 | 基准 | ~60% | ~30% |

### 3.3 编码器重建与 Surface 模式降频

**ByteBuffer 模式**下，热等级变化时 `PreRecordManager` 会：
1. 停止当前 `RingBufferRecorder`
2. 释放资源
3. 用新的 fps/bitrate 创建新的编码器

此过程中会短暂丢失几帧（编码器切换约 50-200ms），对预录影响可忽略。

**Surface 模式**（4K@60fps）下：
- **不重建编码器**（代价太大，需要重建 Camera2 会话）
- 通过 `CameraController.updateSurfaceFps()` 修改 Camera2 `CONTROL_AE_TARGET_FPS_RANGE`
- 从缓存中重新添加所有 Surface target，重建 CaptureRequest
- 帧率变更更平滑，无编码器重建延迟

---

## 四、录制模式优化

### 4.1 编码器切换流程

```
唤醒词检测到
     │
     ├─① dump 预录帧 (O(n), n=缓冲帧数)
     │
     ├─② 断开帧管线 → 停止预录编码器
     │  (此间隙帧被丢弃, ImageAnalysis STRATEGY_KEEP_ONLY_LATEST)
     │
     ├─③ 创建 ActiveRecorder + prepare + start
     │  (MediaCodec 创建约 50-200ms)
     │
     ├─④ 连接帧管线 → 恢复全帧率 (setTargetFps)
     │
     └─⑤ 录制后半段 → 合成 → 保存
```

### 4.2 视频合成

- 使用 `MediaMuxer` 将前后半段合成为 MP4
- PTS 重新归一化（前半段和后半段来自不同编码器，时间戳基准不同）
- 非单调 PTS 处理：`writePtsUs = max(ptsUs, lastPtsUs + 1)`
- 竖屏录制：`muxer.setOrientationHint(90)` 让播放器自动旋转

---

## 五、电池监控

```
CameraViewModel.monitorBattery()
     │
     ├── 每 30 秒调用 PowerStateManager.updateBatteryInfo()
     │
     └── 读取 BatteryManager.BATTERY_PROPERTY_CAPACITY
          → 更新 _batteryLevel StateFlow
          → UI StatusBar 自动显示电量
```

---

## 六、性能基准参考

### 理论 CPU 使用

| 模式 | 操作 | CPU 估算 |
|------|------|---------|
| 待机 Normal | 30fps YUV+编码 + KWS 100ms | 低 |
| 待机 Severe | 10fps YUV+编码 + KWS 300ms | 很低 |
| 录制 720p30 | 30fps YUV+裁剪+缩放+编码 | 低 |
| 录制 1080p30 | 30fps YUV+裁剪+缩放+编码 | 中 |
| 录制 1080p60 | 60fps YUV+裁剪+缩放+编码 | 高 |
| 录制 4K30 | 30fps 大帧YUV+零拷贝编码 | 高 |
| 录制 4K60 | Camera2 Surface 零拷贝 | 中（硬件编码） |

### 内存占用

| 组件 | 大小 |
|------|------|
| NV12 缓冲区 (2976×2976) | ~13.3MB × 1（复用） |
| NV12 缩放缓冲区 (1920×1080) | ~3.1MB × 1（复用） |
| UV 中间缓冲区 | ~8.8MB × 1（复用） |
| 环形缓冲 (30s@3Mbps) | ~11.25MB |
| KWS 模型 | ~10MB (ONNX) |
| 编码帧累积 (录制中) | 取决于时长和码率 |

---

## 七、故障排查

### 灭屏后不响应唤醒词

检查：
1. 前台服务是否运行（通知栏应有「运动相机」通知）
2. WakeLock 是否持有（`adb shell dumpsys power | grep SportCamera`）
3. KWS 是否在监听（Logcat 过滤 `KwsManager`）

### 视频画面异常

| 症状 | 原因 | 位置 |
|------|------|------|
| 绿色覆盖 + 左半画面 | `interleaveUv()` 的 `dstOffset` 为 0（应为 `width*height`） | `YuvConverter.imageToNv12()` |
| 画面拉伸 | 直接缩放未做裁剪 | `cropAndScaleNv12()` 居中裁剪 |
| BufferOverflow | 相机分辨率与编码器不匹配 | `CameraFramePipeline` 缩放 |
| 画面卡顿 | 每帧大量分配导致 GC | 缓冲区复用 |

> **重要**: `interleaveUv()` 调用时 `dstOffset` 必须为 `width * height`。
> 曾因重构时硬编码为 0，导致 UV 覆盖 Y 平面前半部分，1080p/720p 画面变绿。
> 4K 因走 `feedFrameDirect` 零拷贝路径未受影响。

### 预录视频只有唤醒后内容（无前半段）

**症状**: 待机 N 秒后说"开始录像"，保存的视频只包含唤醒后的内容，前半段为空。

**关键日志**: Logcat 过滤 `PreRecordManager` 和 `RingBufferRecorder`：
- ✅ 正常：`drainLoop: ringBuffer 已就绪 (gen=X, waits=Yx10ms)，开始 drain`
- ❌ 异常：`drainLoop: 等待编码器超时（3秒内无帧到达）`
- ❌ 异常：`dumpPreFrames: 缓冲总帧数=0`

**根因排查**:
1. **`RingBufferRecorder.encoder` 缺少 `@Volatile`**（已修复）：`encoder` 在 FrameAnalyzer 线程的 `prepare()` 中赋值，但 `drainEncoder()` 在 IO 线程读取。没有 `@Volatile`，IO 线程始终看到 null → drainEncoder 立即返回 → 环形缓冲永远为空。表现为日志中无 `drainEncoder 开始`，但 `编码器已启动` 正常出现
2. **`PreRecordManager.ringBuffer` 缺少 `@Volatile`**（已修复）：同上，外层容器的 @Volatile 不等于内部字段的可见性
3. **drainLoop 未运行**：检查 `enterStandby()` 中 `preRecordDrainJob` 是否被正确启动
4. **编码器未创建**：检查 `feedFrame()` 日志是否出现 "首帧" 和 "环形缓冲已创建"
5. **热管理重建过频**：检查 `ThermalThrottler` 是否频繁触发编码器重建

### 设备发烫

1. 查看 Logcat 中 `ThermalThrottler` 的热等级
2. 确认预录 fps/bitrate 是否已自动降低
3. 确认 KWS 间隔是否已增加
4. 考虑降低用户选择的分辨率档位

### 4K@60fps Surface 模式问题

**Surface 模式判断条件**: `width >= 3840 && fps > 30`

**关键日志**:
- ✅ `CameraController Surface 模式: ...` — Camera2 会话创建成功
- ✅ `Surface 模式编码器已准备: ...` — 编码器 Surface 创建成功
- ❌ `Camera2 绑定失败，降级到 ByteBuffer` — 自动降级，不影响功能

**排查步骤**:
1. 确认设备 Camera2 支持：`adb shell dumpsys media.camera` 查看 `SCALER_STREAM_CONFIGURATION_MAP`
2. 确认 FPS Range 支持：设备需支持 `[60, 60]` 或包含 60 的范围
3. 确认 PreviewView 使用 COMPATIBLE 模式（Surface 模式需要从 TextureView 获取 Surface）
4. 降级回 ByteBuffer 后帧率约 50fps（ISP YUV 带宽瓶颈），属于正常现象

### 视频防抖 (EIS)

**原理**：通过 Camera2 API 的 `CONTROL_VIDEO_STABILIZATION_MODE_ON` 开启硬件级电子防抖，相机 ISP 对每帧做反向运动补偿以消除手持抖动。开启后会轻微裁剪画面（FOV 减小），这是正常现象。

**能力检测**：启动/切换镜头时自动查询 `CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES`，不支持 ON 模式的设备 UI 开关置灰。

**双路径实现**：
- CameraX 路径：通过 `Camera2Interop.Extender` 在 Preview 和 ImageAnalysis 的 CaptureRequest 中设置
- Camera2 Surface 路径：直接在 `CaptureRequest.Builder` 中设置，热管理降频时通过 `rebuildSurfaceCaptureRequest()` 保留设置

**录制保护**：录制中切换防抖会导致视频中途画面跳动，ViewModel 层通过 `appState.isRecording` 检查自动拒绝。

**关键日志**：
- ✅ `EIS 支持检测: cameraId=X, modes=[0,1], supported=true` — 设备支持
- ✅ `视频防抖已开启, mode=1` — 防抖已启用
- ⚠️ `当前设备不支持 EIS，忽略开启请求` — 设备不支持（UI 开关灰色）
- ⚠️ `录制中禁止切换防抖` — 录制中操作被拒绝

**功耗影响**：EIS 由相机 ISP 硬件处理，CPU 开销可忽略，对电池续航无明显影响。

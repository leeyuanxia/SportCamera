# SportCamera — 项目编码规范

> 本文件是 Claude Code 在此项目中编写代码时必须遵循的规范。
> 所有新代码和修改都应遵守以下约定。
> 思考必须使用中文

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
- `CancellationException` 必须正确处理，不应被吞掉

### 3. 线程安全规范（最重要）

数据流涉及三个线程：

| 线程 | 身份 | 主要操作 |
|------|------|---------|
| `FrameAnalyzer` | 相机分析线程 | `feedFrame()`、`createBuffer()` |
| `Dispatchers.Main` | 主线程 | `drainLoop()`、`dumpPreFrames()`、KWS collect |
| `Dispatchers.IO` | IO 线程池 | `drainEncoder()`（withContext 切换） |

**规则**：

1. **跨线程读写的字段必须标记 `@Volatile`** — 不例外
2. `ConcurrentLinkedDeque` 等并发集合只保证自身操作原子性，不提供字段级可见性
3. 状态重置方法（`stop()`、`release()`）必须重置所有标志位
4. **`MediaCodec encoder` 引用必须 `@Volatile`** — feed 和 drain 在不同线程
5. 多线程共享的可变状态需要 `synchronized` 保护（如 `requestLock`、`encoderLock`）

> **历史教训**：`RingBufferRecorder.encoder` 缺少 `@Volatile` 导致 IO 线程始终看到 null，编码器输出从未被 drain。重构 NV12/YUV 代码时必须验证 UV 偏移量（`width * height`）。

### 4. 日志规范

- 使用项目封装的 `DebugLog`（`util/DebugLog.kt`），**不再直接使用 `android.util.Log`**
- **Debug 包输出日志，Release 包完全静默**
- 错误路径 `DebugLog.e`，正常流程 `DebugLog.d`，罕见/可疑 `DebugLog.w`

### 5. MediaCodec 使用规范

- **双输入模式**：ByteBuffer（默认）和 Surface（4K 零拷贝）
- ByteBuffer `dequeueInputBuffer` 超时 **1000μs**，4K 零拷贝 **5000μs**
- `dequeueOutputBuffer` 超时 **10_000μs**
- 编码器启动顺序：`encoder.start()` → `isRunning = true`
- `drainEncoder()` 在 `withContext(Dispatchers.IO)` 中运行

### 6. 编码器配置

- RingBufferRecorder：High Profile, 关键帧间隔 2s
- ActiveRecorder：High Profile, 关键帧间隔 1s
- Level 选择：`mbPerSec > 1,000,000` → Level 5.2，其他 → Level 4
- Surface 模式：`KEY_COLOR_FORMAT` 使用 `COLOR_FormatSurface`

### 7. 视频合成规范

- `preFrames + postFrames` 拼接时过滤 Config 帧
- PTS 归一化：`baseTimeUs = dataFrames.first().presentationTimeUs`
- 非单调 PTS 保护：`writePtsUs = max(ptsUs, lastPtsUs + 1)`

### 8. 省电设计原则

- 帧率节流在 YUV 转换**之前**执行（跳帧零开销）
- 缓冲区复用消除每帧 ~16MB 分配
- 热管理通过 `ThermalThrottler` 自适应降频
- **统一 Camera2 架构**：非 Surface 模式用 ImageReader → NV12 → ByteBuffer，4K@60fps 用 InputSurface 零拷贝

### 9. UI / Compose

- 状态通过 `AppState` sealed interface 驱动 UI
- 设置项通过 `SettingsRepository` (DataStore) 持久化
- 不在 Composable 中直接调用硬件 API，一律通过 ViewModel
- 横屏/竖屏均需适配，使用 `LocalConfiguration` 检测方向

---

## 测试与验证

### 编译

```bash
./gradlew assembleDebug
```

### Logcat 过滤器

```
adb logcat -s SportCameraLogger:D
```

### 常见问题排查

详见 `docs/performance-and-power.md` 第七节「故障排查」。

---

## 文档索引

重要：每次修改新增完代码请记录修改日志到 `docs/changelog.md`

| 文件 | 内容 |
|------|------|
| `docs/architecture.md` | 项目架构、模块职责、数据流 |
| `docs/video-pipeline.md` | 视频管线详解（YUV转换、编码、合成） |
| `docs/performance-and-power.md` | 省电优化、热管理、故障排查 |
| `docs/changelog.md` | 修改日志 |

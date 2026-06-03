# SportCamera — 项目编码规范

> 本文件是 Claude Code 在此项目中编写代码时必须遵循的规范。
> 所有新代码和修改都应遵守以下约定。
> 思维必须使用中文

## 项目概述

- **包名**: `cn.leeyuanxia.sportcamera`
- **定位**: 运动相机 Android App，语音唤醒词自动触发录像，支持视频防抖 (EIS)
- **最低版本**: Android 14 (API 34) | **目标版本**: Android 16 (API 36)
- **技术栈**: Kotlin 100%, Jetpack Compose, CameraX 1.4.1, Camera2 API, MediaCodec H.264, sherpa-onnx KWS
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
- **4K@60fps 双路径架构**：
  - 非 4K 分辨率：CameraX ImageAnalysis → YUV → NV12 → ByteBuffer → 编码器
  - 4K@60fps：Camera2 API → 编码器 InputSurface（零拷贝，绕过 ISP YUV 带宽瓶颈）
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

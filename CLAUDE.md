# SportCamera — 项目编码规范

> 本文件是 Claude Code 在此项目中编写代码时必须遵循的规范。
> 所有新代码和修改都应遵守以下约定。

## 项目概述

- **包名**: `cn.leeyuanxia.sportcamera`
- **定位**: 运动相机 Android App，语音唤醒词自动触发录像
- **最低版本**: Android 14 (API 34) | **目标版本**: Android 16 (API 36)
- **技术栈**: Kotlin 100%, Jetpack Compose, CameraX 1.4.1, MediaCodec H.264, sherpa-onnx KWS
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

> **历史教训（两次迭代）**:
> - 第一轮：`PreRecordManager.ringBuffer` 缺少 `@Volatile`，修复后仍然不工作
> - 第二轮（真正根因）：`RingBufferRecorder.encoder` 缺少 `@Volatile`
>   - `prepare()` 在 FrameAnalyzer 线程设置 `encoder = MediaCodec.create(...)`
>   - `drainEncoder()` 在 IO 线程读取 `val codec = encoder ?: return`
>   - 没有 `@Volatile`，IO 线程始终看到 `null`，drainEncoder 立即返回
>   - 结果：编码器接收了 249 帧输入但输出从未被 drain，环形缓冲永远为空
>   - **教训：对象引用的内部字段跨线程访问也需要 @Volatile，不能仅靠外层容器的 @Volatile**

### 4. 日志规范

- 使用 `android.util.Log`，不引入 Timber 等日志库
- 关键状态变化必须记录日志：
  - 编码器创建/启动/停止
  - 协程启动/结束
  - 帧计数里程碑（每 150 帧记录一次）
  - 异常和超时
- 异常日志记录完整信息：异常类型 + 消息
- 错误路径用 `Log.e`，正常流程用 `Log.d`，罕见/可疑情况用 `Log.w`

### 5. MediaCodec 使用规范

- 使用 **ByteBuffer 输入模式**（非 Surface），通过 `dequeueInputBuffer` / `queueInputBuffer`
- `dequeueInputBuffer` 超时使用 **1000μs (1ms)**，不用 0（部分设备 timeout=0 抛异常）
- `dequeueOutputBuffer` 超时使用 **10_000μs (10ms)**
- 编码器启动顺序：`encoder.start()` → `isRunning = true`（防止 feedFrame 在未启动时调用）
- EOS 发送：ByteBuffer 模式下用 `queueInputBuffer + BUFFER_FLAG_END_OF_STREAM`，需要重试循环
- `drainEncoder()` 在 `withContext(Dispatchers.IO)` 中运行，`delay(1)` 作为 TRY_AGAIN_LATER 的退避

### 6. 编码器配置

- RingBufferRecorder（预录）：High Profile, Level 4, 关键帧间隔 2s
- ActiveRecorder（录制）：High Profile, Level 4, 关键帧间隔 1s
- 两者使用相同 Profile/Level 以确保 SPS/PPS 兼容（前后段拼接）

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
adb logcat -s PreRecordManager:D RingBufferRecorder:D VoiceTrigger:D
```

### 常见问题排查

详见 `docs/performance-and-power.md` 第七节「故障排查」。

---

## 文档索引

| 文件 | 内容 |
|------|------|
| `docs/architecture.md` | 项目架构、模块职责、数据流 |
| `docs/video-pipeline.md` | 视频管线详解（YUV转换、编码、合成） |
| `docs/performance-and-power.md` | 省电优化、热管理、故障排查 |

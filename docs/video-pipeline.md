# 视频录制管线详解

## 数据流总览

```
Camera Sensor
     │
     ▼
CameraX ImageAnalysis (YUV_420_888, ~30fps)
     │
     ▼
CameraFramePipeline.analyze()
     │
     ├── ① 帧率节流（skipPattern）
     ├── ② imageToNv12(): YUV_420_888 → NV12
     ├── ③ cropAndScaleNv12(): 居中裁剪 + 缩放
     │
     ▼
FrameConsumer.feedFrame(nv12, timestamp, w, h)
     │
     ├── Standby: RingBufferRecorder (环形缓冲)
     └── Recording: ActiveRecorder (全帧收集)
            │
            ▼
     MediaCodec H.264 编码
            │
            ▼
     drainEncoder() → EncodedFrame 列表
            │
            ▼
     VideoAssembler → MediaMuxer → MP4 文件
            │
            ▼
     VideoStorageManager → MediaStore → 系统相册
```

---

## YUV 转换详解

### YUV_420_888 格式

CameraX ImageAnalysis 输出 `YUV_420_888` 格式，包含 3 个平面：

```
Plane[0]: Y (亮度)     — 全分辨率 (width × height)
Plane[1]: U (蓝色色差)  — 四分之一分辨率 (width/2 × height/2)
Plane[2]: V (红色色差)  — 四分之一分辨率 (width/2 × height/2)
```

每个平面有：
- `rowStride`: 行步长（可能大于 width，因为有 padding 对齐）
- `pixelStride`: 像素步长（通常 Y=1, UV=1 或 2）

### NV12 格式（编码器需要）

```
NV12 布局:
┌─────────────────────────────┐
│ Y Plane: width × height     │  ← 亮度，逐行排列
├─────────────────────────────┤
│ UV Plane: width × height/2  │  ← U V 交错排列
│ U0 V0 U1 V1 U2 V2 ...      │
└─────────────────────────────┘

总大小: width × height × 3/2
```

### imageToNv12() 转换过程

```
输入: Image (YUV_420_888, 3 个 ByteBuffer 平面)
输出: ByteArray (NV12 格式)

Step 1: Y 平面
  if (rowStride == width):
    整块复制 yBuffer.get(nv12, 0, width*height)  ← 最快路径
  else:
    逐行复制，跳过每行末尾的 padding

Step 2: UV 平面（优化后）
  uBuffer.get(uBytes)  ← 批量复制，1次 JNI
  vBuffer.get(vBytes)  ← 批量复制，1次 JNI

  for (row, col):
    nv12[...] = uBytes[...]  ← ByteArray 直接索引，无 JNI
    nv12[...] = vBytes[...]  ← ByteArray 直接索引，无 JNI
```

---

## 居中裁剪 + 缩放

### 为什么需要裁剪？

CameraX `setTargetResolution` 只是提示，实际分辨率取决于相机传感器。

```
示例: 请求 1920×1080 (16:9)，但相机输出 2976×2976 (1:1)

如果直接缩放 (scaleNv12):
  2976×2976 → 1920×1080
  1:1 被拉伸为 16:9 → 画面左右压缩变形 ❌

如果裁剪+缩放 (cropAndScaleNv12):
  2976×2976 → 裁剪为 2976×1674 (16:9) → 缩放到 1920×1080 ✓
```

### 裁剪算法

```kotlin
val srcAspect = srcW / srcH  // 源宽高比
val dstAspect = dstW / dstH  // 目标宽高比

if (srcAspect > dstAspect) {
  // 源更宽 → 裁左右
  cropW = (srcH * dstAspect).toInt()
  cropH = srcH
  cropX = (srcW - cropW) / 2
  cropY = 0
} else {
  // 源更高 → 裁上下
  cropW = srcW
  cropH = (srcW / dstAspect).toInt()
  cropX = 0
  cropY = (srcH - cropH) / 2
}
```

### 缩放算法（16.16 定点整数）

```kotlin
// 预计算缩放步长
val xStep = (cropW shl 16) / dstW   // 相当于 cropW/dstW 的定点表示
val yStep = (cropH shl 16) / dstH

// 内循环只用移位和整数运算
for (y in 0 until dstH) {
  val srcY = cropY + ((y * yStep) shr 16)
  for (x in 0 until dstW) {
    val srcX = cropX + ((x * xStep) shr 16)
    dst[y * dstW + x] = src[srcY * srcW + srcX]
  }
}
```

---

## 编码器详解

### 跨线程模型

编码管线涉及三个线程，必须正确处理可见性：

```
┌─────────────────────┐   ┌───────────────────┐   ┌─────────────────┐
│  FrameAnalyzer      │   │  Main (Disp.Main) │   │  IO (Disp.IO)   │
│  (相机分析线程)       │   │  (主线程)          │   │  (IO 线程池)     │
│                     │   │                   │   │                 │
│  feedFrame()        │   │  drainLoop()      │   │  drainEncoder() │
│  createBuffer()     │   │  dumpPreFrames()  │   │    ↓            │
│    ↓ ringBuffer = ○ │   │    ↓ ringBuffer?  │   │  addToRingBuf() │
│    ↓ drainStarted=T │   │    ↓ drainStarted? │   │                 │
└─────────────────────┘   └───────────────────┘   └─────────────────┘
          写                        读                    读写

关键: ringBuffer、drainStarted、cameraWidth 必须标记 @Volatile
      否则 ARM 设备上主线程可能永远看不到相机线程的写入
```

### MediaCodec ByteBuffer 输入模式

```
                    ┌──────────────────┐
  feedFrame() ────► │ Input Buffers    │
  (NV12 ByteArray)  │  (dequeue/queue) │
                    ├──────────────────┤
                    │   MediaCodec     │
                    │  H.264 Encoder   │
                    ├──────────────────┤
  drainEncoder()◄── │ Output Buffers   │
  (EncodedFrame)    │  (dequeue)       │
                    └──────────────────┘
```

### RingBufferRecorder (待机预录)

```
参数:
  分辨率: 管线缩放后的目标尺寸（由 ThermalThrottler 动态调整）
  帧率: 30fps (默认) → 热管理可降为 10/6/4/2 fps
  码率: 3Mbps (默认) → 热管理可降为 1M/600K/400K/200K bps
  Profile: High (与 ActiveRecorder 一致，确保 SPS/PPS 兼容)
  关键帧间隔: 2s

环形缓冲:
  容量 = bitrate × duration / 8
  例如: 3Mbps × 30s ≈ 11.25MB

  溢出策略:
  while (currentBytes > maxBytes):
    if 第二帧是关键帧:
      删除第一个帧
    else:
      break (等待下一个关键帧)

  dumpRecentFrames(durationMs):
    过滤最近 N 毫秒的帧
```

### ActiveRecorder (唤醒后录制)

```
参数:
  分辨率: 用户选择（720p/1080p/4K）
  帧率: 用户选择（30/60fps）
  码率: 用户选择（4~20Mbps）
  Profile: High, Level 4
  关键帧间隔: 1s

关闭流程:
  1. signalEndOfStream() → queueInputBuffer + EOS flag
     (重试10次，每次50ms超时)
  2. drainEncoder() → 循环 dequeueOutputBuffer 直到收到 EOS
  3. stop() + release() (在 finally 块中)
```

---

## 视频合成详解

### 前后半段拼接

```
preFrames (RingBufferRecorder 输出)    postFrames (ActiveRecorder 输出)
  │                                       │
  │ 时长: preRecordDuration.preHalfMs     │ 时长: preRecordDuration.postHalfMs
  │ 如不足则延长 post 段补偿               │ 不足补偿: rawPost + shortfall
  │                                       │
  │ 时间戳基准: pre-encoder               │ 时间戳基准: post-encoder
  │ CSD: ringBuffer.csdData (合并SPS+PPS) │ CSD: activeRecorder.csd0Data + csd1Data
  │                                       │
  └──────────┬────────────────────────────┘
             │
             ▼
  VideoAssembler.assemble()
             │
             ├── 过滤 Config 帧 (SPS/PPS)
             ├── 合并 preFrames + postFrames
             │
             ▼
  VideoStorageManager.assembleToMp4()
             │
             ├── 创建 MediaMuxer
             ├── 设置 video format (width, height, csd-0, csd-1)
             ├── 设置 rotation hint (竖屏=90°)
             ├── PTS 归一化: 基于 dataFrames[0] 的时间戳偏移
             ├── 非单调 PTS 处理: max(ptsUs, lastPtsUs + 1)
             │
             ▼
  输出: DCIM/SportCamera/SPORT_20260601_210035.mp4
```

> **注意**: `VoiceTriggerRecorder.onWakeWordDetected()` 中 `rawPostDurationMs`
> 应使用 `postHalfMs`（后录段时长），而非 `preHalfMs`。虽然当前两者值相同
> （均为 `seconds * 500L`），但语义不同，代码应正确表达意图。

### 时间戳处理

前后半段来自不同编码器实例，PTS 基准不同：

```
preFrames PTS:  [1000, 106666, 213333, ...]  ← RingBufferRecorder
postFrames PTS: [5000, 36666, 68333, ...]     ← ActiveRecorder

归一化后:
  baseTimeUs = dataFrames.first().presentationTimeUs  // 第一个帧的PTS
  for each frame:
    ptsUs = frame.presentationTimeUs - baseTimeUs     // 偏移到0开始
    writePtsUs = max(ptsUs, lastPtsUs + 1)            // 保证单调递增
```

---

## 分辨率配置

### 用户可选档位

| Profile | 分辨率 | 帧率 | 码率 | 用途 |
|---------|--------|------|------|------|
| HD_720P_30 | 1280×720 | 30fps | 4Mbps | 平衡画质与功耗 |
| FHD_1080P_30 | 1920×1080 | 30fps | 8Mbps | 高清标准 |
| FHD_1080P_60 | 1920×1080 | 60fps | 12Mbps | 高帧率运动场景 |
| UHD_4K_30 | 3840×2160 | 30fps | 20Mbps | 超高清 |

### 待机模式固定参数

| 参数 | 值 |
|------|---|
| 分辨率 | 管线缩放后的目标尺寸 |
| 帧率 | 30fps (默认，热管理可调) |
| 码率 | 3Mbps (默认，热管理可调) |
| 关键帧间隔 | 2s |

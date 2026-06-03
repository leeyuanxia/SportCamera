package cn.leeyuanxia.sportcamera.hardware.camera

import cn.leeyuanxia.sportcamera.util.DebugLog
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * 摄像头帧管线 — 连接 CameraX ImageAnalysis 和编码器
 *
 * 数据流：
 * CameraX ImageAnalysis → CameraFramePipeline.analyze()
 *   → YUV_420_888 转 NV12 → feedFrame() 送入当前编码器
 *
 * 性能优化：
 * - 帧率节流：待机 30fps 与录制帧率一致，保证合成视频流畅
 * - 缓冲区复用：fullNv12Buffer / scaledNv12Buffer 避免每帧分配 ~16MB
 * - 居中裁剪：保持宽高比，不拉伸变形
 */
class CameraFramePipeline : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CameraFramePipeline"
    }

    @Volatile
    private var currentEncoder: FrameConsumer? = null

    // 目标编码尺寸
    @Volatile
    private var targetWidth: Int = 0

    @Volatile
    private var targetHeight: Int = 0

    // 帧率节流：skipPattern=1 表示全部处理（待机 30fps 与相机输出帧率一致）
    @Volatile
    private var skipPattern: Int = 1

    private var frameCounter = 0L
    private var totalFrameCount = 0L
    private var discardedCount = 0L
    private var fedFrameCount = 0L

    // 可复用的 NV12 缓冲区（analyze() 在单线程执行，无需同步）
    // 消除每帧 ~16MB 的 ByteArray 分配 → 消除 GC 停顿
    private var fullNv12Buffer: ByteArray? = null
    private var scaledNv12Buffer: ByteArray? = null

    /**
     * 设置目标编码尺寸
     */
    fun setTargetSize(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        DebugLog.d(TAG, "目标编码尺寸: ${width}x${height}")
    }

    /**
     * 设置目标帧率（用于帧率节流）
     *
     * 相机输出约 30fps。当目标帧率低于 30 时，按比例跳帧。
     * 跳帧在 YUV 转换之前执行，节省 CPU、内存带宽和电池。
     *
     * @param fps 目标帧率（如待机 30fps、录制 30fps）
     */
    fun setTargetFps(fps: Int) {
        // 相机通常输出 30fps，计算跳帧比例
        val estimatedCameraFps = 30
        skipPattern = (estimatedCameraFps / fps).coerceAtLeast(1)
        DebugLog.d(TAG, "目标帧率: ${fps}fps, 跳帧比例: 1/${skipPattern}")
    }

    /**
     * 设置当前帧消费编码器
     */
    fun setEncoder(encoder: FrameConsumer?) {
        currentEncoder = encoder
        if (encoder != null) {
            DebugLog.d(TAG, "编码器已连接: ${encoder::class.simpleName}, 目标=${targetWidth}x${targetHeight}, skip=1/${skipPattern}")
        } else {
            DebugLog.d(TAG, "编码器已断开（已喂 $fedFrameCount 帧，丢弃 $discardedCount 帧）")
            fedFrameCount = 0
            discardedCount = 0
        }
    }

    /**
     * ImageAnalysis.Analyzer 实现
     */
    override fun analyze(image: ImageProxy) {
        totalFrameCount++
        val encoder = currentEncoder
        if (encoder == null) {
            discardedCount++
            if (discardedCount % 15 == 1L) {
                DebugLog.d(TAG, "无编码器，丢弃帧 (总接收: $totalFrameCount, 累计丢弃: $discardedCount)")
            }
            image.close()
            return
        }

        // 帧率节流：在 YUV 转换之前跳帧（最大节省 CPU）
        if (skipPattern > 1) {
            frameCounter++
            if (frameCounter % skipPattern != 0L) {
                image.close()
                return
            }
        }

        try {
            val proxyImage = image.image ?: run {
                discardedCount++
                image.close()
                return
            }

            // YUV → NV12 转换（复用缓冲区）
            var nv12 = YuvConverter.imageToNv12(proxyImage, fullNv12Buffer)
            fullNv12Buffer = nv12

            val timestampUs = image.imageInfo.timestamp / 1000

            var frameW = image.width
            var frameH = image.height

            // 居中裁剪 + 缩放（复用缓冲区）
            if (targetWidth > 0 && targetHeight > 0
                && (frameW != targetWidth || frameH != targetHeight)
            ) {
                nv12 = YuvConverter.cropAndScaleNv12(
                    nv12, frameW, frameH, targetWidth, targetHeight,
                    reuse = scaledNv12Buffer
                )
                scaledNv12Buffer = nv12
                frameW = targetWidth
                frameH = targetHeight
            }

            fedFrameCount++
            if (fedFrameCount % 150 == 0L) {
                DebugLog.d(TAG, "帧 ${frameW}x${frameH} → NV12 ${nv12.size}B, ts=${timestampUs}μs, 已喂: $fedFrameCount")
            }
            encoder.feedFrame(nv12, timestampUs, frameW, frameH)
        } catch (e: Exception) {
            DebugLog.w(TAG, "帧处理失败 (总接收: $totalFrameCount)", e)
        } finally {
            image.close()
        }
    }
}

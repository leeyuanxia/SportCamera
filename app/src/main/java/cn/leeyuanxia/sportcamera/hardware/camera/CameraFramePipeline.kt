package cn.leeyuanxia.sportcamera.hardware.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/**
 * 摄像头帧管线 — 连接 CameraX ImageAnalysis 和编码器
 *
 * 数据流：
 * CameraX ImageAnalysis → CameraFramePipeline.analyze()
 *   → YUV_420_888 转 NV12 → feedFrame() 送入当前编码器
 *
 * 支持动态切换编码器目标：
 * - 待机时 → RingBufferRecorder（环形缓冲，720p@15fps）
 * - 录制时 → ActiveRecorder（全质量，用户选择的参数）
 */
class CameraFramePipeline : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CameraFramePipeline"
    }

    @Volatile
    private var currentEncoder: FrameConsumer? = null

    // 目标编码尺寸（用于当相机原生输出与编码器尺寸不匹配时的缩放）
    @Volatile
    private var targetWidth: Int = 0

    @Volatile
    private var targetHeight: Int = 0

    private var totalFrameCount = 0L
    private var discardedCount = 0L
    private var fedFrameCount = 0L

    /**
     * 设置目标编码尺寸
     *
     * 当相机输出分辨率与此不同时，帧管线会自动缩放 NV12 数据以匹配编码器。
     */
    fun setTargetSize(width: Int, height: Int) {
        targetWidth = width
        targetHeight = height
        Log.d(TAG, "目标编码尺寸: ${width}x${height}")
    }

    /**
     * 设置当前帧消费编码器
     *
     * @param encoder 编码器实例（RingBufferRecorder 或 ActiveRecorder），null 表示暂停
     */
    fun setEncoder(encoder: FrameConsumer?) {
        currentEncoder = encoder
        if (encoder != null) {
            Log.d(TAG, "编码器已连接: ${encoder::class.simpleName}, 目标=${targetWidth}x${targetHeight}")
        } else {
            Log.d(TAG, "编码器已断开（已喂 $fedFrameCount 帧，丢弃 $discardedCount 帧）")
            fedFrameCount = 0
            discardedCount = 0
        }
    }

    /**
     * ImageAnalysis.Analyzer 实现
     *
     * 每当 CameraX 产生一帧时调用。
     * 将 YUV_420_888 图像转为 NV12，必要时缩放到目标尺寸，送入当前编码器。
     *
     * 关键：CameraX setTargetResolution 只是提示，实际输出分辨率可能不同
     * （例如提示 1920×1080 但相机输出 2976×2976）。
     * 必须在管线层缩放到编码器配置的 targetWidth×targetHeight，
     * 否则 NV12 数据大小与编码器输入缓冲区不匹配会导致 BufferOverflowException。
     */
    override fun analyze(image: ImageProxy) {
        totalFrameCount++
        val encoder = currentEncoder
        if (encoder == null) {
            discardedCount++
            if (discardedCount % 15 == 1L) {
                Log.d(TAG, "无编码器，丢弃帧 (总接收: $totalFrameCount, 累计丢弃: $discardedCount)")
            }
            image.close()
            return
        }

        try {
            val proxyImage = image.image ?: run {
                discardedCount++
                image.close()
                return
            }
            var nv12 = YuvConverter.imageToNv12(proxyImage)
            // CameraX imageInfo.timestamp 返回纳秒，MediaCodec 需要微秒
            val timestampUs = image.imageInfo.timestamp / 1000

            var frameW = image.width
            var frameH = image.height

            // 当相机输出分辨率与编码器目标分辨率不匹配时，缩放 NV12 数据
            // 例如：相机输出 2976×2976，编码器配置 1920×1080
            if (targetWidth > 0 && targetHeight > 0
                && (frameW != targetWidth || frameH != targetHeight)
            ) {
                nv12 = YuvConverter.scaleNv12(nv12, frameW, frameH, targetWidth, targetHeight)
                frameW = targetWidth
                frameH = targetHeight
            }

            fedFrameCount++
            if (fedFrameCount % 150 == 0L) {
                Log.d(TAG, "帧 ${frameW}x${frameH} → NV12 ${nv12.size}B, ts=${timestampUs}μs, 已喂: $fedFrameCount")
            }
            encoder.feedFrame(nv12, timestampUs, frameW, frameH)
        } catch (e: Exception) {
            Log.w(TAG, "帧处理失败 (总接收: $totalFrameCount)", e)
        } finally {
            image.close()
        }
    }
}

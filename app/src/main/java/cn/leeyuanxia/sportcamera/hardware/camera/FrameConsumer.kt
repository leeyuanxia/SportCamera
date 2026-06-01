package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.Image

/**
 * YUV 图像格式转换工具
 *
 * 将 CameraX ImageAnalysis 输出的 YUV_420_888 格式
 * 转换为 MediaCodec 编码器所需的 NV12 格式。
 *
 * NV12 布局：[Y plane] [interleaved UV plane]
 * - Y plane: width * height 字节
 * - UV plane: width * (height/2) 字节（U V 交替排列）
 */
object YuvConverter {

    /**
     * 将 Image (YUV_420_888) 转换为 NV12 ByteArray
     *
     * @param image 来自 ImageAnalysis 的 YUV_420_888 图像
     * @return NV12 格式的字节数组
     */
    fun imageToNv12(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val width = image.width
        val height = image.height

        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv12 = ByteArray(width * height * 3 / 2)

        // 写入 Y 平面
        // 使用 limit() 而非 remaining() 做边界检查：
        // remaining() = limit - position，会被前一次 get() 推进的 position 影响，
        // 导致从约 50% 行开始 length 计算为 0，Y 数据丢失。
        val yBuffer = yPlane.buffer
        val yLimit = yBuffer.limit()
        for (row in 0 until height) {
            val srcPos = row * yRowStride
            val dstPos = row * width
            val length = width.coerceAtMost(yLimit - srcPos)
            if (length > 0) {
                yBuffer.position(srcPos)
                yBuffer.get(nv12, dstPos, length)
            }
        }

        // 写入交错的 UV 平面 (NV12: U V U V ...)
        // 同理使用 limit() 替代 remaining() 做越界保护，
        // 且 U/V 平面各自使用自己的 rowStride/pixelStride。
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uvOffset = width * height

        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvIndex = uvOffset + (row * width) + col * 2
                val uIdx = row * uRowStride + col * uPixelStride
                val vIdx = row * vRowStride + col * vPixelStride

                if (uIdx < uLimit) {
                    uBuffer.position(uIdx)
                    nv12[uvIndex] = uBuffer.get().toInt().toByte()
                }
                if (vIdx < vLimit) {
                    vBuffer.position(vIdx)
                    nv12[uvIndex + 1] = vBuffer.get().toInt().toByte()
                }
            }
        }

        return nv12
    }

    /**
     * 将 NV12 数据从源尺寸缩放到目标尺寸（最近邻采样，速度快）
     *
     * 用于处理相机输出分辨率与编码器配置不匹配的情况。
     * 例如：相机关输出 4K (3840×2160) 但编码器配置为 1080p (1080×1920)。
     *
     * NV12 布局：
     * - Y 平面: srcW × srcH 字节
     * - UV 平面: (srcW/2) × (srcH/2) × 2 字节（U V 交替）
     */
    fun scaleNv12(
        src: ByteArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
    ): ByteArray {
        if (srcW == dstW && srcH == dstH) return src

        val dst = ByteArray(dstW * dstH * 3 / 2)
        val xRatio = srcW.toDouble() / dstW
        val yRatio = srcH.toDouble() / dstH

        // 缩放 Y 平面（最近邻）
        for (y in 0 until dstH) {
            val srcY = (y * yRatio).toInt()
            val dstRowOff = y * dstW
            val srcRowOff = srcY * srcW
            for (x in 0 until dstW) {
                val srcX = (x * xRatio).toInt()
                dst[dstRowOff + x] = src[srcRowOff + srcX]
            }
        }

        // 缩放 UV 平面（NV12 交错，最近邻）
        val uvDstOff = dstW * dstH
        val uvSrcOff = srcW * srcH
        val uvXRatio = (srcW / 2.0) / (dstW / 2)
        val uvYRatio = (srcH / 2.0) / (dstH / 2)

        for (y in 0 until dstH / 2) {
            val srcY = (y * uvYRatio).toInt()
            val dstRowOff = uvDstOff + y * dstW
            val srcRowOff = uvSrcOff + srcY * srcW
            for (x in 0 until dstW / 2) {
                val srcX = (x * uvXRatio).toInt()
                val srcIdx = srcRowOff + srcX * 2
                val dstIdx = dstRowOff + x * 2
                dst[dstIdx] = src[srcIdx]         // U
                dst[dstIdx + 1] = src[srcIdx + 1] // V
            }
        }

        return dst
    }

    /**
     * NV12 90 度顺时针旋转（用于相机 landscape→portrait 转换）
     *
     * 当相机输出 3840×2160 但需要 2160×3840 时，简单缩放会破坏 NV12 布局。
     * 正确做法是旋转 90°，使得 Y 和 UV 平面正确重构。
     */
    fun rotateNv12_90(
        src: ByteArray, srcW: Int, srcH: Int,
    ): ByteArray {
        val dstW = srcH  // 旋转后宽=原高
        val dstH = srcW  // 旋转后高=原宽
        val dst = ByteArray(src.size)

        // Y 平面 90° 顺时针旋转
        for (y in 0 until srcH) {
            for (x in 0 until srcW) {
                dst[(x + 1) * dstW - 1 - y] = src[y * srcW + x]
            }
        }

        // UV 平面 90° 顺时针旋转（NV12 交错）
        val uvSrcOff = srcW * srcH
        val uvDstOff = dstW * dstH
        val uvSrcW = srcW / 2
        val uvSrcH = srcH / 2
        val uvDstW = dstW / 2  // = srcH/2

        for (y in 0 until uvSrcH) {
            for (x in 0 until uvSrcW) {
                val srcIdx = uvSrcOff + y * srcW + x * 2
                val dstX = uvSrcH - 1 - y
                val dstY = x
                val dstIdx = uvDstOff + dstY * dstW + dstX * 2
                dst[dstIdx] = src[srcIdx]       // U
                dst[dstIdx + 1] = src[srcIdx + 1] // V
            }
        }

        return dst
    }
}

/**
 * 编码器帧消费接口
 *
 * RingBufferRecorder 和 ActiveRecorder 均实现此接口，
 * 由 CameraFramePipeline 统一调用。
 */
interface FrameConsumer {
    /**
     * 向编码器送入一帧 YUV 数据
     *
     * @param yuvData NV12 格式的 YUV 数据
     * @param timestampUs 时间戳（微秒）
     * @param width 帧宽度
     * @param height 帧高度
     */
    fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int)
}

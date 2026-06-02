package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.Image
import android.util.Log

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

    private const val TAG = "YuvConverter"

    /**
     * 可复用的中间缓冲区，避免每帧分配大数组触发 GC 停顿
     * （仅在 analyze 单线程中访问，线程安全）
     */
    private var uBytesCache: ByteArray? = null
    private var vBytesCache: ByteArray? = null

    /**
     * 将 Image (YUV_420_888) 转换为 NV12 ByteArray
     *
     * 性能优化：
     * - Y 平面：按行批量 get()，无 padding 时整块复制
     * - UV 平面：先批量 ByteBuffer → ByteArray（仅 2 次 JNI 调用），
     *   再在 ByteArray 上做交错（纯内存操作），比逐字节 position()+get() 快数十倍
     *
     * @param image 来自 ImageAnalysis 的 YUV_420_888 图像
     * @return NV12 格式的字节数组
     */
    fun imageToNv12(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv12 = ByteArray(width * height * 3 / 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        // ---- Y 平面：按行批量复制 ----
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yLimit = yBuffer.limit()
        if (yRowStride == width) {
            // 快速路径：无 padding，整块复制
            yBuffer.position(0)
            yBuffer.get(nv12, 0, (width * height).coerceAtMost(yLimit))
        } else {
            // 慢速路径：逐行跳过 padding
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                val length = width.coerceAtMost(yLimit - srcPos)
                if (length > 0) {
                    yBuffer.position(srcPos)
                    yBuffer.get(nv12, row * width, length)
                }
            }
        }

        // ---- UV 平面：批量复制到 ByteArray 再交错 ----
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uvOffset = width * height

        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        // 复用缓冲区（仅当尺寸不够时才重新分配）
        val uBytes = uBytesCache?.takeIf { it.size >= uLimit }
            ?: ByteArray(uLimit).also { uBytesCache = it }
        val vBytes = vBytesCache?.takeIf { it.size >= vLimit }
            ?: ByteArray(vLimit).also { vBytesCache = it }

        // 批量复制：2 次 JNI 调用替代数百万次 position()+get()
        uBuffer.position(0)
        uBuffer.get(uBytes, 0, uLimit)
        vBuffer.position(0)
        vBuffer.get(vBytes, 0, vLimit)

        // 在 ByteArray 上做 UV 交错（纯内存操作，无 JNI 开销）
        for (row in 0 until uvHeight) {
            val uSrcRowOff = row * uRowStride
            val vSrcRowOff = row * vRowStride
            val dstRowOff = uvOffset + row * width
            for (col in 0 until uvWidth) {
                nv12[dstRowOff + col * 2] = uBytes[uSrcRowOff + col * uPixelStride]
                nv12[dstRowOff + col * 2 + 1] = vBytes[vSrcRowOff + col * vPixelStride]
            }
        }

        return nv12
    }

    /**
     * NV12 居中裁剪 + 缩放（保持目标宽高比，不拉伸变形）
     *
     * 当源图像宽高比与目标不一致时，先居中裁剪再缩放。
     * 例如：2976×2976 (1:1) → 1920×1080 (16:9)
     *   先居中裁剪为 2976×1674，再缩放到 1920×1080。
     *
     * 使用定点整数运算（16.16 格式）替代浮点，提高循环内性能。
     *
     * @param src   NV12 源数据
     * @param srcW  源宽度
     * @param srcH  源高度
     * @param dstW  目标宽度
     * @param dstH  目标高度
     * @return 缩放后的 NV12 数据
     */
    fun cropAndScaleNv12(
        src: ByteArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int,
    ): ByteArray {
        if (srcW == dstW && srcH == dstH) return src

        // 计算居中裁剪区域（保持目标宽高比）
        val srcAspect = srcW.toDouble() / srcH
        val dstAspect = dstW.toDouble() / dstH
        val cropW: Int
        val cropH: Int
        val cropX: Int
        val cropY: Int

        if (srcAspect > dstAspect) {
            // 源更宽 → 裁左右
            cropH = srcH
            cropW = (srcH * dstAspect).toInt()
            cropX = (srcW - cropW) / 2
            cropY = 0
        } else {
            // 源更高（或等比）→ 裁上下
            cropW = srcW
            cropH = (srcW / dstAspect).toInt()
            cropX = 0
            cropY = (srcH - cropH) / 2
        }

        Log.d(TAG, "裁剪缩放: ${srcW}×${srcH} → 裁剪 ${cropW}×${cropH}@(${cropX},${cropY}) → ${dstW}×${dstH}")

        val dst = ByteArray(dstW * dstH * 3 / 2)

        // 定点整数缩放系数（16.16 格式，避免浮点运算）
        val xStep = (cropW shl 16) / dstW
        val yStep = (cropH shl 16) / dstH

        // Y 平面：裁剪 + 缩放
        for (y in 0 until dstH) {
            val srcY = cropY + ((y * yStep) shr 16)
            val dstRowOff = y * dstW
            val srcRowOff = srcY * srcW + cropX
            for (x in 0 until dstW) {
                dst[dstRowOff + x] = src[srcRowOff + ((x * xStep) shr 16)]
            }
        }

        // UV 平面：裁剪 + 缩放（半分辨率）
        val uvDstOff = dstW * dstH
        val uvSrcOff = srcW * srcH
        val uvCropX = cropX / 2
        val uvCropY = cropY / 2
        val uvCropW = cropW / 2
        val uvCropH = cropH / 2
        val uvXStep = (uvCropW shl 16) / (dstW / 2)
        val uvYStep = (uvCropH shl 16) / (dstH / 2)

        for (y in 0 until dstH / 2) {
            val srcY = uvCropY + ((y * uvYStep) shr 16)
            val dstRowOff = uvDstOff + y * dstW
            val srcRowOff = uvSrcOff + srcY * srcW + uvCropX * 2
            for (x in 0 until dstW / 2) {
                val srcIdx = srcRowOff + ((x * uvXStep) shr 16) * 2
                val dstIdx = dstRowOff + x * 2
                dst[dstIdx] = src[srcIdx]
                dst[dstIdx + 1] = src[srcIdx + 1]
            }
        }

        return dst
    }

    /**
     * 将 NV12 数据从源尺寸缩放到目标尺寸（最近邻采样）
     *
     * 注意：此方法不保持宽高比，可能导致拉伸变形。
     * 优先使用 [cropAndScaleNv12] 以保持宽高比。
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

package cn.leeyuanxia.sportcamera.hardware.camera

import android.media.Image
import cn.leeyuanxia.sportcamera.util.DebugLog
import java.nio.ByteBuffer

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

    private var uBytesCache: ByteArray? = null
    private var vBytesCache: ByteArray? = null

    /**
     * 将 Image (YUV_420_888) 转换为 NV12 ByteArray
     */
    fun imageToNv12(image: Image, reuse: ByteArray? = null): ByteArray {
        val width = image.width
        val height = image.height
        val size = width * height * 3 / 2
        val nv12 = if (reuse != null && reuse.size == size) reuse else ByteArray(size)

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yLimit = yBuffer.limit()

        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv12, 0, (width * height).coerceAtMost(yLimit))
        } else {
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                val length = width.coerceAtMost(yLimit - srcPos)
                if (length > 0) {
                    yBuffer.position(srcPos)
                    yBuffer.get(nv12, row * width, length)
                }
            }
        }

        interleaveUv(image.planes[1], image.planes[2], width, height, nv12, width * height)
        return nv12
    }

    /**
     * 将 Image (YUV_420_888) 直接写入目标 ByteBuffer（零中间拷贝）
     *
     * YUV 转换结果直接写入编码器输入缓冲区，省去 ~12MB 的 ByteArray 中转。
     * 4K@60fps 下可减少 3-4ms/帧，将帧率从 50fps 提升到 60fps。
     */
    fun imageToNv12Direct(image: Image, dst: ByteBuffer) {
        val width = image.width
        val height = image.height

        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yLimit = yBuffer.limit()

        if (yRowStride == width) {
            yBuffer.position(0)
            val ySize = (width * height).coerceAtMost(yLimit)
            if (dst.hasArray()) {
                yBuffer.get(dst.array(), dst.arrayOffset() + dst.position(), ySize)
                dst.position(dst.position() + ySize)
            } else {
                val chunk = ByteArray(minOf(8192, ySize))
                var remaining = ySize
                while (remaining > 0) {
                    val len = minOf(chunk.size, remaining)
                    yBuffer.get(chunk, 0, len)
                    dst.put(chunk, 0, len)
                    remaining -= len
                }
            }
        } else {
            for (row in 0 until height) {
                val srcPos = row * yRowStride
                val length = width.coerceAtMost(yLimit - srcPos)
                if (length > 0) {
                    yBuffer.position(srcPos)
                    val rowBuf = ByteArray(length)
                    yBuffer.get(rowBuf)
                    dst.put(rowBuf)
                }
            }
        }

        interleaveUvToBuffer(image.planes[1], image.planes[2], width, height, dst)
    }

    /** UV 交错写入 ByteArray */
    private fun interleaveUv(
        uPlane: Image.Plane, vPlane: Image.Plane,
        width: Int, height: Int, dst: ByteArray, dstOffset: Int,
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        val uBytes = uBytesCache?.takeIf { it.size >= uLimit }
            ?: ByteArray(uLimit).also { uBytesCache = it }
        val vBytes = vBytesCache?.takeIf { it.size >= vLimit }
            ?: ByteArray(vLimit).also { vBytesCache = it }

        uBuffer.position(0); uBuffer.get(uBytes, 0, uLimit)
        vBuffer.position(0); vBuffer.get(vBytes, 0, vLimit)

        for (row in 0 until uvHeight) {
            val uSrcRowOff = row * uRowStride
            val vSrcRowOff = row * vRowStride
            val dstRowOff = dstOffset + row * width
            for (col in 0 until uvWidth) {
                dst[dstRowOff + col * 2] = uBytes[uSrcRowOff + col * uPixelStride]
                dst[dstRowOff + col * 2 + 1] = vBytes[vSrcRowOff + col * vPixelStride]
            }
        }
    }

    /** UV 交错写入 ByteBuffer（零拷贝路径） */
    private fun interleaveUvToBuffer(
        uPlane: Image.Plane, vPlane: Image.Plane,
        width: Int, height: Int, dst: ByteBuffer,
    ) {
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val uvHeight = height / 2
        val uvWidth = width / 2
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        val uBytes = uBytesCache?.takeIf { it.size >= uLimit }
            ?: ByteArray(uLimit).also { uBytesCache = it }
        val vBytes = vBytesCache?.takeIf { it.size >= vLimit }
            ?: ByteArray(vLimit).also { vBytesCache = it }

        uBuffer.position(0); uBuffer.get(uBytes, 0, uLimit)
        vBuffer.position(0); vBuffer.get(vBytes, 0, vLimit)

        val rowBuf = ByteArray(width)
        for (row in 0 until uvHeight) {
            val uSrcRowOff = row * uRowStride
            val vSrcRowOff = row * vRowStride
            for (col in 0 until uvWidth) {
                rowBuf[col * 2] = uBytes[uSrcRowOff + col * uPixelStride]
                rowBuf[col * 2 + 1] = vBytes[vSrcRowOff + col * vPixelStride]
            }
            dst.put(rowBuf, 0, width)
        }
    }

    /**
     * NV12 居中裁剪 + 缩放（双线性插值）
     *
     * 使用 16.16 定点双线性插值替代最近邻，减少缩放时的锯齿和细节丢失。
     * 性能开销约每帧额外 2-3ms（非 Surface 模式路径可接受）。
     */
    fun cropAndScaleNv12(
        src: ByteArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int, reuse: ByteArray? = null,
    ): ByteArray {
        if (srcW == dstW && srcH == dstH) return src

        val srcAspect = srcW.toDouble() / srcH
        val dstAspect = dstW.toDouble() / dstH
        val cropW: Int; val cropH: Int; val cropX: Int; val cropY: Int
        if (srcAspect > dstAspect) {
            cropH = srcH; cropW = (srcH * dstAspect).toInt()
            cropX = (srcW - cropW) / 2; cropY = 0
        } else {
            cropW = srcW; cropH = (srcW / dstAspect).toInt()
            cropX = 0; cropY = (srcH - cropH) / 2
        }

        val dstSize = dstW * dstH * 3 / 2
        val dst = if (reuse != null && reuse.size == dstSize) reuse else ByteArray(dstSize)
        val xStep = (cropW shl 16) / dstW
        val yStep = (cropH shl 16) / dstH
        val cropYBound = cropY + cropH - 1
        val cropXBound = cropX + cropW - 1

        // Y 平面 — 双线性插值（16.16 定点）
        for (y in 0 until dstH) {
            val srcYFixed = y * yStep
            val srcY0 = cropY + (srcYFixed shr 16)
            val srcY1 = if (srcY0 < cropYBound) srcY0 + 1 else cropYBound
            val fy = (srcYFixed shr 8) and 0xFF
            val rowOff0 = srcY0 * srcW + cropX
            val rowOff1 = srcY1 * srcW + cropX
            for (x in 0 until dstW) {
                val srcXFixed = x * xStep
                val srcX0 = cropX + (srcXFixed shr 16)
                val srcX1 = if (srcX0 < cropXBound) srcX0 + 1 else cropXBound
                val fx = (srcXFixed shr 8) and 0xFF
                val v00 = src[rowOff0 + (srcX0 - cropX)].toInt() and 0xFF
                val v01 = src[rowOff0 + (srcX1 - cropX)].toInt() and 0xFF
                val v10 = src[rowOff1 + (srcX0 - cropX)].toInt() and 0xFF
                val v11 = src[rowOff1 + (srcX1 - cropX)].toInt() and 0xFF
                val top = v00 + ((v01 - v00) * fx shr 8)
                val bot = v10 + ((v11 - v10) * fx shr 8)
                dst[y * dstW + x] = (top + ((bot - top) * fy shr 8)).toByte()
            }
        }

        // UV 平面 — 双线性插值（NV12 交织格式）
        val uvDstOff = dstW * dstH; val uvSrcOff = srcW * srcH
        val uvCropX = cropX / 2; val uvCropY = cropY / 2
        val uvCropW = cropW / 2; val uvCropH = cropH / 2
        val uvXStep = (uvCropW shl 16) / (dstW / 2)
        val uvYStep = (uvCropH shl 16) / (dstH / 2)
        val uvCropYBound = uvCropY + uvCropH - 1
        val uvCropXBound = uvCropX + uvCropW - 1

        for (y in 0 until dstH / 2) {
            val srcYFixed = y * uvYStep
            val srcY0 = uvCropY + (srcYFixed shr 16)
            val srcY1 = if (srcY0 < uvCropYBound) srcY0 + 1 else uvCropYBound
            val fy = (srcYFixed shr 8) and 0xFF
            val rowOff0 = uvSrcOff + srcY0 * srcW + uvCropX * 2
            val rowOff1 = uvSrcOff + srcY1 * srcW + uvCropX * 2
            val dstRowOff = uvDstOff + y * dstW
            for (x in 0 until dstW / 2) {
                val srcXFixed = x * uvXStep
                val srcX0 = uvCropX + (srcXFixed shr 16)
                val srcX1 = if (srcX0 < uvCropXBound) srcX0 + 1 else uvCropXBound
                val fx = (srcXFixed shr 8) and 0xFF
                val dx = (srcX0 - uvCropX) * 2
                val dx1 = (srcX1 - uvCropX) * 2
                // U 分量
                val u00 = src[rowOff0 + dx].toInt() and 0xFF
                val u01 = src[rowOff0 + dx1].toInt() and 0xFF
                val u10 = src[rowOff1 + dx].toInt() and 0xFF
                val u11 = src[rowOff1 + dx1].toInt() and 0xFF
                val uTop = u00 + ((u01 - u00) * fx shr 8)
                val uBot = u10 + ((u11 - u10) * fx shr 8)
                dst[dstRowOff + x * 2] = (uTop + ((uBot - uTop) * fy shr 8)).toByte()
                // V 分量
                val v00 = src[rowOff0 + dx + 1].toInt() and 0xFF
                val v01 = src[rowOff0 + dx1 + 1].toInt() and 0xFF
                val v10 = src[rowOff1 + dx + 1].toInt() and 0xFF
                val v11 = src[rowOff1 + dx1 + 1].toInt() and 0xFF
                val vTop = v00 + ((v01 - v00) * fx shr 8)
                val vBot = v10 + ((v11 - v10) * fx shr 8)
                dst[dstRowOff + x * 2 + 1] = (vTop + ((vBot - vTop) * fy shr 8)).toByte()
            }
        }
        return dst
    }
}

/**
 * 编码器帧消费接口
 */
interface FrameConsumer {
    /** 向编码器送入一帧 YUV 数据（ByteArray 版本） */
    fun feedFrame(yuvData: ByteArray, timestampUs: Long, width: Int, height: Int)

    /**
     * 向编码器送入一帧 YUV 数据（零拷贝版本）
     *
     * YUV 转换直接写入编码器输入缓冲区，省去 ~12MB 中转。
     * 4K 高分辨率下使用此路径可显著提升帧率。
     *
     * @return true=成功送入，false=无可用输入缓冲区
     */
    fun feedFrameDirect(image: Image, timestampUs: Long, width: Int, height: Int): Boolean = false
}

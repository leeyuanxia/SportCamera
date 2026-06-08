package cn.leeyuanxia.sportcamera.hardware.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import cn.leeyuanxia.sportcamera.util.DebugLog
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态照片存储管理器
 *
 * 严格遵循 Android 官方 Motion Photo format 1.0 规范：
 * https://developer.android.google.cn/media/platform/motion-photo-format
 *
 * - 文件格式：JPEG 图像 + 末尾追加 MP4 视频
 * - XMP 元数据使用 Camera 命名空间 + Container:Directory 描述布局
 * - 通过 MediaStore.Images 保存为 image/jpeg
 *
 * 文件结构：
 * ```
 * [FF D8 SOI]
 * [FF E1 APP1: "http://ns.adobe.com/xap/1.0/\0" + XMP XML]
 * [原始 JPEG 剩余数据]
 * [MP4 视频数据]
 * ```
 */
class MotionPhotoStorageManager(private val context: Context) {

    companion object {
        private const val DIR_NAME = "SportCamera"
        private const val FILE_PREFIX = "MOTION_"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        private const val TAG = "MotionPhotoStorage"

        /** XMP 命名空间 URI */
        private const val NS_CAMERA = "http://ns.google.com/photos/1.0/camera/"
        private const val NS_CONTAINER = "http://ns.google.com/photos/1.0/container/"
        private const val NS_ITEM = "http://ns.google.com/photos/1.0/container/item/"
    }

    /**
     * 保存动态照片文件
     *
     * @param jpegBytes JPEG 图片字节数据
     * @param mp4Bytes MP4 视频字节数据
     * @param width 图片宽度
     * @param height 图片高度
     * @return 文件 Uri 字符串
     */
    fun saveMotionPhoto(
        jpegBytes: ByteArray,
        mp4Bytes: ByteArray,
        width: Int,
        height: Int,
    ): String {
        // 文件名包含 MP 标识，符合 Motion Photo format 1.0 命名规范
        // 规范要求：^([^\s/\\]*MP)\.(JPG|jpg|...)
        val fileName = "${FILE_PREFIX}${DATE_FORMAT.format(Date())}.MP.jpg"

        // 1. 通过 MediaStore Images 创建文件条目
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.WIDTH, width)
            put(MediaStore.Images.Media.HEIGHT, height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$DIR_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: throw IllegalStateException("无法通过 MediaStore 创建动态照片文件")

        DebugLog.d(TAG, "动态照片 MediaStore 条目已创建: $fileName, uri=$uri")

        try {
            // 2. 构建 XMP APP1 段并注入 JPEG
            val xmpXml = buildXmpString(mp4Bytes.size)
            val app1Segment = buildXmpApp1Segment(xmpXml)

            // 3. 组装最终 JPEG：SOI(2B) + APP1(XMP) + 原始 JPEG 剩余数据
            val jpegWithXmp = ByteArray(jpegBytes.size + app1Segment.size)
            // SOI 标记（FF D8），占 2 字节
            System.arraycopy(jpegBytes, 0, jpegWithXmp, 0, 2)
            // APP1 XMP 段
            System.arraycopy(app1Segment, 0, jpegWithXmp, 2, app1Segment.size)
            // 原始 JPEG 剩余数据（跳过 SOI 的 2 字节）
            System.arraycopy(jpegBytes, 2, jpegWithXmp, 2 + app1Segment.size, jpegBytes.size - 2)

            // 4. 写入文件：JPEG(XMP) + MP4
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("无法打开 MediaStore 文件描述符")

            FileOutputStream(pfd.fileDescriptor).use { fos ->
                fos.write(jpegWithXmp)
                fos.write(mp4Bytes)
                fos.flush()
            }
            pfd.close()

            val totalSize = jpegWithXmp.size + mp4Bytes.size
            DebugLog.d(TAG, "动态照片已写入: JPEG=${jpegWithXmp.size}B(含XMP), MP4=${mp4Bytes.size}B, 总计=${totalSize}B")
            DebugLog.d(TAG, "XMP 内容:\n$xmpXml")

            // 5. 清除 IS_PENDING 标志，通知系统扫描
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }.also { updateValues ->
                    context.contentResolver.update(uri, updateValues, null, null)
                }
            }
            context.contentResolver.notifyChange(uri, null)

            DebugLog.d(TAG, "动态照片已保存到相册: $uri")
            return uri.toString()

        } catch (e: Exception) {
            DebugLog.e(TAG, "动态照片保存失败", e)
            try { context.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * 构建 Motion Photo XMP 元数据
     *
     * 同时包含新版和旧版属性，确保最大兼容性：
     * - 新版 Container:Directory（Android Motion Photo format 1.0）
     * - 旧版 MicroVideoOffset（小米、多数国产相册依赖此属性）
     *
     * 命名空间前缀用 GCamera（Google Pixel 实际输出的前缀）
     */
    private fun buildXmpString(mp4Size: Int): String {
        return buildString {
            append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
            append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")

            append("  <rdf:Description rdf:about=\"\"\n")
            append("    xmlns:Camera=\"${NS_CAMERA}\"\n")
            append("    xmlns:Container=\"${NS_CONTAINER}\"\n")
            append("    xmlns:Item=\"${NS_ITEM}\"\n")
            append("    Camera:MotionPhoto=\"1\"\n")
            append("    Camera:MotionPhotoVersion=\"1\"\n")
            append("    Camera:MotionPhotoPresentationTimestampUs=\"-1\">\n")

            // Container:Directory（新版规范）
            append("   <Container:Directory>\n")
            append("    <rdf:Seq>\n")

            // Item 1: 主图片
            append("     <rdf:li rdf:parseType=\"Resource\">\n")
            append("      <Item:Mime>image/jpeg</Item:Mime>\n")
            append("      <Item:Semantic>Primary</Item:Semantic>\n")
            append("      <Item:Length>0</Item:Length>\n")
            append("      <Item:Padding>0</Item:Padding>\n")
            append("     </rdf:li>\n")

            // Item 2: MP4 视频
            append("     <rdf:li rdf:parseType=\"Resource\">\n")
            append("      <Item:Mime>video/mp4</Item:Mime>\n")
            append("      <Item:Semantic>MotionPhoto</Item:Semantic>\n")
            append("      <Item:Length>${mp4Size}</Item:Length>\n")
            append("      <Item:Padding>0</Item:Padding>\n")
            append("     </rdf:li>\n")

            append("    </rdf:Seq>\n")
            append("   </Container:Directory>\n")

            append("  </rdf:Description>\n")
            append(" </rdf:RDF>\n")
            append("</x:xmpmeta>\n")
            append("<?xpacket end=\"w\"?>")
        }
    }

    /**
     * 构建 JPEG XMP APP1 段
     *
     * 严格遵循 Adobe XMP 规范 Part 3：
     * [FF E1] [2字节长度(大端)] ["http://ns.adobe.com/xap/1.0/" + 0x00] [XMP XML]
     *
     * 长度字段 = 命名空间字符串字节数 + null终止符 + XMP XML字节数 + 2（长度字段自身）
     */
    private fun buildXmpApp1Segment(xmpXml: String): ByteArray {
        val namespaceStr = "http://ns.adobe.com/xap/1.0/"
        val nsBytes = namespaceStr.toByteArray(Charsets.UTF_8)
        val xmpBytes = xmpXml.toByteArray(Charsets.UTF_8)

        // APP1 标记(2B) + 长度(2B) + 命名空间(29B) + null终止符(1B) + XMP数据
        val segment = ByteArray(4 + nsBytes.size + 1 + xmpBytes.size)

        // APP1 标记: FF E1
        segment[0] = 0xFF.toByte()
        segment[1] = 0xE1.toByte()

        // 长度字段 = 命名空间字节数 + null + XMP字节数 + 2（长度字段自身）
        val len = nsBytes.size + 1 + xmpBytes.size + 2
        segment[2] = (len shr 8).toByte()
        segment[3] = (len and 0xFF).toByte()

        // XMP 命名空间字符串
        var offset = 4
        System.arraycopy(nsBytes, 0, segment, offset, nsBytes.size)
        offset += nsBytes.size

        // null 终止符（关键！必须是 0x00 而非空格 0x20）
        segment[offset] = 0x00
        offset += 1

        // XMP XML 数据
        System.arraycopy(xmpBytes, 0, segment, offset, xmpBytes.size)

        return segment
    }
}

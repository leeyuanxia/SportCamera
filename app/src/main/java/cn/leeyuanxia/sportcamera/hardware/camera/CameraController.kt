package cn.leeyuanxia.sportcamera.hardware.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import cn.leeyuanxia.sportcamera.util.DebugLog
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/**
 * Camera2 摄像头控制器
 *
 * 负责摄像头预览绑定、镜头切换、ImageReader 帧输出。
 *
 * 使用纯 Camera2 API（不依赖 CameraX）：
 * - SurfaceTexture → 预览显示
 * - ImageReader (YUV_420_888) → 帧数据送入编码管线
 * - 编码器 InputSurface → 4K@60fps 零拷贝路径
 */
class CameraController(private val context: Context) {

    private companion object {
        const val TAG = "CameraController"
    }

    private val _currentLens = MutableStateFlow(CameraLens.DEFAULT)

    /** 互斥锁：防止多个协程并发调用 bindPreview / switchLens / rebindWithProfile 导致 Surface 冲突 */
    private val cameraMutex = Mutex()
    val currentLens: StateFlow<CameraLens> = _currentLens.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<CameraLens>>(emptyList())
    val availableLenses: StateFlow<List<CameraLens>> = _availableLenses.asStateFlow()

    /** 摄像头硬件支持的帧率集合（从 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 提取） */
    private val _supportedFps = MutableStateFlow<Set<Int>>(setOf(30))
    val supportedFps: StateFlow<Set<Int>> = _supportedFps.asStateFlow()

    /** 记住上次绑定的参数，用于镜头切换时重新绑定 */
    private var lastTextureView: TextureView? = null
    private var lastFramePipeline: CameraFramePipeline? = null
    private var lastEncoderWidth: Int = 1280
    private var lastEncoderHeight: Int = 720
    private var lastOrientation: RecordOrientation = RecordOrientation.LANDSCAPE
    private var lastFps: Int = 30
    private var lastAppliedFps: Int = 30
    private var lastRebindLens: CameraLens? = null

    /** 初始绑定时是否设置了 AE FPS Range — rebuild 时保持一致 */
    private var fpsRangeWasSetOnBind: Boolean = false

    // ---- Camera2 会话状态 ----

    /** Camera2 设备 */
    private var camera2Device: CameraDevice? = null

    /** Camera2 捕获会话 */
    private var camera2Session: CameraCaptureSession? = null

    /** Camera2 捕获请求构建器（用于缩放/EIS/FPS 更新时重建请求） */
    private var camera2RequestBuilder: CaptureRequest.Builder? = null

    /** Camera2 会话的输出 Surface 列表（用于重建请求时重新添加 target） */
    private var camera2Surfaces: List<Surface> = emptyList()

    /** 当前会话的 SCALER_CROP_REGION（创建时保存，rebuildCaptureRequest 时复用） */
    private var currentCropRegion: android.graphics.Rect? = null

    /** Camera2 专用 HandlerThread */
    private var camera2Thread: HandlerThread? = null

    /** Camera2 专用 Handler */
    private var camera2Handler: Handler? = null

    /** ImageReader（帧数据输出，非 Surface 模式时使用） */
    private var imageReader: ImageReader? = null

    /** 当前是否处于 Camera2 Surface 模式（4K@60fps 零拷贝路径） */
    @Volatile
    var isSurfaceMode: Boolean = false
        private set

    /** 当前是否处于物理相机直连模式（超广角/长焦） */
    @Volatile
    var isPhysicalCameraMode: Boolean = false
        private set

    /** 物理相机模式 StateFlow（供 ViewModel/UI 观测） */
    private val _isPhysicalCameraMode = MutableStateFlow(false)
    val isPhysicalCameraModeState: StateFlow<Boolean> = _isPhysicalCameraMode.asStateFlow()

    /** 物理相机 ID 映射：ULTRA_WIDE → "3", TELEPHOTO → "4" 等（从 Camera2 检测获取） */
    private val physicalCameraIds = mutableMapOf<CameraLens, String>()

    /** 只读视图：外部查询物理相机 ID 映射 */
    val physicalCameraIdsMap: Map<CameraLens, String> get() = physicalCameraIds.toMap()

    // ---- 缩放控制 ----

    /** 当前缩放倍率 */
    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    /** 最大缩放倍率（从 CameraCharacteristics 获取） */
    private val _maxZoomRatio = MutableStateFlow(1.0f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    /** 最小缩放倍率（超广角设备 < 1.0 如 0.5x；无超广角设备 == 1.0） */
    private val _minZoomRatio = MutableStateFlow(1.0f)
    val minZoomRatio: StateFlow<Float> = _minZoomRatio.asStateFlow()

    // ---- 视频防抖 (EIS) ----

    /** 视频防抖是否开启（由 ViewModel 设置，CameraController 负责应用到硬件）
     *  默认关闭以获取最大 FOV — EIS 会裁切传感器 ~10-15% 作为防抖余量 */
    @Volatile
    var videoStabilizationEnabled: Boolean = false
        private set

    /** 当前摄像头是否支持 EIS */
    private val _eisSupported = MutableStateFlow(false)
    val eisSupported: StateFlow<Boolean> = _eisSupported.asStateFlow()

    /** 当前 EIS 模式值（CaptureRequest 常量） */
    private val currentVideoStabilizationMode: Int
        get() = if (videoStabilizationEnabled) {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        } else {
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        }

    /**
     * 初始化并检测可用镜头
     *
     * 纯 Camera2 API 实现，不依赖 CameraX。
     */
    suspend fun initialize() {
        DebugLog.i(TAG, "initialize() 被调用")
        if (_availableLenses.value.isEmpty()) {
            try {
                detectAvailableLenses()
            } catch (e: Exception) {
                DebugLog.e(TAG, "镜头检测失败: ${e.message}", e)
                _availableLenses.value = listOf(CameraLens.WIDE)
            }
        } else {
            DebugLog.i(TAG, "镜头已检测过，跳过: ${_availableLenses.value}")
        }
    }

    /**
     * 检测可用镜头类型
     *
     * 检测四种镜头：
     * - WIDE：标准后摄（始终可用）
     * - FRONT：前置摄像头（大部分设备有）
     * - ULTRA_WIDE：超广角后摄（部分设备可用）
     * - TELEPHOTO：长焦后摄（部分设备可用）
     *
     * 检测策略：
     * 1. Camera2 cameraIdList 标准检测（含物理子相机）
     * 2. 直接探测 camera ID 0~9（绕过厂商 cameraIdList 过滤）
     */
    private fun detectAvailableLenses() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableSetOf<CameraLens>()
        lenses.add(CameraLens.WIDE) // 标准后摄始终可用

        DebugLog.i(TAG, "===== 开始检测镜头 =====")

        // 收集所有可探测的相机信息
        val allCameraChars = mutableMapOf<String, CameraCharacteristics>()

        // 第一阶段：从 cameraIdList 收集
        val visibleIds = cameraManager.cameraIdList.toList()
        DebugLog.i(TAG, "cameraIdList（系统可见）: $visibleIds")
        for (cameraId in visibleIds) {
            try {
                allCameraChars[cameraId] = cameraManager.getCameraCharacteristics(cameraId)
            } catch (_: Exception) {}
        }

        // 第二阶段：探测隐藏 camera ID 0~9
        for (id in 0..9) {
            val idStr = id.toString()
            if (idStr in allCameraChars) continue
            try {
                allCameraChars[idStr] = cameraManager.getCameraCharacteristics(idStr)
            } catch (_: Exception) {}
        }

        // 分析所有相机
        for ((cameraId, chars) in allCameraChars) {
            logCameraInfo("检测", cameraId, chars)

            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            // 前置摄像头
            if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
                lenses.add(CameraLens.FRONT)
                DebugLog.i(TAG, "检测到前置摄像头: $cameraId")
            }

            // 后置超广角（焦距 < 4mm）和长焦（焦距 > 7mm）
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                checkFocalLengthForUltraWide(chars, lenses, cameraId)
                checkFocalLengthForTelephoto(chars, lenses, cameraId)

                // 检查物理子相机（Android 9+）
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val physicalIds = chars.physicalCameraIds
                    if (physicalIds.isNotEmpty()) {
                        DebugLog.i(TAG, "相机 $cameraId 的物理子相机: $physicalIds")
                    }
                    for (physicalId in physicalIds) {
                        try {
                            val physicalChars = cameraManager.getCameraCharacteristics(physicalId)
                            logCameraInfo("物理子相机", physicalId, physicalChars)
                            checkFocalLengthForUltraWide(physicalChars, lenses, physicalId)
                            checkFocalLengthForTelephoto(physicalChars, lenses, physicalId)
                        } catch (e: Exception) {
                            DebugLog.w(
                                TAG, "无法查询物理子相机 $physicalId: ${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                }
            }
        }

        _availableLenses.value = lenses.toList()
        DebugLog.i(TAG, "===== 检测完成，可用镜头: ${lenses.toList()} =====")
    }

    /**
     * 记录单个相机的详细信息（诊断用）
     */
    private fun logCameraInfo(
        source: String,
        cameraId: String,
        chars: CameraCharacteristics,
    ) {
        val facing = chars.get(CameraCharacteristics.LENS_FACING)
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val facingLabel = when (facing) {
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN($facing)"
        }
        DebugLog.i(
            TAG, "[$source] 相机 $cameraId: facing=$facingLabel, focalLengths=${focalLengths?.toList()}"
        )
    }

    /**
     * 检查焦距是否属于超广角镜头（< 4mm），并记录物理相机 ID
     */
    private fun checkFocalLengthForUltraWide(
        chars: CameraCharacteristics,
        lenses: MutableSet<CameraLens>,
        cameraId: String? = null,
    ) {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return
        if (focalLengths.any { it < 4.0f }) {
            lenses.add(CameraLens.ULTRA_WIDE)
            if (cameraId != null) {
                physicalCameraIds[CameraLens.ULTRA_WIDE] = cameraId
                DebugLog.d(TAG, "记录超广角物理相机: cameraId=$cameraId, focalLength=${focalLengths.first()}")
            }
        }
    }

    /**
     * 检查焦距是否属于长焦镜头（> 7mm），并记录物理相机 ID
     */
    private fun checkFocalLengthForTelephoto(
        chars: CameraCharacteristics,
        lenses: MutableSet<CameraLens>,
        cameraId: String? = null,
    ) {
        val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS) ?: return
        if (focalLengths.any { it > 7.0f }) {
            lenses.add(CameraLens.TELEPHOTO)
            if (cameraId != null) {
                physicalCameraIds[CameraLens.TELEPHOTO] = cameraId
                DebugLog.d(TAG, "记录长焦物理相机: cameraId=$cameraId, focalLength=${focalLengths.first()}")
            }
        }
    }

    /**
     * 绑定摄像头预览 + 帧分析到 TextureView 和编码管线（线程安全）
     *
     * 纯 Camera2 实现：
     * 1. 打开 CameraDevice
     * 2. 创建 SurfaceTexture（预览）+ ImageReader（帧捕获）
     * 3. 创建 CaptureSession
     * 4. 构建 CaptureRequest 并开始预览
     */
    suspend fun bindPreview(
        textureView: TextureView,
        lens: CameraLens = _currentLens.value,
        orientation: RecordOrientation = RecordOrientation.PORTRAIT,
        framePipeline: CameraFramePipeline? = null,
        encoderWidth: Int = 1280,
        encoderHeight: Int = 720,
        fps: Int = 30,
    ) = cameraMutex.withLock {
        bindPreviewInternal(textureView, lens, orientation, framePipeline, encoderWidth, encoderHeight, fps)
    }

    /**
     * bindPreview 的内部实现（不加锁，调用者必须持有 cameraMutex）
     */
    private suspend fun bindPreviewInternal(
        textureView: TextureView,
        lens: CameraLens = _currentLens.value,
        orientation: RecordOrientation = RecordOrientation.PORTRAIT,
        framePipeline: CameraFramePipeline? = null,
        encoderWidth: Int = 1280,
        encoderHeight: Int = 720,
        fps: Int = 30,
    ) {
        // 记住绑定参数，用于镜头切换
        lastTextureView = textureView
        lastFramePipeline = framePipeline
        lastEncoderWidth = encoderWidth
        lastEncoderHeight = encoderHeight
        lastOrientation = orientation
        lastFps = fps

        // 1. 清理旧会话
        stopCamera2Session()

        // 2. 启动 Camera2 HandlerThread
        val thread = HandlerThread("Camera2Preview").apply { start() }
        val handler = Handler(thread.looper)
        camera2Thread = thread
        camera2Handler = handler

        // 3. 解析 camera ID
        val cameraId = resolveCameraId(lens)
        DebugLog.d(TAG, "bindPreview: lens=${lens.name}, cameraId=$cameraId, fps=$fps, encoder=${encoderWidth}x${encoderHeight}")

        // 4. 查询传感器信息
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)

        // 5. 获取传感器信息
        val maxSensorSize = selectMaxSensorResolution(cameraId)
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = maxSensorSize.width.toFloat() / maxSensorSize.height

        // 6. 预览分辨率 — ~5MP 固定，保证流畅和画质，同一宽高比避免 HAL 拉伸
        val previewSize = selectPreviewResolution(cameraId, sensorAspect)
        val previewSurface = configurePreviewSurface(textureView, previewSize.width, previewSize.height)
            ?: throw IllegalStateException("无法配置预览 Surface，请确保 TextureView 已就绪")
        DebugLog.d(TAG, "预览: ${previewSize.width}x${previewSize.height}, " +
            "ImageReader: ${maxSensorSize.width}x${maxSensorSize.height}, " +
            "编码器目标: ${encoderWidth}x${encoderHeight}")

        // 7. 创建 ImageReader（帧捕获）— 最大分辨率保证最高画质和最大 FOV
        //    帧管线在软件层通过 cropAndScaleNv12 裁剪到编码器目标分辨率
        val reader = if (framePipeline != null) {
            val ir = ImageReader.newInstance(maxSensorSize.width, maxSensorSize.height, ImageFormat.YUV_420_888, 2)
            ir.setOnImageAvailableListener(framePipeline, handler)
            imageReader = ir
            DebugLog.d(TAG, "ImageReader 创建: ${maxSensorSize.width}x${maxSensorSize.height}")
            ir
        } else {
            imageReader = null
            null
        }

        // 7. 解析 FPS Range — 设最宽范围确保帧率，同时给 HAL 灵活选择传感器模式
        val (actualFps, _) = resolveActualFpsFromCameraId(cameraId, fps)
        val previewRange = resolveSurfaceFpsRange(cameraId, fps)
        lastAppliedFps = actualFps
        fpsRangeWasSetOnBind = true
        DebugLog.d(TAG, "请求帧率: ${fps}fps, 实际帧率: ${actualFps}fps, 宽Range: [${previewRange.lower},${previewRange.upper}]")

        // 8. 打开 Camera2 设备
        val device = openCamera2Device(cameraManager, cameraId, handler)
        camera2Device = device

        // 9. 创建 CaptureSession
        val surfaces = mutableListOf(previewSurface)
        if (reader != null) surfaces.add(reader.surface)
        camera2Surfaces = surfaces
        val session = createCaptureSession(device, surfaces, handler)
        camera2Session = session

        // 10. 构建 CaptureRequest — 使用 TEMPLATE_PREVIEW 获得最宽 FOV
        //    TEMPLATE_RECORD 会应用内部裁切优化，导致 FOV 变窄
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            surfaces.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewRange)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 视频防抖 (EIS)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, currentVideoStabilizationMode)
            // SCALER_CROP_REGION 设为传感器全幅 — 获得最大 FOV
            // 输出分辨率和 activeArray 宽高比一致，HAL 不会拉伸
            if (activeArray != null) {
                set(CaptureRequest.SCALER_CROP_REGION, activeArray)
                currentCropRegion = activeArray
                DebugLog.d(TAG, "SCALER_CROP_REGION: 全幅 ${activeArray.width()}x${activeArray.height()}")
            }
        }
        camera2RequestBuilder = requestBuilder

        session.setRepeatingRequest(requestBuilder.build(), null, handler)

        // 11. 初始化缩放范围
        initZoomFromCameraCharacteristics(cameraId)

        // 11.5 应用 TextureView 变换矩阵 — 修正预览旋转和宽高比
        applyPreviewTransform(textureView, cameraId, previewSize.width, previewSize.height)

        // 12. 通知 framePipeline 实际帧率
        if (framePipeline != null) {
            framePipeline.setCameraFps(lastAppliedFps)
        }

        // 13. 重置模式标志
        isSurfaceMode = false
        isPhysicalCameraMode = false
        _isPhysicalCameraMode.value = false

        _currentLens.value = lens
        DebugLog.d(TAG, "Camera2 预览绑定完成: lens=${lens.name}, cameraId=$cameraId, ${surfaces.size} 个 Surface")
    }

    /**
     * 切换镜头 — 使用上次绑定的参数重新绑定
     *
     * 所有镜头统一使用 Camera2 API：
     * - WIDE/FRONT：通过 resolveCameraId 找到逻辑相机 ID，bindPreview 绑定
     * - ULTRA_WIDE/TELEPHOTO：优先逻辑相机 + CONTROL_ZOOM_RATIO，降级到物理相机直连
     *
     * @return 切换后的镜头，null 表示无法切换
     */
    suspend fun switchLens(lens: CameraLens): CameraLens? = cameraMutex.withLock {
        // Surface 模式下无法简单切换
        if (isSurfaceMode) {
            DebugLog.w(TAG, "Surface 模式下暂不支持镜头切换")
            return null
        }

        // 停止物理相机模式（如果正在运行）
        if (isPhysicalCameraMode) {
            stopPhysicalCamera()
        }

        val tv = lastTextureView ?: return null

        // 超广角/长焦：优先用逻辑相机 + CONTROL_ZOOM_RATIO（与系统相机一致）
        if (lens == CameraLens.ULTRA_WIDE || lens == CameraLens.TELEPHOTO) {
            val targetZoomRatio = resolveTargetZoomRatio(lens)
            if (targetZoomRatio != null) {
                try {
                    bindLogicalCameraWithZoom(
                        textureView = tv,
                        fps = lastFps,
                        zoomRatio = targetZoomRatio,
                        lens = lens,
                    )
                    return lens
                } catch (e: Exception) {
                    DebugLog.e(TAG, "逻辑相机+缩放方案失败: ${e.message}", e)
                    // 降级到物理相机直连
                }
            }

            // 降级：物理相机直连
            val physicalCameraId = physicalCameraIds[lens]
            if (physicalCameraId != null) {
                try {
                    bindPhysicalCameraInternal(
                        cameraId = physicalCameraId,
                        textureView = tv,
                        fps = lastFps,
                    )
                    DebugLog.d(TAG, "降级到物理相机直连: ${lens.name}, cameraId=$physicalCameraId")
                    return lens
                } catch (e: Exception) {
                    DebugLog.e(TAG, "物理相机绑定失败: ${e.message}", e)
                }
            }
            return null
        }

        // 标准/前置：直接 bindPreview
        val pipeline = lastFramePipeline

        bindPreviewInternal(
            textureView = tv,
            lens = lens,
            orientation = lastOrientation,
            framePipeline = pipeline,
            encoderWidth = lastEncoderWidth,
            encoderHeight = lastEncoderHeight,
            fps = lastFps,
        )
        return lens
    }

    /**
     * 解析目标镜头对应的 zoom ratio
     *
     * ULTRA_WIDE → minZoomRatio (如 0.6x，HAL 内部切换到超广角物理相机)
     * TELEPHOTO → 长焦切换点（暂不支持）
     */
    private fun resolveTargetZoomRatio(lens: CameraLens): Float? {
        if (lens == CameraLens.ULTRA_WIDE) {
            val minRatio = _minZoomRatio.value
            if (minRatio < 1.0f) {
                DebugLog.d(TAG, "ULTRA_WIDE 目标 zoomRatio=$minRatio (逻辑相机缩放)")
                return minRatio
            }
            DebugLog.d(TAG, "逻辑相机 minZoomRatio=$minRatio，不支持缩放到超广角")
            return null
        }
        return null
    }

    /**
     * 用 Camera2 打开逻辑相机 + 设置 CONTROL_ZOOM_RATIO（超广角/长焦切换）
     *
     * 与系统相机一致的路径：打开逻辑相机（如 camera 0），通过 CONTROL_ZOOM_RATIO
     * 让 HAL 内部切换到目标物理镜头。
     */
    private suspend fun bindLogicalCameraWithZoom(
        textureView: TextureView,
        fps: Int = 30,
        zoomRatio: Float,
        lens: CameraLens,
    ) {
        // 记住绑定参数
        lastTextureView = textureView
        lastFps = fps

        // 1. 清理旧会话
        stopCamera2Session()

        // 2. 启动 Camera2 HandlerThread
        val thread = HandlerThread("Camera2Logical").apply { start() }
        val handler = Handler(thread.looper)
        camera2Thread = thread
        camera2Handler = handler

        // 3. 获取逻辑相机的 camera ID
        val logicalCameraId = resolveLogicalBackCameraId()
        DebugLog.d(TAG, "逻辑相机+缩放: logicalCameraId=$logicalCameraId, zoomRatio=$zoomRatio, lens=${lens.name}")

        // 4. 配置预览 Surface — ~5MP 固定，保证流畅和画质
        val sensorAspect = selectMaxSensorResolution(logicalCameraId).let {
            it.width.toFloat() / it.height
        }
        val previewSize = selectPreviewResolution(logicalCameraId, sensorAspect)
        val previewSurface = configurePreviewSurface(textureView, previewSize.width, previewSize.height)
            ?: throw IllegalStateException("无法配置预览 Surface")

        // 5. 解析帧率 — 设最宽 FPS Range 确保帧率，同时给 HAL 灵活选择传感器模式
        val (actualFps, _) = resolveActualFpsFromCameraId(logicalCameraId, fps)
        lastAppliedFps = actualFps
        val zoomFpsRange = resolveSurfaceFpsRange(logicalCameraId, fps)
        fpsRangeWasSetOnBind = true

        // 6. 查询传感器信息
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalChars = cameraManager.getCameraCharacteristics(logicalCameraId)
        val activeArray = logicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val zoomRatioRange = logicalChars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        DebugLog.d(TAG, "逻辑相机传感器: activeArray=${activeArray?.width()}x${activeArray?.height()}, " +
            "zoomRatioRange=$zoomRatioRange")

        // 7. 打开逻辑相机
        val device = openCamera2Device(cameraManager, logicalCameraId, handler)
        camera2Device = device

        // 8. 创建 CaptureSession
        val surfaces = listOf(previewSurface)
        camera2Surfaces = surfaces
        val session = createCaptureSession(device, surfaces, handler)
        camera2Session = session

        // 9. 构建 CaptureRequest — 使用 TEMPLATE_PREVIEW 获得最宽 FOV
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, zoomFpsRange)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 视频防抖
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, currentVideoStabilizationMode)
            // 关键：通过 CONTROL_ZOOM_RATIO 切换物理镜头
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
            // SCALER_CROP_REGION 设为传感器全幅 — 获得最大 FOV
            if (activeArray != null) {
                set(CaptureRequest.SCALER_CROP_REGION, activeArray)
                currentCropRegion = activeArray
                DebugLog.d(TAG, "SCALER_CROP_REGION: 全幅 ${activeArray.width()}x${activeArray.height()}")
            }
            DebugLog.d(TAG, "已设置 CONTROL_ZOOM_RATIO=$zoomRatio, FPS=[${zoomFpsRange.lower},${zoomFpsRange.upper}]")
        }
        camera2RequestBuilder = requestBuilder

        session.setRepeatingRequest(requestBuilder.build(), null, handler)

        // 应用 TextureView 变换矩阵 — 修正预览旋转和宽高比
        applyPreviewTransform(textureView, logicalCameraId, previewSize.width, previewSize.height)

        // 初始化缩放范围（读取逻辑相机的 min/max zoomRatio）
        initZoomFromCameraCharacteristics(logicalCameraId)

        // 标记为物理相机模式（复用现有基础设施）
        isPhysicalCameraMode = true
        _isPhysicalCameraMode.value = true
        isSurfaceMode = false

        _currentLens.value = lens
        DebugLog.d(TAG, "逻辑相机+缩放绑定完成: logicalCameraId=$logicalCameraId, zoomRatio=$zoomRatio, lens=${lens.name}")
    }

    /**
     * 获取逻辑后置相机的 Camera2 ID
     */
    private fun resolveLogicalBackCameraId(): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId
            }
        }
        throw IllegalStateException("找不到后置逻辑相机")
    }

    /**
     * 使用新的 profile 参数重新绑定摄像头
     */
    suspend fun rebindWithProfile(width: Int, height: Int, fps: Int): Boolean = cameraMutex.withLock {
        // Surface 模式下不重新绑定，帧率通过 updateSurfaceFps() 管理
        if (isSurfaceMode) {
            DebugLog.d(TAG, "Surface 模式下跳过 rebindWithProfile，改用 updateSurfaceFps")
            return true
        }
        // 物理相机模式下也不重新绑定，帧率通过 updateSurfaceFps() 管理
        if (isPhysicalCameraMode) {
            DebugLog.d(TAG, "物理相机模式下跳过 rebindWithProfile，改用 updateSurfaceFps")
            return true
        }
        val tv = lastTextureView ?: return false
        val pipeline = lastFramePipeline

        // 参数没变时跳过重建，避免不必要的会话重启
        if (width == lastEncoderWidth && height == lastEncoderHeight &&
            fps == lastFps && currentLens.value == lastRebindLens) {
            DebugLog.d(TAG, "rebindWithProfile: 参数未变，跳过 ($width x $height, ${fps}fps)")
            return true
        }
        lastRebindLens = currentLens.value

        DebugLog.d(TAG, "rebindWithProfile: $width x $height, fps=$fps (预览帧率固定)")
        bindPreviewInternal(
            textureView = tv,
            lens = currentLens.value,
            orientation = lastOrientation,
            framePipeline = pipeline,
            encoderWidth = width,
            encoderHeight = height,
            fps = fps,
        )
        return true
    }

    // ==================== Camera2 Surface 模式（4K@60fps） ====================

    /**
     * 使用 Camera2 API 绑定预览 + 编码器 Surface（4K@60fps 专用，线程安全）
     */
    suspend fun bindPreviewWithSurface(
        textureView: TextureView,
        lens: CameraLens = _currentLens.value,
        fps: Int = 60,
        encoderSurface: Surface,
    ) = cameraMutex.withLock {
        bindPreviewWithSurfaceInternal(textureView, lens, fps, encoderSurface)
    }

    /**
     * bindPreviewWithSurface 的内部实现（不加锁，调用者必须持有 cameraMutex）
     */
    private suspend fun bindPreviewWithSurfaceInternal(
        textureView: TextureView,
        lens: CameraLens = _currentLens.value,
        fps: Int = 60,
        encoderSurface: Surface,
    ) {
        // 记住绑定参数
        lastTextureView = textureView
        lastFps = fps

        // 1. 清理旧会话
        stopCamera2Session()

        // 2. 启动 Camera2 HandlerThread
        val thread = HandlerThread("Camera2Session").apply { start() }
        val handler = Handler(thread.looper)
        camera2Thread = thread
        camera2Handler = handler

        // 3. 获取 cameraId
        val cameraId = resolveCameraId(lens)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // 4. 配置预览 Surface — 与编码器 Surface 同宽高比（16:9）
        //    预览和编码器输出必须宽高比一致，否则拉伸变形
        val surfaceSize = selectBestResolution(cameraId, 3840, 2160)
        val surfaceAspect = surfaceSize.width.toFloat() / surfaceSize.height
        val previewSurface = configurePreviewSurface(textureView, surfaceSize.width, surfaceSize.height)
            ?: throw IllegalStateException("无法配置预览 Surface，请确保 TextureView 已就绪")

        // 5. 解析最佳 FPS Range
        val actualRange = resolveSurfaceFpsRange(cameraId, fps)
        lastAppliedFps = actualRange.upper
        fpsRangeWasSetOnBind = true  // Surface 模式始终设置 FPS Range

        DebugLog.d(TAG, "Camera2 Surface 模式: cameraId=$cameraId, 请求fps=$fps, 实际Range=${actualRange}, 镜头=${lens.name}")

        // 6. 打开 Camera2 设备
        val device = openCamera2Device(cameraManager, cameraId, handler)
        camera2Device = device

        // 7. 创建 CaptureSession（预览 + 编码器双 Surface 输出）
        val surfaces = listOf(previewSurface, encoderSurface)
        camera2Surfaces = surfaces
        val session = createCaptureSession(device, surfaces, handler)
        camera2Session = session

        // 8. 构建 CaptureRequest
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(encoderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, actualRange)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 视频防抖 (EIS)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, currentVideoStabilizationMode)
            // SCALER_CROP_REGION — 居中裁剪以匹配编码器 Surface 的 16:9 宽高比
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            if (activeArray != null) {
                val sensorAspect = activeArray.width().toFloat() / activeArray.height()
                val cropRegion = if (abs(sensorAspect - surfaceAspect) < 0.02f) {
                    activeArray
                } else if (sensorAspect > surfaceAspect) {
                    // 传感器比 16:9 更宽（如 4:3）→ 裁左右
                    val newWidth = (activeArray.height() * surfaceAspect).toInt()
                    val dx = (activeArray.width() - newWidth) / 2
                    android.graphics.Rect(activeArray.left + dx, activeArray.top,
                        activeArray.left + dx + newWidth, activeArray.bottom)
                } else {
                    // 传感器比 16:9 更高 → 裁上下
                    val newHeight = (activeArray.width() / surfaceAspect).toInt()
                    val dy = (activeArray.height() - newHeight) / 2
                    android.graphics.Rect(activeArray.left, activeArray.top + dy,
                        activeArray.right, activeArray.top + dy + newHeight)
                }
                set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
                currentCropRegion = cropRegion
                DebugLog.d(TAG, "SCALER_CROP_REGION: ${activeArray.width()}x${activeArray.height()} → ${cropRegion.width()}x${cropRegion.height()}")
            }
        }
        camera2RequestBuilder = requestBuilder

        session.setRepeatingRequest(requestBuilder.build(), null, handler)

        // 应用 TextureView 变换矩阵
        applyPreviewTransform(textureView, cameraId, surfaceSize.width, surfaceSize.height)

        isSurfaceMode = true
        isPhysicalCameraMode = false
        _isPhysicalCameraMode.value = false
        _currentLens.value = lens

        DebugLog.d(TAG, "Camera2 Surface 模式绑定完成: ${previewSurface}? + encoderSurface, fps=${actualRange}")
    }

    /**
     * 使用 Camera2 API 直接绑定物理相机（线程安全）
     */
    suspend fun bindPhysicalCamera(
        cameraId: String,
        textureView: TextureView,
        fps: Int = 30,
        encoderSurface: Surface? = null,
    ) = cameraMutex.withLock {
        bindPhysicalCameraInternal(cameraId, textureView, fps, encoderSurface)
    }

    /**
     * bindPhysicalCamera 的内部实现（不加锁，调用者必须持有 cameraMutex）
     */
    private suspend fun bindPhysicalCameraInternal(
        cameraId: String,
        textureView: TextureView,
        fps: Int = 30,
        encoderSurface: Surface? = null,
    ) {
        // 记住绑定参数
        lastTextureView = textureView
        lastFps = fps

        // 1. 清理旧会话
        stopCamera2Session()

        // 2. 启动 Camera2 HandlerThread
        val thread = HandlerThread("Camera2Physical").apply { start() }
        val handler = Handler(thread.looper)
        camera2Thread = thread
        camera2Handler = handler

        // 3. 配置预览 Surface — ~5MP 固定，保证流畅和画质
        val sensorAspect = selectMaxSensorResolution(cameraId).let {
            it.width.toFloat() / it.height
        }
        val previewSize = selectPreviewResolution(cameraId, sensorAspect)
        val previewSurface = configurePreviewSurface(textureView, previewSize.width, previewSize.height)
            ?: throw IllegalStateException("无法配置预览 Surface")

        // 4. 解析帧率 — 录制模式设宽范围保帧率，预览模式不设以保画质
        val fpsRangeToSet: android.util.Range<Int>?
        // 设置最宽 FPS Range 确保帧率，同时给 HAL 灵活选择传感器模式保持画质
        val (actualFps, _) = resolveActualFpsFromCameraId(cameraId, fps)
        fpsRangeToSet = resolveSurfaceFpsRange(cameraId, fps)
        lastAppliedFps = actualFps
        fpsRangeWasSetOnBind = true

        // 5. 查询物理相机传感器信息（诊断用）
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val physicalChars = cameraManager.getCameraCharacteristics(cameraId)
        val activeArray = physicalChars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val preCorrectionArray = physicalChars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val physicalSize = physicalChars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val focalLengths = physicalChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        DebugLog.d(TAG, "物理相机传感器: cameraId=$cameraId, activeArray=${activeArray?.width()}x${activeArray?.height()}, " +
            "preCorrection=${preCorrectionArray?.width()}x${preCorrectionArray?.height()}, " +
            "physicalSize=${physicalSize?.width}x${physicalSize?.height}mm, " +
            "focalLengths=${focalLengths?.toList()}")

        // 6. 打开物理相机
        val device = openCamera2Device(cameraManager, cameraId, handler)
        camera2Device = device

        // 7. 创建 CaptureSession
        val surfaces = if (encoderSurface != null) {
            listOf(previewSurface, encoderSurface)
        } else {
            listOf(previewSurface)
        }
        camera2Surfaces = surfaces
        val session = createCaptureSession(device, surfaces, handler)
        camera2Session = session

        // 8. 构建 CaptureRequest — 使用 TEMPLATE_PREVIEW 获得最宽 FOV
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            surfaces.forEach { surface -> addTarget(surface) }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRangeToSet)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 视频防抖
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, currentVideoStabilizationMode)
            // SCALER_CROP_REGION 设为传感器全幅 — 获得最大 FOV
            if (activeArray != null) {
                set(CaptureRequest.SCALER_CROP_REGION, activeArray)
                currentCropRegion = activeArray
                DebugLog.d(TAG, "SCALER_CROP_REGION: 全幅 ${activeArray.width()}x${activeArray.height()}")
            }
        }
        camera2RequestBuilder = requestBuilder

        session.setRepeatingRequest(requestBuilder.build(), null, handler)

        // 应用 TextureView 变换矩阵 — 修正预览旋转和宽高比
        applyPreviewTransform(textureView, cameraId, previewSize.width, previewSize.height)

        // 初始化缩放范围
        initZoomFromCameraCharacteristics(cameraId)

        isPhysicalCameraMode = true
        _isPhysicalCameraMode.value = true
        isSurfaceMode = encoderSurface != null

        // 根据物理相机 ID 确定 currentLens
        val lens = physicalCameraIds.entries.firstOrNull { it.value == cameraId }?.key
            ?: CameraLens.WIDE
        _currentLens.value = lens

        DebugLog.d(TAG, "物理相机绑定完成: cameraId=$cameraId, surfaces=${surfaces.size}, fps=${lastAppliedFps}fps, lens=${lens.name}")
    }

    /**
     * 停止物理相机直连模式
     */
    fun stopPhysicalCamera() {
        if (!isPhysicalCameraMode) return
        stopCamera2Session()
        isPhysicalCameraMode = false
        _isPhysicalCameraMode.value = false
        DebugLog.d(TAG, "物理相机模式已停止")
    }

    /**
     * 停止 Camera2 会话并释放资源
     */
    fun stopCamera2Session() {
        try {
            camera2Session?.stopRepeating()
        } catch (_: Exception) {}
        try {
            camera2Session?.close()
        } catch (_: Exception) {}
        camera2Session = null
        camera2RequestBuilder = null
        camera2Surfaces = emptyList()
        currentCropRegion = null

        try {
            camera2Device?.close()
        } catch (_: Exception) {}
        camera2Device = null

        imageReader?.close()
        imageReader = null

        camera2Handler = null
        camera2Thread?.quitSafely()
        camera2Thread = null

        if (isSurfaceMode) {
            isSurfaceMode = false
            DebugLog.d(TAG, "Camera2 Surface 会话已停止")
        }
    }

    // ---- 视频防抖 (EIS) 方法 ----

    /**
     * 检查当前摄像头是否支持 EIS（电子防抖）
     */
    private fun checkEisSupportedForCurrentCamera(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camera2Device?.id ?: resolveCameraId(_currentLens.value)

            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?: intArrayOf()

            val supported = modes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
            DebugLog.d(TAG, "EIS 支持检测: cameraId=$cameraId, modes=${modes.toList()}, supported=$supported")
            supported
        } catch (e: Exception) {
            DebugLog.w(TAG, "检测 EIS 支持失败: ${e.message}")
            false
        }
    }

    /**
     * 刷新 EIS 能力检测并更新 StateFlow
     */
    fun refreshEisCapability() {
        val supported = checkEisSupportedForCurrentCamera()
        _eisSupported.value = supported
        DebugLog.d(TAG, "EIS 能力已刷新: supported=$supported")
    }

    /**
     * 设置视频防抖模式
     */
    fun setVideoStabilization(enabled: Boolean, isRecording: Boolean = false) {
        if (isRecording) {
            DebugLog.w(TAG, "录制中禁止切换防抖，忽略请求: enabled=$enabled")
            return
        }
        if (enabled && !_eisSupported.value) {
            DebugLog.w(TAG, "当前设备不支持 EIS，忽略开启请求")
            return
        }

        videoStabilizationEnabled = enabled
        val mode = currentVideoStabilizationMode
        DebugLog.d(TAG, "视频防抖已${if (enabled) "开启" else "关闭"}, mode=$mode")

        // 重建 CaptureRequest 使变更立即生效
        rebuildCaptureRequest(videoStabilizationMode = mode)
    }

    /**
     * 重建 CaptureRequest（通用方法）
     *
     * 在缩放、热管理 FPS、EIS 切换时调用。
     * 确保所有目标 Surface 和参数被正确设置。
     *
     * @param fpsRange 新的 FPS Range，为 null 时保留当前值
     * @param videoStabilizationMode EIS 模式，默认使用 currentVideoStabilizationMode
     * @param zoomRatio 缩放倍率，为 null 时保留当前值
     */
    private fun rebuildCaptureRequest(
        fpsRange: android.util.Range<Int>? = null,
        videoStabilizationMode: Int = currentVideoStabilizationMode,
        zoomRatio: Float? = null,
    ) {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val handler = camera2Handler ?: return

        try {
            val cameraId = device.id
            // FPS Range 策略：显式传入时使用，否则遵循初始绑定时是否设置的策略
            val range = fpsRange ?: run {
                if (!fpsRangeWasSetOnBind) {
                    // 初始绑定时未设 FPS Range（预览模式），rebuild 时也不设
                    null
                } else {
                    val fps = if (lastAppliedFps > 0) lastAppliedFps else 30
                    resolveSurfaceFpsRange(cameraId, fps)
                }
            }
            val zoom = zoomRatio ?: _zoomRatio.value

            // 复用当前会话的 SCALER_CROP_REGION（在 bind 时已计算好）
            val savedCropRegion = currentCropRegion

            // Surface 模式（4K@60fps 录制）用 TEMPLATE_RECORD，其他模式用 TEMPLATE_PREVIEW
            val template = if (isSurfaceMode) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val requestBuilder = device.createCaptureRequest(template).apply {
                camera2Surfaces.forEach { surface -> addTarget(surface) }
                // 仅在初始绑定或热管理显式设置了 FPS Range 时才设置
                if (range != null) {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabilizationMode)
                // 缩放
                if (zoom != 1.0f) {
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                }
                // 复用绑定时保存的 SCALER_CROP_REGION
                if (savedCropRegion != null) {
                    set(CaptureRequest.SCALER_CROP_REGION, savedCropRegion)
                }
            }
            camera2RequestBuilder = requestBuilder
            session.setRepeatingRequest(requestBuilder.build(), null, handler)
            if (range != null) lastAppliedFps = range.upper
            DebugLog.d(TAG, "CaptureRequest 已重建: fpsRange=${range}, eisMode=$videoStabilizationMode, zoom=$zoom")
        } catch (e: Exception) {
            DebugLog.w(TAG, "重建 CaptureRequest 失败: ${e.message}")
        }
    }

    /**
     * 更新 Camera2 会话的 AE FPS Range（热管理降频时调用）
     */
    fun updateSurfaceFps(fps: Int) {
        val cameraId = camera2Device?.id ?: return
        val range = resolveSurfaceFpsRange(cameraId, fps)
        rebuildCaptureRequest(fpsRange = range)
    }

    // ---- Camera2 内部辅助方法 ----

    /**
     * 将镜头枚举映射到 Camera2 camera ID
     *
     * WIDE/ULTRA_WIDE/TELEPHOTO → 后置逻辑相机
     * FRONT → 前置逻辑相机
     */
    private fun resolveCameraId(lens: CameraLens): String {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = when (lens) {
            CameraLens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            else -> CameraCharacteristics.LENS_FACING_BACK
        }

        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            if (chars.get(CameraCharacteristics.LENS_FACING) == targetFacing) {
                return cameraId
            }
        }
        return "0" // 兜底
    }

    /**
     * 从 StreamConfigurationMap 中选择最佳分辨率
     *
     * 策略（参考 boofcv SimpleCamera2Activity）：
     * 1. 精确匹配目标分辨率
     * 2. ≥ 目标分辨率的最小值（避免浪费带宽）
     * 3. 最接近的可用分辨率
     */
    private fun selectBestResolution(
        cameraId: String,
        targetWidth: Int,
        targetHeight: Int,
    ): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(targetWidth, targetHeight)

        val outputSizes = configMap.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width >= 640 && it.height >= 480 } // 过滤太小的分辨率
            .sortedByDescending { it.width.toLong() * it.height }

        if (outputSizes.isEmpty()) return Size(targetWidth, targetHeight)

        // 精确匹配
        outputSizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }?.let { return it }

        // ≥ 目标的最小值
        outputSizes.lastOrNull { it.width >= targetWidth && it.height >= targetHeight }?.let { return it }

        // 最接近的
        return outputSizes.minByOrNull {
            abs(it.width - targetWidth) + abs(it.height - targetHeight)
        } ?: outputSizes.first()
    }

    /**
     * 从传感器支持的 YUV 输出尺寸中选取最大分辨率（与传感器原生宽高比匹配）
     *
     * 返回与 activeArray 宽高比一致的最大 YUV_420_888 输出尺寸。
     * 这保证输出缓冲区与 SCALER_CROP_REGION (全幅 activeArray) 宽高比一致，
     * HAL 不会拉伸变形，传感器全幅使用获得最大 FOV。
     *
     * 注意：如果编码器目标宽高比不同，帧管线通过 cropAndScaleNv12
     * 在软件层做居中裁剪，不影响 FOV。
     */
    private fun selectMaxSensorResolution(cameraId: String): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)

        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val sensorAspect = if (activeArray != null) {
            activeArray.width().toFloat() / activeArray.height()
        } else {
            4f / 3f // 默认 4:3
        }

        val candidates = configMap.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width >= 640 && it.height >= 480 }

        // 优先选与传感器宽高比一致的最大分辨率
        return candidates
            .filter { abs(it.width.toFloat() / it.height - sensorAspect) < 0.03f }
            .maxByOrNull { it.width.toLong() * it.height }
            // 降级：选任意最大分辨率
            ?: candidates.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(1920, 1080)
    }

    /**
     * 选取适合预览的分辨率（与 sensorAspect 宽高比一致，~1080p 级别）
     *
     * 预览分辨率过高（如 4000×3000）会导致 ISP 带宽不足，画面卡顿。
     * 此方法从传感器支持的输出尺寸中选一个匹配宽高比的中等分辨率，
     * 在流畅性和画质之间取得平衡（目标 ~5MP）。
     *
     * @param cameraId 相机 ID
     * @param sensorAspect 传感器宽高比（宽/高）
     * @param maxPixels 最大像素数，默认 ~5MP
     */
    private fun selectPreviewResolution(
        cameraId: String,
        sensorAspect: Float,
        maxPixels: Long = 2560L * 1920L,
    ): Size {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return Size(1920, 1080)

        val candidates = configMap.getOutputSizes(ImageFormat.YUV_420_888)
            .filter { it.width >= 640 && it.height >= 480 }
            .filter { abs(it.width.toFloat() / it.height - sensorAspect) < 0.03f }
            .sortedByDescending { it.width.toLong() * it.height }

        if (candidates.isEmpty()) {
            return Size(1920, 1080)
        }

        // 选最接近但不超过 maxPixels 的分辨率，保证清晰度
        return candidates.firstOrNull { it.width.toLong() * it.height <= maxPixels }
            ?: candidates.last()
    }

    /**
     * 查询摄像头实际支持的帧率和对应的 AE Range
     *
     * 直接通过 cameraId 查询 CameraCharacteristics，不依赖 CameraX。
     */
    private fun resolveActualFpsFromCameraId(
        cameraId: String,
        requestedFps: Int,
    ): Pair<Int, android.util.Range<Int>?> {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: arrayOf()

            // 收集所有 ≥30 的 FPS 值（取每个 range 的 upper），始终更新 UI 可选帧率
            val aeSupported = fpsRanges.map { it.upper }.filter { it >= 30 }.distinct().sortedDescending()
            _supportedFps.value = aeSupported.toSet()
            DebugLog.d(TAG, "摄像头 $cameraId AE 支持的 FPS: $aeSupported, 可用 Range: ${fpsRanges.map { "[${it.lower},${it.upper}]" }}, 请求: ${requestedFps}fps")

            // 选不超过请求值的最佳 FPS（仅用于 pipeline 帧率节流，不设置 AE Range）
            val bestFps = aeSupported.firstOrNull { it <= requestedFps }
                ?: aeSupported.firstOrNull()
                ?: 30

            // 预览模式下不设置 AE_TARGET_FPS_RANGE，让 HAL 使用默认值
            // HAL 默认值通常是 [30, 60] 或 [15, 60]，在充足光线下可达 60fps
            // 且 HAL 不会因设置了 AE Range 而切换到裁切传感器模式
            // Surface 模式由 resolveSurfaceFpsRange 单独控制
            DebugLog.d(TAG, "预览不设 AE Range，HAL 默认，pipeline 目标: ${bestFps}fps")
            return Pair(bestFps, null)
        } catch (e: Exception) {
            DebugLog.w(TAG, "查询摄像头帧率失败，退回 30fps: ${e.message}")
            return Pair(requestedFps.coerceAtMost(30), null)
        }
    }

    /**
     * 为 Camera2 Surface 模式解析最佳 FPS Range
     *
     * 策略（按优先级）：
     * 1. 包含 fps 的范围中，选下限最接近 fps 的（避免 HAL 以过低帧率输出）
     * 2. upper >= fps 的任意范围
     * 3. 构造 [fps, fps]（兜底）
     */
    private fun resolveSurfaceFpsRange(cameraId: String, fps: Int): android.util.Range<Int> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()

        DebugLog.d(TAG, "Surface FPS 解析: 可用范围=${fpsRanges.map { "[${it.lower},${it.upper}]" }}, 请求=$fps")

        // 选包含 fps 的范围中下限最高的，确保 HAL 输出帧率接近目标
        // [30,30] 优于 [25,30] 优于 [14,30]——下限越高 HAL 越不会掉到低帧率
        val tightRange = fpsRanges
            .filter { it.upper >= fps && it.lower <= fps }
            .maxByOrNull { it.lower }
        if (tightRange != null) {
            DebugLog.d(TAG, "Surface FPS: 最优范围 [${tightRange.lower},${tightRange.upper}]")
            return tightRange
        }

        // 降级：任意上限 ≥ fps 的范围，选上限最高的
        val anyRange = fpsRanges
            .filter { it.upper >= fps }
            .maxByOrNull { it.upper }
        if (anyRange != null) {
            DebugLog.d(TAG, "Surface FPS: 兜底范围 [${anyRange.lower},${anyRange.upper}]")
            return anyRange
        }

        // 最终降级
        DebugLog.w(TAG, "Surface FPS: 无匹配范围，使用构造值 [$fps,$fps]")
        return android.util.Range(fps, fps)
    }

    /**
     * 配置 TextureView 的 SurfaceTexture 缓冲区并构建 Surface
     *
     * 将 SurfaceTexture 缓冲区大小设为指定分辨率，
     * 确保预览输出与帧捕获（ImageReader）使用相同宽高比。
     *
     * @param textureView TextureView
     * @param width 目标宽度（与 ImageReader/编码器一致）
     * @param height 目标高度
     */
    private fun configurePreviewSurface(textureView: TextureView, width: Int, height: Int): Surface? {
        if (!textureView.isAvailable) return null
        val surfaceTexture = textureView.surfaceTexture ?: return null
        try {
            surfaceTexture.setDefaultBufferSize(width, height)
            DebugLog.d(TAG, "SurfaceTexture 缓冲区已配置: ${width}x${height}")
        } catch (e: Exception) {
            DebugLog.w(TAG, "配置 SurfaceTexture 缓冲区失败: ${e.message}")
        }
        return Surface(surfaceTexture)
    }

    /**
     * 应用 TextureView 变换矩阵
     *
     * 参考 Google Camera2Basic 示例，将相机传感器输出旋转/缩放到与
     * 设备屏幕方向匹配。传感器全幅宽撑满显示，高度居中不裁剪。
     */
    private fun applyPreviewTransform(
        textureView: TextureView,
        cameraId: String,
        bufferWidth: Int,
        bufferHeight: Int,
    ) {
        val viewW = textureView.width
        val viewH = textureView.height
        if (viewW == 0 || viewH == 0) {
            DebugLog.w(TAG, "TextureView 尚未布局，跳过变换矩阵")
            return
        }

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val rotation = wm.defaultDisplay.rotation

            val matrix = Matrix()
            val centerX = viewW / 2f
            val centerY = viewH / 2f

            // 参考 Google Camera2Basic 示例：交换宽高构建 bufferRect
            val viewRect = RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
            val bufferRect = RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())

            // @formatter:off — Google Camera2Basic 标准变换逻辑
            when (rotation) {
                android.view.Surface.ROTATION_0 -> {
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                }
                android.view.Surface.ROTATION_90,
                android.view.Surface.ROTATION_270 -> {
                    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
                    matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
                    val scale = maxOf(
                        viewH.toFloat() / bufferHeight,
                        viewW.toFloat() / bufferWidth,
                    )
                    matrix.postScale(scale, scale, centerX, centerY)
                    if (rotation == android.view.Surface.ROTATION_90) {
                        matrix.postRotate(270f, centerX, centerY)
                    } else {
                        matrix.postRotate(90f, centerX, centerY)
                    }
                }
                android.view.Surface.ROTATION_180 -> {
                    matrix.postRotate(180f, centerX, centerY)
                }
            }
            // @formatter:on

            // 非标准传感器方向补偿
            if (sensorOrientation != 90) {
                val adjust = ((90 - sensorOrientation) % 360 + 360) % 360
                if (adjust != 0) {
                    matrix.postRotate(adjust.toFloat(), centerX, centerY)
                }
            }

            textureView.setTransform(matrix)

            DebugLog.d(TAG, "TextureView 变换: sensorOri=$sensorOrientation°, " +
                "rotation=$rotation, buffer=${bufferWidth}x${bufferHeight}, view=${viewW}x${viewH}")
        } catch (e: Exception) {
            DebugLog.w(TAG, "应用 TextureView 变换失败: ${e.message}")
        }
    }

    /**
     * 从 CameraCharacteristics 初始化缩放范围
     *
     * 替代原 CameraX 的 initZoomFromCamera(camera)。
     * 从 CONTROL_ZOOM_RATIO_RANGE 读取 min/max，同时重置当前缩放为 1.0x。
     */
    private fun initZoomFromCameraCharacteristics(cameraId: String) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            val zoomRange = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRange != null) {
                _minZoomRatio.value = zoomRange.lower.toFloat()
                _maxZoomRatio.value = zoomRange.upper.toFloat()
                DebugLog.d(TAG, "缩放范围: ${zoomRange.lower}x ~ ${zoomRange.upper}x (CONTROL_ZOOM_RATIO_RANGE)")
            } else {
                // 无 ZOOM_RATIO 支持，回退到 SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
                val maxDigitalZoom = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1.0f
                _minZoomRatio.value = 1.0f
                _maxZoomRatio.value = maxDigitalZoom
                DebugLog.d(TAG, "缩放范围: 1.0x ~ ${maxDigitalZoom}x (SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)")
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "读取缩放范围失败: ${e.message}")
            _maxZoomRatio.value = 1.0f
            _minZoomRatio.value = 1.0f
        }
        _zoomRatio.value = 1.0f
    }

    /**
     * 应用缩放增量（双指捏合时调用）
     *
     * 通过重建 CaptureRequest 并设置 CONTROL_ZOOM_RATIO 实现。
     * 物理相机模式（超广角/长焦直连）和 Surface 模式下禁用。
     */
    fun applyZoomDelta(delta: Float) {
        if (isPhysicalCameraMode) return

        val newRatio = (_zoomRatio.value * delta)
            .coerceIn(_minZoomRatio.value, _maxZoomRatio.value)
        if (abs(newRatio - _zoomRatio.value) < 0.01f) return

        _zoomRatio.value = newRatio
        rebuildCaptureRequest(zoomRatio = newRatio)
    }

    /**
     * 打开 Camera2 设备（suspend 函数）
     */
    private suspend fun openCamera2Device(
        cameraManager: CameraManager,
        cameraId: String,
        handler: Handler,
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                DebugLog.d(TAG, "Camera2 设备已打开: $cameraId")
                cont.resume(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                DebugLog.w(TAG, "Camera2 设备断开: $cameraId")
                camera.close()
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("Camera2 设备断开连接")
                )
            }
            override fun onError(camera: CameraDevice, error: Int) {
                DebugLog.e(TAG, "Camera2 设备错误: $cameraId, error=$error")
                camera.close()
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("Camera2 设备打开失败: error=$error")
                )
            }
        }, handler)

        cont.invokeOnCancellation {
            try { cameraManager.getCameraIdList() } catch (_: Exception) {}
        }
    }

    /**
     * 创建 Camera2 CaptureSession（suspend 函数）
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                DebugLog.d(TAG, "Camera2 CaptureSession 已配置: ${surfaces.size} 个 Surface")
                cont.resume(session)
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                DebugLog.e(TAG, "Camera2 CaptureSession 配置失败")
                if (cont.isActive) cont.resumeWithException(
                    IllegalStateException("Camera2 CaptureSession 配置失败")
                )
            }
        }, handler)
    }

    /**
     * 释放所有摄像头会话和资源
     */
    fun release() {
        stopPhysicalCamera()
        stopCamera2Session()
        _zoomRatio.value = 1.0f
        _maxZoomRatio.value = 1.0f
        _minZoomRatio.value = 1.0f
    }
}

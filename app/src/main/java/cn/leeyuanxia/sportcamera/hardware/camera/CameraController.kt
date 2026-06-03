package cn.leeyuanxia.sportcamera.hardware.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Display
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import cn.leeyuanxia.sportcamera.util.DebugLog
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX 摄像头控制器
 *
 * 负责摄像头预览绑定、镜头切换、ImageAnalysis 帧输出。
 *
 * 绑定两个 use case：
 * - Preview → PreviewView（屏幕显示）
 * - ImageAnalysis → CameraFramePipeline（帧数据送入编码器）
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context) {

    private companion object {
        const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null

    private val _currentLens = MutableStateFlow(CameraLens.DEFAULT)
    val currentLens: StateFlow<CameraLens> = _currentLens.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<CameraLens>>(emptyList())
    val availableLenses: StateFlow<List<CameraLens>> = _availableLenses.asStateFlow()

    /** 摄像头硬件支持的帧率集合（从 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 提取） */
    private val _supportedFps = MutableStateFlow<Set<Int>>(setOf(30))
    val supportedFps: StateFlow<Set<Int>> = _supportedFps.asStateFlow()

    /** ImageAnalysis 回调用的单线程执行器 */
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "FrameAnalyzer").apply { isDaemon = true }
    }

    /** 记住上次绑定的参数，用于镜头切换时重新绑定 */
    private var lastLifecycleOwner: LifecycleOwner? = null
    private var lastPreviewView: PreviewView? = null
    private var lastFramePipeline: CameraFramePipeline? = null
    private var lastEncoderWidth: Int = 1280
    private var lastEncoderHeight: Int = 720
    private var lastOrientation: RecordOrientation = RecordOrientation.LANDSCAPE
    private var lastFps: Int = 30
    private var lastAppliedFps: Int = 30

    // ---- Camera2 Surface 模式（4K@60fps 使用） ----

    /** Camera2 设备（Surface 模式下使用，替代 CameraX） */
    private var camera2Device: CameraDevice? = null

    /** Camera2 捕获会话 */
    private var camera2Session: CameraCaptureSession? = null

    /** Camera2 捕获请求构建器（用于热管理更新 FPS Range） */
    private var camera2RequestBuilder: CaptureRequest.Builder? = null

    /** Camera2 会话的输出 Surface 列表（用于重建请求时重新添加 target） */
    private var camera2Surfaces: List<Surface> = emptyList()

    /** Camera2 专用 HandlerThread */
    private var camera2Thread: HandlerThread? = null

    /** Camera2 专用 Handler */
    private var camera2Handler: Handler? = null

    /** CameraX 绑定返回的 Camera 对象（用于缩放控制） */
    @Volatile
    private var boundCamera: androidx.camera.core.Camera? = null

    /** 当前是否处于 Camera2 Surface 模式 */
    @Volatile
    var isSurfaceMode: Boolean = false
        private set

    // ---- 缩放控制 ----

    /** 当前缩放倍率（从 zoomState 同步，反映 HAL 实际值） */
    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    /** 最大缩放倍率（从 CameraInfo.zoomState 获取） */
    private val _maxZoomRatio = MutableStateFlow(1.0f)
    val maxZoomRatio: StateFlow<Float> = _maxZoomRatio.asStateFlow()

    // ---- 视频防抖 (EIS) ----

    /** 视频防抖是否开启（由 ViewModel 设置，CameraController 负责应用到硬件） */
    @Volatile
    var videoStabilizationEnabled: Boolean = true
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
     * 初始化 CameraProvider 并检测可用镜头
     *
     * 注意：bindPreview() 也会初始化 cameraProvider（竞态条件），
     * 所以镜头检测不能依赖 cameraProvider == null 判断，
     * 改用 availableLenses 是否为空来决定是否需要检测。
     */
    suspend fun initialize() {
        DebugLog.i(TAG, "initialize() 被调用")
        if (cameraProvider == null) {
            cameraProvider = ProcessCameraProvider.getInstance(context).await()
        }
        // 镜头检测：只在尚未检测时执行（避免竞态跳过）
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
     * 检测三种镜头：
     * - WIDE：标准后摄（始终可用）
     * - FRONT：前置摄像头（大部分设备有）
     * - ULTRA_WIDE：超广角后摄（部分设备可用，某些厂商会隐藏）
     *
     * 检测策略：
     * 1. Camera2 cameraIdList 标准检测（含物理子相机）
     * 2. 直接探测 camera ID 0~9（绕过厂商 cameraIdList 过滤）
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun detectAvailableLenses() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableSetOf<CameraLens>()
        lenses.add(CameraLens.WIDE) // 标准后摄始终可用

        DebugLog.i(TAG, "===== 开始检测镜头 =====")

        // 收集所有可探测的相机信息
        val allCameraChars = mutableMapOf<String, android.hardware.camera2.CameraCharacteristics>()

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

            // 后置超广角（焦距 < 4mm）
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                checkFocalLengthForUltraWide(chars, lenses)

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
                            checkFocalLengthForUltraWide(physicalChars, lenses)
                        } catch (e: Exception) {
                            DebugLog.w(
                                TAG, "无法查询物理子相机 $physicalId: ${e.javaClass.simpleName}: ${e.message}"
                            )
                        }
                    }
                }
            }
        }

        // 验证：Camera2 检测到的镜头，CameraX 不一定能用（如魅族隐藏超广角）
        val provider = cameraProvider
        if (provider != null) {
            validateLensesAgainstCameraX(lenses, provider)
        }

        _availableLenses.value = lenses.toList()
        DebugLog.i(TAG, "===== 检测完成，可用镜头: ${lenses.toList()} =====")
    }

    /**
     * 验证镜头在 CameraX 中是否真正可用
     *
     * Camera2 探测到的镜头（如魅族隐藏的超广角），CameraX 可能看不到。
     * 在 bindToLifecycle 时会抛 IllegalArgumentException 导致崩溃。
     * 此方法通过模拟 resolveCameraSelector 的过滤条件来提前剔除不可用的镜头。
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun validateLensesAgainstCameraX(
        lenses: MutableSet<CameraLens>,
        provider: ProcessCameraProvider,
    ) {
        val cameraInfos = provider.availableCameraInfos
        val iterator = lenses.iterator()
        while (iterator.hasNext()) {
            val lens = iterator.next()
            if (lens == CameraLens.WIDE) continue // WIDE 用 DEFAULT_BACK_CAMERA，始终可用

            val selector = resolveCameraSelector(lens)
            // 用 selector 内部的 filter 逻辑检查是否有匹配的相机
            val matched = selector.filter(cameraInfos)
            if (matched.isEmpty()) {
                DebugLog.w(TAG, "镜头 ${lens.name} 在 CameraX 中无匹配相机，移除")
                iterator.remove()
            } else {
                DebugLog.i(TAG, "镜头 ${lens.name} 验证通过，匹配 ${matched.size} 个相机")
            }
        }
    }

    /**
     * 记录单个相机的详细信息（诊断用）
     */
    private fun logCameraInfo(
        source: String,
        cameraId: String,
        chars: android.hardware.camera2.CameraCharacteristics,
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
     * 检查焦距是否属于超广角镜头（< 4mm）
     */
    private fun checkFocalLengthForUltraWide(
        chars: android.hardware.camera2.CameraCharacteristics,
        lenses: MutableSet<CameraLens>,
    ) {
        val focalLengths = chars.get(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
        ) ?: return
        if (focalLengths.any { it < 4.0f }) {
            lenses.add(CameraLens.ULTRA_WIDE)
        }
    }

    /**
     * 绑定摄像头预览 + 帧分析到 PreviewView 和编码管线
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bindPreview(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lens: CameraLens = _currentLens.value,
        orientation: RecordOrientation = RecordOrientation.PORTRAIT,
        framePipeline: CameraFramePipeline? = null,
        encoderWidth: Int = 1280,
        encoderHeight: Int = 720,
        fps: Int = 30,
    ) {
        val provider = cameraProvider
            ?: ProcessCameraProvider.getInstance(context).await().also {
                cameraProvider = it
            }

        // 记住绑定参数，用于镜头切换
        lastLifecycleOwner = lifecycleOwner
        lastPreviewView = previewView
        lastFramePipeline = framePipeline
        lastEncoderWidth = encoderWidth
        lastEncoderHeight = encoderHeight
        lastOrientation = orientation
        lastFps = fps

        val cameraSelector = resolveCameraSelector(lens)
        // 查询硬件支持的帧率和对应的 AE Range
        val (actualFps, bestRange) = resolveActualFps(fps, cameraSelector)
        lastAppliedFps = actualFps
        DebugLog.d(TAG, "请求帧率: ${fps}fps, 实际帧率: ${actualFps}fps, Range: ${bestRange}")

        val targetRotation = try {
            resolveDisplay(context)?.rotation ?: Surface.ROTATION_0
        } catch (_: UnsupportedOperationException) {
            Surface.ROTATION_0
        }

        // Use Case 1: Preview → PreviewView
        val previewBuilder = Preview.Builder()
            .setTargetRotation(targetRotation)

        // 通过 Camera2Interop 设置视频防抖（EIS）
        try {
            Camera2Interop.Extender(previewBuilder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    currentVideoStabilizationMode
                )
        } catch (e: Exception) {
            DebugLog.w(TAG, "Preview Camera2Interop 设置防抖失败: ${e.message}")
        }

        val preview = previewBuilder.build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        provider.unbindAll()

        // Use Case 2: ImageAnalysis → 帧管线（如果提供）
        if (framePipeline != null) {
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                AspectRatio.RATIO_16_9,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO
                            )
                        )
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(encoderWidth, encoderHeight),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                )
                .setTargetRotation(targetRotation)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            // 通过 Camera2Interop 设置 AE 目标帧率范围，让摄像头实际输出高帧率
            // 关键：使用传感器实际支持的 Range（如 [30,60]）而非单点值（如 [60,60]），
            // 单点值在部分设备上会被忽略或导致高分辨率下退回 30fps
            // 通过 Camera2Interop 设置 AE 目标帧率范围和视频防抖
            val extender = Camera2Interop.Extender(analysisBuilder)

            if (actualFps > 30 && bestRange != null) {
                try {
                    extender.setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        bestRange
                    )
                    DebugLog.d(TAG, "已通过 Camera2Interop 请求 ${actualFps}fps 输出, Range=[${bestRange.lower},${bestRange.upper}]")
                } catch (e: Exception) {
                    DebugLog.w(TAG, "Camera2Interop 设置帧率失败: ${e.message}")
                }
            }

            // 视频防抖 (EIS)：通过 Camera2Interop 设置
            try {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    currentVideoStabilizationMode
                )
                DebugLog.d(TAG, "已设置视频防抖: mode=$currentVideoStabilizationMode")
            } catch (e: Exception) {
                DebugLog.w(TAG, "Camera2Interop 设置防抖失败: ${e.message}")
            }

            val imageAnalysis = analysisBuilder.build()
            imageAnalysis.setAnalyzer(analyzerExecutor, framePipeline)

            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            boundCamera = camera
            initZoomFromCamera(camera)
            framePipeline.setCameraFps(lastAppliedFps)
        } else {
            val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
            boundCamera = camera
            initZoomFromCamera(camera)
        }

        _currentLens.value = lens
    }

    /**
     * 查询摄像头实际支持的帧率和对应的 AE Range
     *
     * 从 CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES 中选取最佳匹配：
     * - AE Range 的 upper 不超过请求值
     * - 返回完整的 Range 对象（而非单点值），供 Camera2Interop 使用
     *
     * 注意：不使用 StreamConfigurationMap.getOutputMinFrameDuration 来限制帧率，
     * 因为该方法返回的是 YUV 格式的格式级帧持续时间，远低于传感器实际能力，
     * 会导致所有高帧率选项被错误过滤（实测在支持 4K@60fps 的设备上也返回 30fps）。
     *
     * @return Pair(bestFps, bestRange)，bestFps=30 时 bestRange 为 null
     */
    private fun resolveActualFps(
        requestedFps: Int,
        cameraSelector: CameraSelector,
    ): Pair<Int, android.util.Range<Int>?> {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = Camera2CameraInfo.from(cameraProvider!!.availableCameraInfos
                .first { cameraSelector.filter(listOf(it)).isNotEmpty() }).cameraId
            val chars = cameraManager.getCameraCharacteristics(cameraId)

            val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?: arrayOf()

            // 收集所有 ≥30 的 FPS 值（取每个 range 的 upper），始终更新 UI 可选帧率
            val aeSupported = fpsRanges.map { it.upper }.filter { it >= 30 }.distinct().sortedDescending()
            _supportedFps.value = aeSupported.toSet()
            DebugLog.d(TAG, "摄像头 $cameraId AE 支持的 FPS: $aeSupported, 可用 Range: ${fpsRanges.map { "[${it.lower},${it.upper}]" }}, 请求: ${requestedFps}fps")

            // 请求 ≤30fps 时，无需查询高帧率 Range，直接返回
            if (requestedFps <= 30) return Pair(requestedFps, null)

            // 选不超过请求值的最佳 FPS
            val bestFps = aeSupported.firstOrNull { it <= requestedFps }
                ?: aeSupported.firstOrNull()
                ?: 30

            if (bestFps <= 30) return Pair(30, null)

            // 找到包含 bestFps 的最佳 Range：优先 upper == bestFps 的，其次 lower 最大的
            val bestRange = fpsRanges
                .filter { it.upper == bestFps }
                .maxByOrNull { it.lower }
                ?: fpsRanges.firstOrNull { it.upper >= bestFps }

            DebugLog.d(TAG, "实际使用帧率: ${bestFps}fps, AE Range: [${bestRange?.lower},${bestRange?.upper}]")
            return Pair(bestFps, bestRange)
        } catch (e: Exception) {
            DebugLog.w(TAG, "查询摄像头帧率失败，退回 30fps: ${e.message}")
            return Pair(requestedFps.coerceAtMost(30), null)
        }
    }

    /**
     * 切换镜头 — 使用上次绑定的参数重新绑定
     *
     * Surface 模式下需要重建 Camera2 会话（不同镜头需要不同的 cameraId）。
     *
     * @return 切换后的镜头，null 表示无法切换（没有上次的绑定参数）
     */
    suspend fun switchLens(lens: CameraLens): CameraLens? {
        // Surface 模式下无法简单切换（需要 encoder Surface），记录日志即可
        // 实际切换在退出再进入 Surface 模式时完成
        if (isSurfaceMode) {
            DebugLog.w(TAG, "Surface 模式下暂不支持镜头切换")
            return null
        }
        val owner = lastLifecycleOwner ?: return null
        val pv = lastPreviewView ?: return null
        val pipeline = lastFramePipeline

        bindPreview(
            lifecycleOwner = owner,
            previewView = pv,
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
     * 使用新的 profile 参数重新绑定摄像头
     *
     * 在用户切换分辨率/帧率时调用。使用缓存的 lifecycleOwner 和 previewView，
     * 只更新 encoderWidth/encoderHeight/fps。
     *
     * @return true 表示重新绑定成功，false 表示摄像头尚未绑定过
     */
    suspend fun rebindWithProfile(width: Int, height: Int, fps: Int): Boolean {
        // Surface 模式下不重新绑定 CameraX，帧率通过 updateSurfaceFps() 管理
        if (isSurfaceMode) {
            DebugLog.d(TAG, "Surface 模式下跳过 rebindWithProfile，改用 updateSurfaceFps")
            return true
        }
        val owner = lastLifecycleOwner ?: return false
        val pv = lastPreviewView ?: return false
        val pipeline = lastFramePipeline

        bindPreview(
            lifecycleOwner = owner,
            previewView = pv,
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
     * 使用 Camera2 API 绑定预览 + 编码器 Surface（4K@60fps 专用）
     *
     * CameraX ImageAnalysis 在 4K 分辨率下受 ISP YUV 输出带宽限制，无法达到 60fps。
     * 此方法绕过 CameraX，直接使用 Camera2 API 创建 CaptureSession，
     * 将相机输出同时发送到：
     * - PreviewView 的 Surface（预览显示）
     * - 编码器的 InputSurface（零拷贝硬件编码）
     *
     * 流程：
     * 1. unbindAll() 释放 CameraX（CameraX 和 Camera2 不能共享同一相机）
     * 2. 启动 Camera2 HandlerThread
     * 3. 获取 PreviewView 内部 TextureView 的 Surface
     * 4. openCamera() → createCaptureSession() → setRepeatingRequest()
     *
     * @param previewView  PreviewView（必须使用 COMPATIBLE 模式，内部为 TextureView）
     * @param lens         目标镜头
     * @param fps          目标帧率
     * @param encoderSurface 编码器 InputSurface（从 RingBufferRecorder.prepareWithSurface() 获取）
     */
    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bindPreviewWithSurface(
        previewView: PreviewView,
        lens: CameraLens = _currentLens.value,
        fps: Int = 60,
        encoderSurface: Surface,
    ) {
        val provider = cameraProvider
            ?: ProcessCameraProvider.getInstance(context).await().also {
                cameraProvider = it
            }

        // 记住绑定参数
        lastPreviewView = previewView
        lastFps = fps

        // 1. 释放 CameraX（CameraX 和 Camera2 不能同时持有同一相机）
        provider.unbindAll()

        // 2. 启动 Camera2 专用 HandlerThread
        stopCamera2Session() // 清理可能存在的旧会话
        val thread = HandlerThread("Camera2Session").apply { start() }
        val handler = Handler(thread.looper)
        camera2Thread = thread
        camera2Handler = handler

        // 3. 获取 cameraId（复用 Camera2CameraInfo 逻辑）
        val cameraSelector = resolveCameraSelector(lens)
        val cameraId = resolveCamera2Id(cameraSelector)
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // 4. 获取 PreviewView 内部的 TextureView Surface
        // COMPATIBLE 模式下 PreviewView 的第一个子 View 是 TextureView
        val previewSurface = getPreviewViewSurface(previewView)
            ?: throw IllegalStateException("无法获取 PreviewView 的 Surface，请确保使用 COMPATIBLE 模式且 View 已布局")

        // 5. 解析最佳 FPS Range
        // Surface 模式下必须强制使用精确帧率范围 [fps, fps]，否则 AE 会在范围内选较低值
        val actualRange = resolveSurfaceFpsRange(cameraId, fps)
        lastAppliedFps = actualRange.upper

        DebugLog.d(TAG, "Camera2 Surface 模式: cameraId=$cameraId, 请求fps=$fps, 实际Range=${actualRange}, 镜头=${lens.name}")

        // 6. 打开 Camera2 设备
        val device = openCamera2Device(cameraManager, cameraId, handler)
        camera2Device = device

        // 7. 创建 CaptureSession（预览 + 编码器双 Surface 输出）
        val surfaces = listOf(previewSurface, encoderSurface)
        camera2Surfaces = surfaces
        val session = createCaptureSession(device, surfaces, handler)
        camera2Session = session

        // 8. 构建 CaptureRequest 并设置 FPS Range
        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(encoderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, actualRange)
            // 自动对焦：连续视频模式
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            // 自动曝光
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            // 视频防抖 (EIS)
            val eisMode = if (videoStabilizationEnabled) {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            } else {
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            }
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisMode)
        }
        camera2RequestBuilder = requestBuilder

        session.setRepeatingRequest(requestBuilder.build(), null, handler)
        isSurfaceMode = true
        _currentLens.value = lens

        DebugLog.d(TAG, "Camera2 Surface 模式绑定完成: ${previewSurface}? + encoderSurface, fps=${actualRange}")
    }

    /**
     * 停止 Camera2 会话并释放资源
     *
     * 调用时机：
     * - 退出 4K@60fps 待机模式时
     * - 切换回 CameraX 模式前
     * - release() 时
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

        try {
            camera2Device?.close()
        } catch (_: Exception) {}
        camera2Device = null

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
     *
     * 从 CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES
     * 读取支持的模式列表，检查是否包含 CONTROL_VIDEO_STABILIZATION_MODE_ON。
     *
     * @return true 如果当前摄像头支持 EIS ON 模式
     */
    private fun checkEisSupportedForCurrentCamera(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = if (isSurfaceMode && camera2Device != null) {
                camera2Device!!.id
            } else if (cameraProvider != null) {
                resolveCamera2Id(resolveCameraSelector(_currentLens.value))
            } else {
                return false
            }

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
     *
     * 调用时机：
     * - 初始化完成后
     * - 镜头切换后
     * - 相机绑定后（不同设备 EIS 能力可能不同）
     */
    fun refreshEisCapability() {
        val supported = checkEisSupportedForCurrentCamera()
        _eisSupported.value = supported
        DebugLog.d(TAG, "EIS 能力已刷新: supported=$supported")
    }

    /**
     * 设置视频防抖模式
     *
     * @param enabled 是否开启防抖
     * @param isRecording 当前是否在录制中（录制中禁止切换）
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

        // Surface 模式下需立重建 CaptureRequest 使变更生效
        if (isSurfaceMode) {
            rebuildSurfaceCaptureRequest(videoStabilizationMode = mode)
        }
        // CameraX 模式下在下次 bindPreview() 时生效
    }

    /**
     * 重建 Surface 模式的 CaptureRequest
     *
     * 在热管理更新 FPS 或用户切换 EIS 时调用。
     * 确保所有目标 Surface 和参数被重新添加，EIS 模式不会因重建而丢失。
     *
     * @param fpsRange 新的 FPS Range，为 null 时保留当前值
     * @param videoStabilizationMode EIS 模式，默认使用 currentVideoStabilizationMode
     */
    private fun rebuildSurfaceCaptureRequest(
        fpsRange: android.util.Range<Int>? = null,
        videoStabilizationMode: Int = currentVideoStabilizationMode,
    ) {
        val session = camera2Session ?: return
        val device = camera2Device ?: return
        val handler = camera2Handler ?: return

        try {
            val cameraId = device.id
            val range = fpsRange ?: run {
                val fps = if (lastAppliedFps > 0) lastAppliedFps else 30
                resolveSurfaceFpsRange(cameraId, fps)
            }

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                camera2Surfaces.forEach { surface -> addTarget(surface) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, videoStabilizationMode)
            }
            camera2RequestBuilder = requestBuilder
            session.setRepeatingRequest(requestBuilder.build(), null, handler)
            lastAppliedFps = range.upper
            DebugLog.d(TAG, "Surface CaptureRequest 已重建: fpsRange=${range}, eisMode=$videoStabilizationMode")
        } catch (e: Exception) {
            DebugLog.w(TAG, "重建 Surface CaptureRequest 失败: ${e.message}")
        }
    }

    /**
     * 更新 Camera2 会话的 AE FPS Range（热管理降频时调用）
     *
     * Surface 模式下无法通过 skipPattern 跳帧，
     * 只能通过修改 AE FPS Range 来控制帧率。
     *
     * @param fps 目标帧率
     */
    fun updateSurfaceFps(fps: Int) {
        val cameraId = camera2Device?.id ?: return
        val range = resolveSurfaceFpsRange(cameraId, fps)
        rebuildSurfaceCaptureRequest(fpsRange = range)
    }

    // ---- Camera2 内部辅助方法 ----

    /**
     * 为 Camera2 Surface 模式解析最佳 FPS Range
     *
     * 策略（按优先级）：
     * 1. 精确匹配 [fps, fps] — 强制相机恒定输出目标帧率
     * 2. 包含 fps 的最窄范围 — 尽量减少 AE 波动
     * 3. upper >= fps 的任意范围 — 兜底
     * 4. 最终兜底：构造 [fps, fps]（部分设备即使不在列表中也能生效）
     */
    private fun resolveSurfaceFpsRange(cameraId: String, fps: Int): android.util.Range<Int> {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val chars = cameraManager.getCameraCharacteristics(cameraId)
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: emptyArray()

        DebugLog.d(TAG, "Surface FPS 解析: 可用范围=${fpsRanges.map { "[${it.lower},${it.upper}]" }}, 请求=$fps")

        // 1. 精确匹配 [fps, fps]
        val exactRange = fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
        if (exactRange != null) {
            DebugLog.d(TAG, "Surface FPS: 精确匹配 [${exactRange.lower},${exactRange.upper}]")
            return exactRange
        }

        // 2. 包含 fps 的最窄范围（lower 越大越窄 → AE 波动越小）
        val narrowRange = fpsRanges
            .filter { it.lower <= fps && it.upper >= fps }
            .maxByOrNull { it.lower }
        if (narrowRange != null) {
            DebugLog.d(TAG, "Surface FPS: 最窄包含范围 [${narrowRange.lower},${narrowRange.upper}]")
            return narrowRange
        }

        // 3. upper >= fps 的任意范围
        val anyRange = fpsRanges.firstOrNull { it.upper >= fps }
        if (anyRange != null) {
            DebugLog.d(TAG, "Surface FPS: 兜底范围 [${anyRange.lower},${anyRange.upper}]")
            return anyRange
        }

        // 4. 构造 [fps, fps]（部分设备即使不在支持列表中也能接受）
        DebugLog.w(TAG, "Surface FPS: 无匹配范围，使用构造值 [$fps,$fps]")
        return android.util.Range(fps, fps)
    }

    /**
     * 从 CameraSelector 解析 Camera2 cameraId
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun resolveCamera2Id(cameraSelector: CameraSelector): String {
        val provider = cameraProvider ?: throw IllegalStateException("CameraProvider 未初始化")
        val cameraInfo = provider.availableCameraInfos
            .first { cameraSelector.filter(listOf(it)).isNotEmpty() }
        return Camera2CameraInfo.from(cameraInfo).cameraId
    }

    /**
     * 获取 PreviewView 内部 TextureView 的 Surface
     *
     * PreviewView COMPATIBLE 模式内部使用 TextureView，
     * 通过 getChildAt(0) 获取并从中提取 Surface。
     */
    private fun getPreviewViewSurface(previewView: PreviewView): Surface? {
        if (previewView.childCount == 0) return null
        val child = previewView.getChildAt(0)
        if (child is TextureView && child.isAvailable) {
            return Surface(child.surfaceTexture)
        }
        DebugLog.w(TAG, "PreviewView 内部不是 TextureView 或未就绪: ${child?.javaClass?.simpleName}")
        return null
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

    // ---- 缩放控制方法 ----

    /**
     * 从绑定的 Camera 对象初始化缩放范围
     *
     * 每次绑定/重新绑定摄像头后调用，读取 min/max zoom ratio。
     * 同时将当前缩放重置为 1.0x（切换镜头后）。
     */
    /**
     * 从绑定的 Camera 对象初始化缩放范围
     *
     * 每次绑定/重新绑定摄像头后调用，读取 min/max zoom ratio。
     * 同时将当前缩放重置为 1.0x（切换镜头后）。
     */
    private fun initZoomFromCamera(camera: androidx.camera.core.Camera) {
        try {
            val zoomState = camera.cameraInfo.zoomState.value
            if (zoomState != null) {
                _maxZoomRatio.value = zoomState.maxZoomRatio
                DebugLog.d(TAG, "缩放范围: ${zoomState.minZoomRatio}x ~ ${zoomState.maxZoomRatio}x")
            } else {
                _maxZoomRatio.value = 1.0f
                DebugLog.w(TAG, "zoomState 为 null，缩放不可用")
            }
        } catch (e: Exception) {
            DebugLog.w(TAG, "读取缩放范围失败: ${e.message}")
            _maxZoomRatio.value = 1.0f
        }
        // 镜头切换/重新绑定时重置缩放
        _zoomRatio.value = 1.0f
        try {
            camera.cameraControl.setZoomRatio(1.0f)
        } catch (_: Exception) {}
    }

    /**
     * 应用缩放增量（双指捏合时调用）
     *
     * 从 zoomState 读取实际缩放值作为基准（非缓存值），
     * 避免 CameraX 物理相机切换后基准不准导致跳变。
     *
     * @param delta 缩放乘数（>1.0 放大，<1.0 缩小）
     */
    fun applyZoomDelta(delta: Float) {
        val camera = boundCamera ?: return
        if (isSurfaceMode) return

        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val actualRatio = zoomState.zoomRatio
        val newRatio = (actualRatio * delta).coerceIn(1.0f, _maxZoomRatio.value)
        if (kotlin.math.abs(newRatio - actualRatio) < 0.01f) return

        try {
            camera.cameraControl.setZoomRatio(newRatio)
            _zoomRatio.value = newRatio
        } catch (e: Exception) {
            DebugLog.w(TAG, "设置缩放失败: ${e.message}")
        }
    }

    /**
     * 解析镜头对应的 CameraSelector
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun resolveCameraSelector(lens: CameraLens): CameraSelector = when (lens) {
        CameraLens.WIDE -> CameraSelector.DEFAULT_BACK_CAMERA
        CameraLens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        CameraLens.ULTRA_WIDE -> CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { camInfo ->
                    val c2Info = Camera2CameraInfo.from(camInfo)
                    // 必须同时满足：后置 + 焦距 < 4mm（防止误匹配前摄）
                    val facing = c2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_FACING
                    )
                    val focalLengths = c2Info.getCameraCharacteristic(
                        CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                    )
                    facing == CameraCharacteristics.LENS_FACING_BACK
                        && focalLengths?.any { it < 4.0f } == true
                }
            }
            .build()
    }

    /**
     * 释放所有摄像头绑定
     */
    fun release() {
        stopCamera2Session()
        cameraProvider?.unbindAll()
        cameraProvider = null
        boundCamera = null
        _zoomRatio.value = 1.0f
        _maxZoomRatio.value = 1.0f
        analyzerExecutor.shutdownNow()
    }

    /**
     * 从 Context 中安全获取 Display
     */
    private fun resolveDisplay(ctx: Context): Display? {
        try {
            return ctx.display
        } catch (_: UnsupportedOperationException) {}

        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        return windowManager?.defaultDisplay
    }
}

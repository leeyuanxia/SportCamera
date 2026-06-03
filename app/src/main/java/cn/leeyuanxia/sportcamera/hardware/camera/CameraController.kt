package cn.leeyuanxia.sportcamera.hardware.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
import cn.leeyuanxia.sportcamera.util.DebugLog
import androidx.camera.camera2.interop.Camera2CameraInfo
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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

        val cameraSelector = resolveCameraSelector(lens)

        val targetRotation = try {
            resolveDisplay(context)?.rotation ?: Surface.ROTATION_0
        } catch (_: UnsupportedOperationException) {
            Surface.ROTATION_0
        }

        // Use Case 1: Preview → PreviewView
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

        provider.unbindAll()

        // Use Case 2: ImageAnalysis → 帧管线（如果提供）
        if (framePipeline != null) {
            val imageAnalysis = ImageAnalysis.Builder()
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
                .build()
            imageAnalysis.setAnalyzer(analyzerExecutor, framePipeline)

            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
        } else {
            provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
        }

        _currentLens.value = lens
    }

    /**
     * 切换镜头 — 使用上次绑定的参数重新绑定
     *
     * @return 切换后的镜头，null 表示无法切换（没有上次的绑定参数）
     */
    suspend fun switchLens(lens: CameraLens): CameraLens? {
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
        )
        return lens
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
        cameraProvider?.unbindAll()
        cameraProvider = null
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

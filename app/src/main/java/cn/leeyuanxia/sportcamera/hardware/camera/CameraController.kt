package cn.leeyuanxia.sportcamera.hardware.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.annotation.OptIn
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

    private var cameraProvider: ProcessCameraProvider? = null

    private val _currentLens = MutableStateFlow(CameraLens.DEFAULT)
    val currentLens: StateFlow<CameraLens> = _currentLens.asStateFlow()

    private val _availableLenses = MutableStateFlow<List<CameraLens>>(emptyList())
    val availableLenses: StateFlow<List<CameraLens>> = _availableLenses.asStateFlow()

    /** ImageAnalysis 回调用的单线程执行器 */
    private val analyzerExecutor: ExecutorService = Executors.newSingleThreadExecutor {
        Thread(it, "FrameAnalyzer").apply { isDaemon = true }
    }

    /**
     * 初始化 CameraProvider 并检测可用镜头
     */
    suspend fun initialize() {
        if (cameraProvider != null) return
        cameraProvider = ProcessCameraProvider.getInstance(context).await()
        try {
            detectAvailableLenses()
        } catch (e: Exception) {
            _availableLenses.value = listOf(CameraLens.WIDE)
        }
    }

    /**
     * 检测后置镜头类型
     */
    private fun detectAvailableLenses() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lenses = mutableListOf<CameraLens>()

        for (cameraId in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)

            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val focalLengths = chars.get(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            ) ?: floatArrayOf()

            if (focalLengths.any { it < 4.0f }) {
                lenses.add(CameraLens.ULTRA_WIDE)
            }
            lenses.add(CameraLens.WIDE)
        }

        _availableLenses.value = lenses
    }

    /**
     * 绑定摄像头预览 + 帧分析到 PreviewView 和编码管线
     *
     * 同时绑定两个 CameraX use case：
     * - Preview → PreviewView（屏幕显示）
     * - ImageAnalysis → framePipeline（帧数据送入编码器）
     *
     * @param framePipeline 帧管线，接收 Camera 帧并转发给编码器
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

        val cameraSelector = when (lens) {
            CameraLens.WIDE -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraLens.ULTRA_WIDE -> CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { camInfo ->
                        val c2Info = Camera2CameraInfo.from(camInfo)
                        val focalLengths = c2Info.getCameraCharacteristic(
                            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
                        )
                        focalLengths?.any { it < 4.0f } == true
                    }
                }
                .build()
        }

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
     * 切换镜头并重新绑定预览
     */
    suspend fun switchLens(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lens: CameraLens,
    ) {
        bindPreview(lifecycleOwner, previewView, lens)
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

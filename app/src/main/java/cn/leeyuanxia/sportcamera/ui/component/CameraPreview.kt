package cn.leeyuanxia.sportcamera.ui.component

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * CameraX PreviewView 的 Compose 封装
 *
 * 屏幕方向由 MainScreen 通过 Activity.requestedOrientation 动态控制：
 * - 选择竖屏 → Activity 设为竖屏，UI 全部竖排
 * - 选择横屏 → Activity 设为横屏，UI 全部横排
 *
 * CameraX PreviewView 会自动适配 Activity 的实际方向，无需手动旋转。
 *
 * 关键设计：
 * - 使用 TextureView（COMPATIBLE 模式），确保与 Compose 渲染管线兼容
 * - LaunchedEffect 依赖 configuration.orientation：Activity 旋转完成后配置才更新，
 *   此时重新绑定 Preview 以更新 targetRotation，避免方向不一致导致双重旋转
 * - rememberCoroutineScope + NonCancellable 确保 unbindAll + bindToLifecycle 原子执行
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    orientation: RecordOrientation = RecordOrientation.PORTRAIT,
    onBindCamera: suspend (PreviewView, LifecycleOwner, RecordOrientation) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // 使用独立的协程作用域：不受 recomposition 取消影响
    val scope = rememberCoroutineScope()

    // State 持有 PreviewView 引用，factory 创建后触发绑定
    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    // 监听 Configuration 变化 — Activity 真正旋转完成后 orientation 才变化
    val configuration = LocalConfiguration.current

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    // 强制使用 TextureView：SurfaceView 渲染在独立 window surface 上，
                    // 某些场景下与 Compose 渲染管线不兼容
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = it
                }
            },
        )
    }

    // 依赖 previewView 和 configuration.orientation
    //
    // configuration.orientation 在 Activity 真正旋转后才更新，
    // 此时 resolveDisplay 能获取到正确的 display rotation，
    // 避免旧的 targetRotation 与新的屏幕方向不一致导致双重旋转。
    //
    // 不直接依赖 orientation 参数：
    // orientation 变化时 Activity 还没旋转完，display rotation 仍是旧值，
    // 此时绑定会用错误的 targetRotation。
    LaunchedEffect(previewView, configuration.orientation) {
        val pv = previewView ?: return@LaunchedEffect
        scope.launch {
            withContext(NonCancellable) {
                try {
                    onBindCamera(pv, lifecycleOwner, orientation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}

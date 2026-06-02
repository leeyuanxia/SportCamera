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
 * 屏幕方向由 MainScreen 通过 Activity.requestedOrientation 动态控制。
 *
 * @param isVisible 预览是否可见。不可见时用全黑覆盖（OLED 不发光省电），
 *   CameraX 内部仍保持绑定不断流。
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    orientation: RecordOrientation = RecordOrientation.PORTRAIT,
    isVisible: Boolean = true,
    onBindCamera: suspend (PreviewView, LifecycleOwner, RecordOrientation) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var previewView by remember { mutableStateOf<PreviewView?>(null) }

    val configuration = LocalConfiguration.current

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    previewView = it
                }
            },
        )

        // 预览隐藏时用全黑覆盖 — OLED 屏幕不发光，最省电
        if (!isVisible) {
            Box(modifier = Modifier.matchParentSize().background(Color.Black))
        }
    }

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

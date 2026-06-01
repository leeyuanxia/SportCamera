package cn.leeyuanxia.sportcamera.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cn.leeyuanxia.sportcamera.ui.theme.SportCameraTheme
import cn.leeyuanxia.sportcamera.ui.screen.MainScreen
import cn.leeyuanxia.sportcamera.viewmodel.CameraViewModel

/**
 * 主 Activity — Compose 入口
 *
 * 竖屏全屏运行（Manifest 已配置 portrait + fullscreen theme）
 *
 * 注意：viewModel.initialize() 不在 onCreate 中调用，
 * 而是在权限授予后由 MainScreen 回调触发，
 * 避免在无权限时访问 CameraManager 导致 SecurityException 崩溃。
 */
class MainActivity : ComponentActivity() {

    private lateinit var viewModel: CameraViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        viewModel = CameraViewModel(application)

        setContent {
            SportCameraTheme {
                MainScreen(viewModel)
            }
        }

        // 不在这里调用 viewModel.initialize()！
        // 权限授予前调用 cameraController.initialize() → CameraManager.getCameraCharacteristics()
        // 会抛出 SecurityException 导致崩溃。初始化由 MainScreen 在权限授予后触发。
    }

    override fun onDestroy() {
        super.onDestroy()
        // ViewModel.onCleared() 会自动释放资源
    }
}
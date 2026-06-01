package cn.leeyuanxia.sportcamera.ui.screen

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.isRecording
import cn.leeyuanxia.sportcamera.ui.component.CameraPreview
import cn.leeyuanxia.sportcamera.ui.component.ControlBar
import cn.leeyuanxia.sportcamera.ui.component.RecordIndicator
import cn.leeyuanxia.sportcamera.ui.component.StatusBar
import cn.leeyuanxia.sportcamera.viewmodel.CameraViewModel

/**
 * 主界面 — 全屏竖屏布局
 *
 * 层级结构：
 * Layer 1: 全屏 Camera 预览
 * Layer 2: 顶部状态栏（半透明）
 * Layer 3: 中央录制指示器（仅录制时显示）
 * Layer 4: 底部控制栏（半透明）
 */
@Composable
fun MainScreen(viewModel: CameraViewModel) {
    val appState by viewModel.appState.collectAsState()
    val preRecordDuration by viewModel.preRecordDuration.collectAsState()
    val currentLens by viewModel.currentLens.collectAsState()
    val availableLenses by viewModel.availableLenses.collectAsState()
    val resolutionProfile by viewModel.resolutionProfile.collectAsState()
    val recordOrientation by viewModel.recordOrientation.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()

    val context = LocalContext.current
    val activity = context as? ComponentActivity

    // 屏幕亮度控制：录制时保持屏幕亮
    DisposableEffect(appState) {
        if (appState.isRecording) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 动态方向控制：根据录制方向切换 Activity 屏幕方向
    // 竖屏 → SCREEN_ORIENTATION_PORTRAIT，横屏 → SCREEN_ORIENTATION_LANDSCAPE
    // 这会让整个 UI（StatusBar、ControlBar、系统导航栏）全部旋转，
    // 比 graphicsLayer 假旋转更彻底、触摸事件也正确
    DisposableEffect(recordOrientation) {
        activity?.requestedOrientation = when (recordOrientation) {
            cn.leeyuanxia.sportcamera.domain.model.RecordOrientation.PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            cn.leeyuanxia.sportcamera.domain.model.RecordOrientation.LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            // 离开时恢复竖屏
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // 权限检查 — 权限授予后才初始化摄像头和 KWS 模块
    PermissionGate(
        onPermissionsGranted = {
            // 关键修复：权限授予后才初始化，避免 SecurityException 崩溃
            viewModel.initialize()
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Layer 1: 全屏取景器
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                orientation = recordOrientation,
                onBindCamera = { previewView, lifecycleOwner, orientation ->
                    viewModel.bindCamera(lifecycleOwner, previewView, orientation)
                },
            )

            // Layer 2: 顶部状态栏
            StatusBar(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .statusBarsPadding(),
                appState = appState,
                preRecordDuration = preRecordDuration,
                selectedLens = currentLens,
                batteryLevel = batteryLevel,
            )

            // Layer 3: 中央录制指示器
            RecordIndicator(
                modifier = Modifier.align(Alignment.Center),
                appState = appState,
            )

            // Layer 4: 底部控制栏
            ControlBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                appState = appState,
                selectedDuration = preRecordDuration,
                availableLenses = availableLenses,
                currentLens = currentLens,
                selectedProfile = resolutionProfile,
                selectedOrientation = recordOrientation,
                onDurationChanged = viewModel::setPreRecordDuration,
                onLensSwitch = viewModel::switchLens,
                onResolutionChanged = viewModel::setResolutionProfile,
                onOrientationChanged = viewModel::setRecordOrientation,
                onStartStandby = viewModel::startStandby,
                onStopStandby = viewModel::stopStandby,
            )
        }
    }
}

/**
 * 权限门 — 使用 ActivityResult API，权限结果自动触发 recomposition
 *
 * 修复原 Bug：原实现用普通 val allGranted（Compose 不追踪）+ ActivityCompat.requestPermissions（无回调通知），
 * 导致授权后 App 卡住不动。
 *
 * 现改为：
 * - mutableStateOf 持有权限状态 → 授权结果自动触发 recomposition
 * - rememberLauncherForActivityResult 替代 ActivityCompat → 结果回调写入 State
 * - LaunchedEffect 自动弹出系统权限对话框（省一次点击）
 * - onPermissionsGranted 回调：权限首次授予时触发，用于延迟初始化摄像头等需要权限的模块
 */
@Composable
private fun PermissionGate(
    onPermissionsGranted: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val permissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    // 关键修复：用 mutableStateOf 持有权限状态，Launcher 回调更新它 → 自动 recomposition
    var allGranted by remember {
        mutableStateOf(
            permissions.all {
                PackageManager.PERMISSION_GRANTED == context.checkSelfPermission(it)
            }
        )
    }

    // 记录是否已经触发过初始化回调（避免 recomposition 时重复调用）
    var hasNotified by remember { mutableStateOf(allGranted) }

    // 关键修复：ActivityResult Launcher 替代 ActivityCompat.requestPermissions
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result: Map<String, Boolean> ->
        allGranted = result.values.all { it }
    }

    // 权限状态变化时触发回调（首次授予时通知外部初始化）
    LaunchedEffect(allGranted) {
        if (allGranted && !hasNotified) {
            hasNotified = true
            onPermissionsGranted()
        }
    }

    // 首次进入时若未授权，自动弹出系统权限对话框（用户体验更顺畅）
    LaunchedEffect(Unit) {
        if (!allGranted) {
            permissionLauncher.launch(permissions)
        }
    }

    if (allGranted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "需要以下权限才能运行：",
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(16.dp))
            permissions.forEach { perm ->
                val label = when (perm) {
                    Manifest.permission.CAMERA -> "📷 摄像头"
                    Manifest.permission.RECORD_AUDIO -> "🎤 麦克风"
                    Manifest.permission.POST_NOTIFICATIONS -> "🔔 通知"
                    else -> perm
                }
                Text(label, color = Color.White)
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { permissionLauncher.launch(permissions) }
            ) {
                Text("授权")
            }
        }
    }
}
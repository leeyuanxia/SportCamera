package cn.leeyuanxia.sportcamera.ui.screen

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.isRecording
import cn.leeyuanxia.sportcamera.ui.component.CameraPreview
import cn.leeyuanxia.sportcamera.ui.component.ControlBar
import cn.leeyuanxia.sportcamera.ui.component.RecordIndicator
import cn.leeyuanxia.sportcamera.ui.component.StandbyOverlay
import cn.leeyuanxia.sportcamera.ui.component.StatusBar
import cn.leeyuanxia.sportcamera.viewmodel.CameraViewModel

/**
 * 主界面 — 全屏布局
 *
 * 层级结构：
 * Layer 0: 全黑背景（OLED 省电）
 * Layer 1: 相机预览（条件显示）
 * Layer 2: 待机叠加层（省电状态指示）
 * Layer 3: 顶部状态栏
 * Layer 4: 中央录制指示器
 * Layer 5: 底部控制栏
 *
 * 省电策略：
 * - 待机模式：预览隐藏 + 屏幕最低亮度常亮 + UI 可全部隐藏（OLED 全黑）
 * - 录制模式：预览显示 + 屏幕正常亮度
 * - 点击屏幕可唤醒被隐藏的 UI
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
    val supportedFps by viewModel.supportedFps.collectAsState()
    val previewVisible by viewModel.previewVisible.collectAsState()
    val uiVisible by viewModel.uiVisible.collectAsState()
    val videoStabilization by viewModel.videoStabilization.collectAsState()
    val eisSupported by viewModel.eisSupported.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val maxZoomRatio by viewModel.maxZoomRatio.collectAsState()
    val minZoomRatio by viewModel.minZoomRatio.collectAsState()
    val isPhysicalCameraMode by viewModel.isPhysicalCameraMode.collectAsState()

    val context = LocalContext.current
    val activity = context as? ComponentActivity

    val isActive = appState is AppState.Standby || appState.isRecording

    // 屏幕亮度和常亮控制
    DisposableEffect(isActive, appState) {
        val window = activity?.window
        if (isActive) {
            // 待机和录制时：屏幕常亮
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 设置最低亮度（不修改系统设置，只修改当前窗口）
            val params = window?.attributes
            params?.screenBrightness = if (appState.isRecording) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // 录制时恢复系统默认
            } else {
                0.01f // 待机时最低亮度
            }
            window?.attributes = params
        } else {
            // 非活跃状态：清除常亮标志，恢复系统亮度
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window?.attributes
            params?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window?.attributes = params
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val params = window?.attributes
            params?.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window?.attributes = params
        }
    }

    // 动态方向控制
    DisposableEffect(recordOrientation) {
        activity?.requestedOrientation = when (recordOrientation) {
            cn.leeyuanxia.sportcamera.domain.model.RecordOrientation.PORTRAIT ->
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            cn.leeyuanxia.sportcamera.domain.model.RecordOrientation.LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // 权限检查
    PermissionGate(
        onPermissionsGranted = {
            viewModel.initialize()
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                // 双指捏合缩放：在预览画面上控制缩放倍率（物理相机模式下禁用）
                .pointerInput(minZoomRatio, maxZoomRatio, isPhysicalCameraMode) {
                    detectTransformGestures { _, _, zoomChange, _ ->
                        if (isPhysicalCameraMode) return@detectTransformGestures
                        // 允许缩放：有放大空间 (maxZoomRatio > 1.0) 或有超广角空间 (minZoomRatio < 1.0)
                        if (zoomChange != 1.0f && (maxZoomRatio > 1.0f || minZoomRatio < 1.0f)) {
                            // 阻尼：将原始变化量向 1.0 压缩，手势更细腻
                            val damped = 1.0f + (zoomChange - 1.0f) * 0.4f
                            viewModel.applyZoomDelta(damped)
                        }
                    }
                }
                // 点击预览画面切换 UI 显示/隐藏
                .pointerInput(Unit) {
                    detectTapGestures { viewModel.toggleUi() }
                },
        ) {
            // Layer 1: 相机预览（条件显示）
            // 不论是否可见，CameraPreview 始终存在（保持 Camera2 会话）
            // 通过 alpha 控制可见性，隐藏时完全透明（OLED 不发光）
            CameraPreview(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (previewVisible) Modifier
                        else Modifier.background(Color.Black) // 隐藏时用黑底覆盖
                    ),
                orientation = recordOrientation,
                onBindCamera = { textureView, orientation ->
                    viewModel.bindCamera(textureView, orientation)
                },
                isVisible = previewVisible,
            )

            // 仅在活跃状态（待机/录制）时显示以下层
            if (uiVisible) {
                // Layer 2: 待机叠加层（预览隐藏时的状态指示）
                if (appState is AppState.Standby && !previewVisible) {
                    StandbyOverlay(
                        modifier = Modifier.fillMaxSize(),
                        appState = appState,
                        batteryLevel = batteryLevel,
                    )
                }

                // Layer 3: 顶部状态栏 + 镜头切换
                StatusBar(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .then(
                            if (appState is AppState.Standby)
                                Modifier.statusBarsPadding()
                            else Modifier.statusBarsPadding()
                        ),
                    appState = appState,
                    preRecordDuration = preRecordDuration,
                    availableLenses = availableLenses,
                    currentLens = currentLens,
                    zoomRatio = zoomRatio,
                    batteryLevel = batteryLevel,
                    onLensSwitch = viewModel::switchLens,
                )

                // Layer 4: 中央录制指示器
                RecordIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    appState = appState,
                )

                // Layer 5: 底部控制栏
                ControlBar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    appState = appState,
                    selectedDuration = preRecordDuration,
                    selectedProfile = resolutionProfile,
                    selectedOrientation = recordOrientation,
                    supportedFps = supportedFps,
                    videoStabilization = videoStabilization,
                    eisSupported = eisSupported,
                    previewVisible = previewVisible,
                    onStartStandby = viewModel::startStandby,
                    onStopStandby = viewModel::stopStandby,
                    onPeekPreview = viewModel::peekPreview,
                    onToggleUi = viewModel::toggleUi,
                    onDurationChanged = viewModel::setPreRecordDuration,
                    onResolutionChanged = viewModel::setResolutionProfile,
                    onOrientationChanged = viewModel::setRecordOrientation,
                    onVideoStabilizationChanged = viewModel::setVideoStabilization,
                    onRequestBatteryOptimization = {
                        viewModel.requestBatteryOptimization(context)
                    },
                    needsBatteryOptimization = viewModel.needsBatteryOptimization,
                )
            }
        }
    }
}

/**
 * 权限门
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

    var allGranted by remember {
        mutableStateOf(
            permissions.all {
                PackageManager.PERMISSION_GRANTED == context.checkSelfPermission(it)
            }
        )
    }

    var hasNotified by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result: Map<String, Boolean> ->
        allGranted = result.values.all { it }
    }

    androidx.compose.runtime.LaunchedEffect(allGranted) {
        if (allGranted && !hasNotified) {
            hasNotified = true
            onPermissionsGranted()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
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

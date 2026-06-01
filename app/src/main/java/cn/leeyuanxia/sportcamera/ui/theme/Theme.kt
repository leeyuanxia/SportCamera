package cn.leeyuanxia.sportcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// 运动相机 — 深色主题（默认且唯一主题）
private val DarkColorScheme = darkColorScheme(
    primary = StatusStandby,
    onPrimary = Black,
    secondary = StatusRecording,
    onSecondary = Black,
    tertiary = StatusSaving,
    background = Black,
    onBackground = TextPrimary,
    surface = DarkGray,
    onSurface = TextPrimary,
    surfaceVariant = MediumGray,
    onSurfaceVariant = TextSecondary,
)

@Composable
fun SportCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = SportCameraTypography,
        content = content,
    )
}
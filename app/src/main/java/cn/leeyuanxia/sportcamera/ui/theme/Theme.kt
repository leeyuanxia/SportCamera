package cn.leeyuanxia.sportcamera.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SportCameraColorScheme = darkColorScheme(
    primary = AccentAmber,
    onPrimary = Black,
    secondary = StatusSaving,
    onSecondary = Black,
    tertiary = StatusRecording,
    background = Black,
    onBackground = TextPrimary,
    surface = DarkGray,
    onSurface = TextPrimary,
    surfaceVariant = MediumGray,
    onSurfaceVariant = TextSecondary,
    error = StatusError,
    onError = Black,
)

@Composable
fun SportCameraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SportCameraColorScheme,
        typography = SportCameraTypography,
        content = content,
    )
}

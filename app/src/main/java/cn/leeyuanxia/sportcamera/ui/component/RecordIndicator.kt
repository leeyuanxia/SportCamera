package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording

/**
 * 中央录制指示器 — 录制时显示红色圆点 + 倒计时
 */
@Composable
fun RecordIndicator(
    appState: AppState,
    modifier: Modifier = Modifier,
) {
    if (appState !is AppState.Recording) return

    // 使用浮点除法显示十进制秒数（如 "2.5/5" 而非 "2/5"）
    val elapsedSec = appState.elapsedMs / 1000.0
    val targetSec = appState.targetMs / 1000  // 目标值始终是整数秒，无需小数

    // 脉冲缩放动画
    val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 红色圆点
        Box(
            modifier = Modifier
                .size(20.dp)
                .scale(scale)
                .background(StatusRecording, CircleShape),
        )
        Spacer(Modifier.height(8.dp))
        // 倒计时（已用 / 总时长，已用显示一位小数）
        Text(
            text = "${"%.1f".format(elapsedSec)} / $targetSec",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}
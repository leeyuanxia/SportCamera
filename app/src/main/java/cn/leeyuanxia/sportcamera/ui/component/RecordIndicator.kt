package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.ui.theme.AccentAmber
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording

/**
 * 中央录制指示器 — 录制时显示红色脉冲 + 倒计时，动态照片时显示琥珀色闪烁
 */
@Composable
fun RecordIndicator(
    appState: AppState,
    modifier: Modifier = Modifier,
) {
    when (appState) {
        is AppState.Recording -> RecordIndicatorContent(appState, modifier)
        is AppState.CapturingPhoto -> MotionPhotoIndicatorContent(appState.progress, modifier)
        else -> return
    }
}

/**
 * 录制指示器 — 红色圆点 + 脉冲光环 + 倒计时
 */
@Composable
private fun RecordIndicatorContent(
    appState: AppState.Recording,
    modifier: Modifier = Modifier,
) {
    val elapsedSec = appState.elapsedMs / 1000.0
    val targetSec = appState.targetMs / 1000
    val progress = (appState.elapsedMs.toFloat() / appState.targetMs).coerceIn(0f, 1f)

    val infiniteTransition = rememberInfiniteTransition(label = "recordPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 录制图标：脉冲光环 + 红点
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 外层脉冲光环
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(ringScale)
                    .border(2.dp, StatusRecording.copy(alpha = ringAlpha), CircleShape),
            )
            // 内层红点
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .scale(dotScale)
                    .background(StatusRecording, CircleShape),
            )
        }

        Spacer(Modifier.height(16.dp))

        // 倒计时 — 大字醒目
        Text(
            text = formatTime(elapsedSec, targetSec),
            color = Color.White,
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        // 进度文字
        Text(
            text = "${(progress * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 动态照片指示器 — 琥珀色圆环 + 白色闪烁 + "动态照片" 文字
 */
@Composable
private fun MotionPhotoIndicatorContent(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "motionPhotoPulse")
    val ringScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringScale",
    )
    val ringAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(400),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 动态照片图标：琥珀色脉冲光环 + 白色闪烁圆点
        Box(
            modifier = Modifier.size(56.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 外层脉冲光环
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .scale(ringScale)
                    .border(2.dp, AccentAmber.copy(alpha = ringAlpha), CircleShape),
            )
            // 内层圆点（白色闪烁）
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(Color.White.copy(alpha = dotAlpha), CircleShape),
            )
        }

        Spacer(Modifier.height(16.dp))

        // "动态照片" 标签
        Text(
            text = "动态照片",
            color = AccentAmber,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        Spacer(Modifier.height(4.dp))

        // 进度文字
        Text(
            text = "${(progress * 100).toInt()}%",
            color = Color.White.copy(alpha = 0.4f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 格式化倒计时显示
 * 还剩不足 5 秒时高亮显示
 */
private fun formatTime(elapsedSec: Double, targetSec: Long): String {
    val remaining = targetSec - elapsedSec
    return if (remaining <= 5) {
        // 最后 5 秒：显示剩余秒数（一位小数）
        "%.1f".format(remaining.coerceAtLeast(0.0))
    } else {
        // 正常：已用 / 总时长
        "${"%.1f".format(elapsedSec)} / $targetSec"
    }
}

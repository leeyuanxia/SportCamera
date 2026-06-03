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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.ui.theme.StatusStandby

/**
 * 待机叠加层 — 极简 OLED 省电显示
 *
 * 预览隐藏时显示，全黑背景中央几像素的内容。
 */
@Composable
fun StandbyOverlay(
    appState: AppState,
    batteryLevel: Int,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "standbyBreath")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathAlpha",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        // 呼吸灯圆点
        Box(
            modifier = Modifier
                .size(6.dp)
                .alpha(dotAlpha)
                .background(StatusStandby, CircleShape),
        )

        Spacer(Modifier.height(16.dp))

        // 状态文字
        Text(
            text = "监听中",
            color = Color.White.copy(alpha = 0.3f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Light,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "$batteryLevel%",
            color = Color.White.copy(alpha = 0.15f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Light,
        )
    }
}

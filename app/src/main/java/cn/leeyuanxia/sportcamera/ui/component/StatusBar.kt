package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.label
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.ui.theme.StatusIdle
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording
import cn.leeyuanxia.sportcamera.ui.theme.StatusSaving
import cn.leeyuanxia.sportcamera.ui.theme.StatusStandby

/**
 * 顶部状态栏 — 半透明黑底，显示状态灯和信息
 *
 * 显示内容：
 * - 状态指示灯（黄=待机/红=录制/蓝=保存/灰=未启动）
 * - 状态文字
 * - 预录时长
 * - 镜头类型
 * - 电池电量
 */
@Composable
fun StatusBar(
    appState: AppState,
    preRecordDuration: PreRecordDuration,
    selectedLens: CameraLens,
    batteryLevel: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左侧: 状态灯 + 状态文字
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(appState)
            Spacer(Modifier.width(8.dp))
            Text(
                text = appState.label,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 中间: 预录时长
        Text(
            text = "⏱ ${preRecordDuration.label}预录",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )

        // 右侧: 镜头 + 电量
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "📷 ${selectedLens.label}",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "🔋 $batteryLevel%",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 状态指示灯 — 录制时闪烁动画
 */
@Composable
private fun StatusDot(appState: AppState) {
    val dotColor = when (appState) {
        AppState.Idle -> StatusIdle
        AppState.Standby -> StatusStandby
        is AppState.Recording -> StatusRecording
        is AppState.Saving -> StatusSaving
        is AppState.Error -> StatusRecording
    }

    // 录制中闪烁动画
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Box(
        modifier = Modifier
            .size(10.dp)
            .background(dotColor, CircleShape)
            .then(
                if (appState is AppState.Recording) Modifier.alpha(alpha)
                else Modifier
            ),
    )
}
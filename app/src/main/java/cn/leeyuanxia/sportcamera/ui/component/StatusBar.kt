package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.label
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelected
import cn.leeyuanxia.sportcamera.ui.theme.ChipUnselected
import cn.leeyuanxia.sportcamera.ui.theme.StatusIdle
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording
import cn.leeyuanxia.sportcamera.ui.theme.StatusSaving
import cn.leeyuanxia.sportcamera.ui.theme.StatusStandby

/**
 * 顶部状态栏 — 半透明黑底，显示状态灯和信息
 *
 * 显示内容：
 * - 第一行：状态灯 + 状态文字 | 预录时长 | 电量
 * - 第二行：镜头选择 Chips（标准 / 前置 / 广角）
 */
@Composable
fun StatusBar(
    appState: AppState,
    preRecordDuration: PreRecordDuration,
    availableLenses: List<CameraLens>,
    currentLens: CameraLens,
    batteryLevel: Int,
    onLensSwitch: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        // 第一行：状态信息
        Row(
            modifier = Modifier.fillMaxWidth(),
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

            // 右侧: 电量
            Text(
                text = "🔋 $batteryLevel%",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 第二行：镜头选择 Chips（仅有多镜头时显示）
        if (availableLenses.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CameraLens.entries.forEach { lens ->
                    // 不在可用列表中的镜头，灰显不可点击
                    val isAvailable = lens in availableLenses
                    val isSelected = lens == currentLens && isAvailable

                    LensChip(
                        label = lens.label,
                        isSelected = isSelected,
                        enabled = isAvailable,
                        onClick = { onLensSwitch(lens) },
                    )
                    if (lens != CameraLens.entries.last()) {
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

/**
 * 镜头选择 Chip
 */
@Composable
private fun LensChip(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = when {
        isSelected -> ChipSelected
        enabled -> ChipUnselected
        else -> Color.White.copy(alpha = 0.08f)
    }
    val textColor = when {
        isSelected -> Color.Black
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(
                    1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp)
                ) else Modifier
            )
            .then(
                if (enabled) Modifier.clickable { onClick() }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.bodySmall)
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

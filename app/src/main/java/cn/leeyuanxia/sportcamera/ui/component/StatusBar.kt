package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.label
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelected
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelectedText
import cn.leeyuanxia.sportcamera.ui.theme.ChipUnselected
import cn.leeyuanxia.sportcamera.ui.theme.StatusIdle
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording
import cn.leeyuanxia.sportcamera.ui.theme.StatusSaving
import cn.leeyuanxia.sportcamera.ui.theme.StatusStandby
import cn.leeyuanxia.sportcamera.ui.theme.SurfaceOverlay
import cn.leeyuanxia.sportcamera.ui.theme.TextPrimary
import cn.leeyuanxia.sportcamera.ui.theme.TextSecondary

/**
 * 顶部状态栏 — 毛玻璃效果，显示状态灯和核心信息
 */
@Composable
fun StatusBar(
    appState: AppState,
    preRecordDuration: PreRecordDuration,
    availableLenses: List<CameraLens>,
    currentLens: CameraLens,
    zoomRatio: Float,
    batteryLevel: Int,
    onLensSwitch: (CameraLens) -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    Column(
        modifier = modifier
            .background(SurfaceOverlay)
            .padding(
                horizontal = if (isLandscape) 10.dp else 16.dp,
                vertical = if (isLandscape) 4.dp else 8.dp,
            ),
    ) {
        // 第一行：状态灯 + 电量 + 时长
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：状态灯 + 文字 + 缩放倍率
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(appState)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = appState.label,
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                // 缩放倍率指示（非标准 1.0x 时显示，包括超广角 0.5x）
                if (kotlin.math.abs(zoomRatio - 1.0f) > 0.05f) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ChipSelected)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = String.format("%.1fx", zoomRatio),
                            color = ChipSelectedText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 右侧：时长 + 电量
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "${preRecordDuration.label}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "$batteryLevel%",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // 第二行：镜头选择（仅多镜头时显示）
        if (availableLenses.size > 1) {
            // 横屏只显示可用镜头，竖屏显示全部（不可用的灰显）
            val displayLenses = if (isLandscape) availableLenses else CameraLens.entries
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (isLandscape) 3.dp else 6.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                displayLenses.forEachIndexed { index, lens ->
                    val isAvailable = lens in availableLenses
                    val isSelected = when {
                        !isAvailable -> false
                        currentLens == CameraLens.FRONT -> lens == CameraLens.FRONT
                        currentLens == CameraLens.ULTRA_WIDE -> lens == CameraLens.ULTRA_WIDE
                        lens == CameraLens.ULTRA_WIDE -> zoomRatio < 0.95f
                        lens == CameraLens.WIDE -> zoomRatio >= 0.95f
                        else -> lens == currentLens
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(if (isLandscape) 8.dp else 12.dp))
                            .background(
                                when {
                                    isSelected -> ChipSelected
                                    isAvailable -> ChipUnselected
                                    else -> Color.White.copy(alpha = 0.06f)
                                }
                            )
                            .then(
                                if (isAvailable) Modifier.clickable { onLensSwitch(lens) }
                                else Modifier
                            )
                            .padding(
                                horizontal = if (isLandscape) 6.dp else 10.dp,
                                vertical = if (isLandscape) 2.dp else 4.dp,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = lens.label,
                            color = when {
                                isSelected -> ChipSelectedText
                                isAvailable -> TextPrimary
                                else -> TextSecondary.copy(alpha = 0.3f)
                            },
                            style = if (isLandscape) MaterialTheme.typography.labelSmall
                            else MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }

                    if (index < displayLenses.lastIndex) {
                        Spacer(Modifier.width(if (isLandscape) 3.dp else 6.dp))
                    }
                }
            }
        }
    }
}

/**
 * 状态指示灯
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

    // 录制和待机时呼吸动画
    val shouldAnimate = appState is AppState.Recording || appState is AppState.Standby
    val infiniteTransition = rememberInfiniteTransition(label = "statusDot")
    val animAlpha by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (appState is AppState.Recording) 0.2f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (appState is AppState.Recording) 600 else 1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Box(
        modifier = Modifier
            .size(8.dp)
            .background(dotColor, CircleShape)
            .then(if (shouldAnimate) Modifier.alpha(animAlpha) else Modifier),
    )
}

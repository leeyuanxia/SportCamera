package cn.leeyuanxia.sportcamera.ui.component

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.model.CameraLens
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelected
import cn.leeyuanxia.sportcamera.ui.theme.ChipUnselected

/**
 * 底部控制栏
 *
 * 包含：
 * - 预录时长：预设 Chips + 自定义 Slider
 * - 录制方向：横屏/竖屏 Chips
 * - 分辨率档位选择
 * - 镜头切换按钮
 * - 待机启停按钮
 * - 预览窥视按钮（待机时显示 30s 预览）
 * - UI 显隐切换按钮
 * - 电池优化白名单按钮
 */
@Composable
fun ControlBar(
    appState: AppState,
    selectedDuration: PreRecordDuration,
    availableLenses: List<CameraLens>,
    currentLens: CameraLens,
    selectedProfile: ResolutionProfile,
    selectedOrientation: RecordOrientation,
    previewVisible: Boolean,
    onStartStandby: () -> Unit,
    onStopStandby: () -> Unit,
    onPeekPreview: () -> Unit,
    onToggleUi: () -> Unit,
    onDurationChanged: (PreRecordDuration) -> Unit,
    onLensSwitch: (CameraLens) -> Unit,
    onResolutionChanged: (ResolutionProfile) -> Unit,
    onOrientationChanged: (RecordOrientation) -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    needsBatteryOptimization: Boolean,
    modifier: Modifier = Modifier,
) {
    val isStandby = appState is AppState.Standby
    val isRecording = appState is AppState.Recording
    val isIdle = appState is AppState.Idle

    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // ===== 待机模式：精简控制 =====
        if (isStandby) {
            // 上排：窥视预览 + UI 隐藏 + 电池优化 + 停止
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 窥视预览按钮
                ActionChip(
                    label = if (previewVisible) "👁 预览中" else "👁 预览",
                    onClick = onPeekPreview,
                )

                // UI 隐藏按钮（省电）
                ActionChip(
                    label = "🌑 隐藏界面",
                    onClick = onToggleUi,
                )

                // 电池优化白名单（未优化时显示）
                if (needsBatteryOptimization) {
                    ActionChip(
                        label = "🔋 省电",
                        onClick = onRequestBatteryOptimization,
                    )
                }

                Spacer(Modifier.weight(1f))

                // 停止按钮
                ActionChip(
                    label = "⏹ 停止",
                    onClick = onStopStandby,
                    bgColor = Color.Red.copy(alpha = 0.7f),
                )
            }
        } else {
            // ===== 空闲 / 录制模式：完整控制 =====

            // 上排：预录时长 Chips + Slider
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PreRecordDuration.PRESETS.forEach { preset ->
                        DurationChip(
                            label = preset.label,
                            isSelected = preset == selectedDuration,
                            onClick = { onDurationChanged(preset) },
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                var sliderValue by remember(selectedDuration) {
                    mutableFloatStateOf(selectedDuration.seconds.toFloat())
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "预录时长",
                            color = Color.White,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "${sliderValue.toInt()}s",
                            color = ChipSelected,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Slider(
                        value = sliderValue,
                        onValueChange = { v ->
                            sliderValue = v
                            onDurationChanged(PreRecordDuration(v.toInt()))
                        },
                        valueRange = PreRecordDuration.MIN.toFloat()..PreRecordDuration.MAX.toFloat(),
                        steps = (PreRecordDuration.MAX - PreRecordDuration.MIN) - 1,
                        colors = SliderDefaults.colors(
                            thumbColor = ChipSelected,
                            activeTrackColor = ChipSelected,
                            inactiveTrackColor = ChipUnselected,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 中排：录制方向 Chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "方向",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.width(4.dp))
                RecordOrientation.entries.forEach { orientation ->
                    OrientationChip(
                        label = orientation.label,
                        isSelected = orientation == selectedOrientation,
                        onClick = { onOrientationChanged(orientation) },
                    )
                }
            }

            // 下排：分辨率 + 镜头 + 待机/停止
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ResolutionProfile.entries.forEach { profile ->
                        ResolutionChip(
                            label = profile.shortLabel,
                            isSelected = profile == selectedProfile,
                            onClick = { onResolutionChanged(profile) },
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                if (availableLenses.size > 1) {
                    val nextLens = when (currentLens) {
                        CameraLens.WIDE -> CameraLens.ULTRA_WIDE
                        CameraLens.ULTRA_WIDE -> CameraLens.WIDE
                    }
                    ActionChip(
                        label = "🔄 ${nextLens.label}",
                        onClick = { onLensSwitch(nextLens) },
                    )
                }

                // 电池优化白名单（未优化时显示）
                if (needsBatteryOptimization && isIdle) {
                    Spacer(Modifier.width(4.dp))
                    ActionChip(
                        label = "🔋 省电",
                        onClick = onRequestBatteryOptimization,
                    )
                }

                Spacer(Modifier.width(4.dp))

                // 待机启停按钮
                val buttonText = if (isStandby || isRecording) "⏹ 停止" else "▶ 待机"
                val buttonColor = if (isStandby || isRecording) Color.Red.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.3f)

                ActionChip(
                    label = buttonText,
                    onClick = {
                        if (isStandby || isRecording) onStopStandby() else onStartStandby()
                    },
                    bgColor = buttonColor,
                )
            }
        }
    }
}

/**
 * 通用操作按钮
 */
@Composable
fun ActionChip(
    label: String,
    onClick: () -> Unit,
    bgColor: Color = Color.White.copy(alpha = 0.2f),
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 预录时长 chip
 */
@Composable
private fun DurationChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) ChipSelected else ChipUnselected
    val textColor = if (isSelected) Color.Black else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 录制方向 chip
 */
@Composable
private fun OrientationChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) ChipSelected else ChipUnselected
    val textColor = if (isSelected) Color.Black else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * 分辨率 chip
 */
@Composable
private fun ResolutionChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isSelected) ChipSelected else ChipUnselected
    val textColor = if (isSelected) Color.Black else Color.White

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.bodySmall)
    }
}

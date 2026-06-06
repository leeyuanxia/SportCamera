package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.leeyuanxia.sportcamera.domain.AppState
import cn.leeyuanxia.sportcamera.domain.model.PreRecordDuration
import cn.leeyuanxia.sportcamera.domain.model.RecordOrientation
import cn.leeyuanxia.sportcamera.domain.model.ResolutionProfile
import cn.leeyuanxia.sportcamera.ui.theme.AccentAmber
import cn.leeyuanxia.sportcamera.ui.theme.Black
import cn.leeyuanxia.sportcamera.ui.theme.CardGray
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelected
import cn.leeyuanxia.sportcamera.ui.theme.ChipSelectedText
import cn.leeyuanxia.sportcamera.ui.theme.ChipUnselected
import cn.leeyuanxia.sportcamera.ui.theme.MediumGray
import cn.leeyuanxia.sportcamera.ui.theme.StatusRecording
import cn.leeyuanxia.sportcamera.ui.theme.TextPrimary
import cn.leeyuanxia.sportcamera.ui.theme.TextSecondary
import cn.leeyuanxia.sportcamera.ui.theme.TextTertiary

/**
 * 底部控制栏 — 重新设计
 *
 * 布局分为两层：
 * - 上层：设置区（时长 / 方向 / 分辨率 Chips），空闲时显示，待机时隐藏
 * - 下层：操作区（预览窥视 / 隐藏UI / 省电 / 待机启停）
 */
@Composable
fun ControlBar(
    appState: AppState,
    selectedDuration: PreRecordDuration,
    selectedProfile: ResolutionProfile,
    selectedOrientation: RecordOrientation,
    supportedFps: Set<Int> = setOf(30),
    videoStabilization: Boolean = true,
    eisSupported: Boolean = false,
    previewVisible: Boolean,
    onStartStandby: () -> Unit,
    onStopStandby: () -> Unit,
    onPeekPreview: () -> Unit,
    onToggleUi: () -> Unit,
    onDurationChanged: (PreRecordDuration) -> Unit,
    onResolutionChanged: (ResolutionProfile) -> Unit,
    onOrientationChanged: (RecordOrientation) -> Unit,
    onVideoStabilizationChanged: (Boolean) -> Unit = {},
    onRequestBatteryOptimization: () -> Unit,
    needsBatteryOptimization: Boolean,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    val isStandby = appState is AppState.Standby
    val isIdle = appState is AppState.Idle

    Column(
        modifier = modifier
            .padding(
                horizontal = if (isLandscape) 8.dp else 16.dp,
                vertical = if (isLandscape) 4.dp else 12.dp,
            ),
    ) {
        // ===== 设置面板：仅空闲时显示 =====
        if (isIdle) {
            SettingsPanel(
                selectedDuration = selectedDuration,
                selectedProfile = selectedProfile,
                selectedOrientation = selectedOrientation,
                supportedFps = supportedFps,
                videoStabilization = videoStabilization,
                eisSupported = eisSupported,
                onDurationChanged = onDurationChanged,
                onResolutionChanged = onResolutionChanged,
                onOrientationChanged = onOrientationChanged,
                onVideoStabilizationChanged = onVideoStabilizationChanged,
                isLandscape = isLandscape,
                modifier = if (isLandscape) Modifier.weight(1f) else Modifier,
            )
            Spacer(Modifier.height(if (isLandscape) 6.dp else 16.dp))
        }

        // ===== 操作栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardGray)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧：辅助操作
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStandby) {
                    IconButton(
                        icon = if (previewVisible) "👁" else "👁",
                        label = if (previewVisible) "关闭" else "预览",
                        active = previewVisible,
                        onClick = onPeekPreview,
                    )
                    IconButton(
                        icon = "🌑",
                        label = "隐藏",
                        onClick = onToggleUi,
                    )
                }
                if (needsBatteryOptimization) {
                    IconButton(
                        icon = "⚡",
                        label = "省电",
                        onClick = onRequestBatteryOptimization,
                    )
                }
            }

            // 中间：主导操作按钮
            MainActionButton(
                isStandby = isStandby,
                onClick = if (isStandby) onStopStandby else onStartStandby,
            )

            // 右侧：占位保持居中
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStandby) {
                    // 对称占位，让中间按钮居中
                    Spacer(Modifier.size(36.dp))
                }
            }
        }
    }
}

/**
 * 设置面板 — 时长 / 方向 / 分辨率
 */
@Composable
private fun SettingsPanel(
    selectedDuration: PreRecordDuration,
    selectedProfile: ResolutionProfile,
    selectedOrientation: RecordOrientation,
    supportedFps: Set<Int> = setOf(30),
    videoStabilization: Boolean = true,
    eisSupported: Boolean = false,
    onDurationChanged: (PreRecordDuration) -> Unit,
    onResolutionChanged: (ResolutionProfile) -> Unit,
    onOrientationChanged: (RecordOrientation) -> Unit,
    onVideoStabilizationChanged: (Boolean) -> Unit = {},
    isLandscape: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (isLandscape) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .clip(RoundedCornerShape(16.dp))
            .background(CardGray)
            .padding(if (isLandscape) 8.dp else 12.dp),
    ) {
        // ===== 预录时长：预设 Chips + 自定义 Slider =====
        SectionLabel("预录时长", compact = isLandscape)
        Spacer(Modifier.height(if (isLandscape) 4.dp else 6.dp))

        // 预设 Chips — 平分宽度居中
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PreRecordDuration.PRESETS.forEach { preset ->
                Chip(
                    label = preset.label,
                    isSelected = preset == selectedDuration,
                    onClick = { onDurationChanged(preset) },
                    compact = isLandscape,
                )
            }
        }

        Spacer(Modifier.height(if (isLandscape) 4.dp else 8.dp))

        // 自定义 Slider — 手动填写时间
        var sliderSeconds by remember(selectedDuration) {
            mutableFloatStateOf(selectedDuration.seconds.toFloat())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "自定义",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
            Slider(
                value = sliderSeconds,
                onValueChange = { v ->
                    sliderSeconds = v
                    onDurationChanged(PreRecordDuration(v.toInt()))
                },
                valueRange = PreRecordDuration.MIN.toFloat()..PreRecordDuration.MAX.toFloat(),
                steps = (PreRecordDuration.MAX - PreRecordDuration.MIN) - 1,
                colors = SliderDefaults.colors(
                    thumbColor = AccentAmber,
                    activeTrackColor = AccentAmber,
                    inactiveTrackColor = ChipUnselected,
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(
                text = "${sliderSeconds.toInt()}s",
                color = AccentAmber,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (!isLandscape) {
            Text(
                text = "唤醒后自动保存前后各 ${selectedDuration.seconds / 2} 秒的片段",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Spacer(Modifier.height(if (isLandscape) 6.dp else 12.dp))

        // ===== 画质：分辨率 + 帧率 =====
        SettingsQualitySection(
            selectedProfile = selectedProfile,
            supportedFps = supportedFps,
            onResolutionChanged = onResolutionChanged,
            isLandscape = isLandscape,
        )

        Spacer(Modifier.height(if (isLandscape) 6.dp else 12.dp))

        // ===== 方向 =====
        SectionLabel("录制方向", compact = isLandscape)
        Spacer(Modifier.height(if (isLandscape) 4.dp else 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RecordOrientation.entries.forEach { orientation ->
                Chip(
                    label = orientation.label,
                    isSelected = orientation == selectedOrientation,
                    onClick = { onOrientationChanged(orientation) },
                    compact = isLandscape,
                )
            }
        }

        Spacer(Modifier.height(if (isLandscape) 6.dp else 12.dp))

        // ===== 视频防抖 =====
        SectionLabel("视频防抖", compact = isLandscape)
        Spacer(Modifier.height(if (isLandscape) 4.dp else 6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Chip(
                label = "关闭",
                isSelected = !videoStabilization,
                enabled = eisSupported,
                onClick = { onVideoStabilizationChanged(false) },
            )
            Chip(
                label = "开启",
                isSelected = videoStabilization,
                enabled = eisSupported,
                onClick = { onVideoStabilizationChanged(true) },
            )
        }
        if (!eisSupported) {
            Text(
                text = "当前设备不支持电子防抖",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** 分辨率选项：从 ResolutionProfile 枚举中提取独立的分辨率值 */
private data class ResOption(val height: Int, val label: String)

private val RES_OPTIONS = ResolutionProfile.entries
    .map { ResOption(it.height, resolutionLabel(it)) }
    .distinct()

/** 帧率选项：从 ResolutionProfile 枚举中提取独立的帧率值 */
private data class FpsOption(val fps: Int, val label: String)

private val FPS_OPTIONS = ResolutionProfile.entries
    .map { FpsOption(it.fps, "${it.fps}fps") }
    .distinct()

/** 从 ResolutionProfile 提取分辨率标签 */
private fun resolutionLabel(profile: ResolutionProfile): String = when (profile.height) {
    720 -> "720p"
    1080 -> "1080p"
    else -> "4K"
}

/** 根据分辨率高度获取当前可选帧率 */
private fun availableFpsForRes(height: Int): Set<Int> =
    ResolutionProfile.entries.filter { it.height == height }.map { it.fps }.toSet()

/** 查找匹配的 ResolutionProfile */
private fun findProfile(height: Int, fps: Int): ResolutionProfile? =
    ResolutionProfile.entries.find { it.height == height && it.fps == fps }

/**
 * 画质设置区 — 分辨率一行 + 帧率一行
 */
@Composable
private fun SettingsQualitySection(
    selectedProfile: ResolutionProfile,
    supportedFps: Set<Int> = setOf(30),
    onResolutionChanged: (ResolutionProfile) -> Unit,
    isLandscape: Boolean = false,
) {
    val currentHeight = selectedProfile.height
    val currentFps = selectedProfile.fps
    // 该分辨率下枚举定义的帧率，再与硬件支持的帧率取交集
    val availableFps = availableFpsForRes(currentHeight).intersect(supportedFps)

    SectionLabel("画质", compact = isLandscape)
    Spacer(Modifier.height(if (isLandscape) 4.dp else 6.dp))

    // 分辨率 — 平分宽度居中
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        RES_OPTIONS.forEach { option ->
            Chip(
                label = option.label,
                isSelected = option.height == currentHeight,
                onClick = {
                    val fps = if (option.height == currentHeight) currentFps
                    else availableFpsForRes(option.height).first()
                    findProfile(option.height, fps)?.let(onResolutionChanged)
                },
                compact = isLandscape,
            )
        }
    }

    Spacer(Modifier.height(if (isLandscape) 4.dp else 6.dp))

    // 帧率 — 平分宽度居中（不可用的灰显）
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        FPS_OPTIONS.forEach { option ->
            val available = option.fps in availableFps
            Chip(
                label = option.label,
                isSelected = option.fps == currentFps && available,
                enabled = available,
                onClick = {
                    if (available) {
                        findProfile(currentHeight, option.fps)?.let(onResolutionChanged)
                    }
                },
                compact = isLandscape,
            )
        }
    }
}

/**
 * 节标题
 */
@Composable
private fun SectionLabel(text: String, compact: Boolean = false) {
    Text(
        text = text,
        color = TextSecondary,
        style = if (compact) MaterialTheme.typography.labelSmall
        else MaterialTheme.typography.labelMedium,
    )
}

/**
 * 统一 Chip 组件 — 圆角药丸形
 */
@Composable
private fun Chip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val bgColor = when {
        isSelected -> ChipSelected
        !enabled -> ChipUnselected.copy(alpha = 0.4f)
        else -> ChipUnselected
    }
    val textColor = when {
        isSelected -> ChipSelectedText
        !enabled -> TextPrimary.copy(alpha = 0.25f)
        else -> TextPrimary
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(
                horizontal = if (compact) 8.dp else 12.dp,
                vertical = if (compact) 4.dp else 6.dp,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 小图标按钮
 */
@Composable
private fun IconButton(
    icon: String,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) AccentAmber.copy(alpha = 0.2f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(icon, fontSize = MaterialTheme.typography.bodyLarge.fontSize)
        Text(
            text = label,
            color = if (active) AccentAmber else TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * 主导操作按钮 — 待机 / 停止
 */
@Composable
private fun MainActionButton(
    isStandby: Boolean,
    onClick: () -> Unit,
) {
    val bgColor = if (isStandby) StatusRecording else AccentAmber
    val icon = if (isStandby) "⏹" else "▶"
    val label = if (isStandby) "停止" else "待机"

    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(bgColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = icon,
                fontSize = MaterialTheme.typography.titleLarge.fontSize,
            )
            Text(
                text = label,
                color = Black,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

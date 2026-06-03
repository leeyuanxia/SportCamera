package cn.leeyuanxia.sportcamera.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 背景色阶 =====
val Black = Color(0xFF000000)
val DarkGray = Color(0xFF0F0F0F)
val MediumGray = Color(0xFF1C1C1E)
val LightGray = Color(0xFF2C2C2E)
val CardGray = Color(0xFF1A1A1D)

// ===== 状态指示色 =====
val StatusStandby = Color(0xFF34C759)    // 翠绿 — 待机监听中
val StatusRecording = Color(0xFFFF453A)  // 苹果红 — 正在录制
val StatusSaving = Color(0xFF5E5CE6)     // 靛蓝 — 正在保存
val StatusIdle = Color(0xFF48484A)       // 灰 — 未启动
val StatusError = Color(0xFFFF6B3D)      // 橘红 — 错误

// ===== 主色调 =====
val AccentAmber = Color(0xFFFF9F0A)      // 琥珀 — 主要交互色
val AccentAmberDim = Color(0xFFB36F00)   // 琥珀暗 — 非选中态

// ===== Chip 样式 =====
val ChipSelected = AccentAmber
val ChipSelectedText = Color.Black
val ChipUnselected = Color(0xFF2C2C2E)

// ===== 文字 =====
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFF98989D)
val TextTertiary = Color(0xFF636366)

// ===== 表面 =====
val SurfaceGlass = Color(0xCC1C1C1E)     // 毛玻璃效果底色
val SurfaceOverlay = Color(0x99000000)    // 叠加层底色

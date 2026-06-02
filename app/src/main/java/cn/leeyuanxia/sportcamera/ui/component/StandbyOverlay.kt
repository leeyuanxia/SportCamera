package cn.leeyuanxia.sportcamera.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.leeyuanxia.sportcamera.domain.AppState

/**
 * 待机叠加层 — 预览隐藏时显示最小状态信息
 *
 * 全黑背景中央显示：
 * - 状态小圆点
 * - 简要状态文字
 * - 电量
 *
 * 设计原则：信息最少化，OLED 像素占用最少，最大省电。
 */
@Composable
fun StandbyOverlay(
    appState: AppState,
    batteryLevel: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 待机状态指示 — 琥珀色小圆点
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Color(0xFFFFA000), CircleShape),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = "监听中",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Light,
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "🔋 $batteryLevel%",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
        )
    }
}

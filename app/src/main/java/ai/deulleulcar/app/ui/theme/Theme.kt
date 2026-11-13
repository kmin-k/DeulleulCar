package ai.deulleulcar.app.ui.theme

import ai.deulleulcar.core.model.Severity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 디자인 토큰 (docs/ux/ux-flow.md §6). 야간 운전을 고려한 다크 기본. */
object DC {
    val Bg = Color(0xFF0E1116)
    val Surface = Color(0xFF171B22)
    val Surface2 = Color(0xFF1F2530)
    val Line = Color(0xFF2A3140)
    val Text = Color(0xFFF2F4F7)
    val Sub = Color(0xFF9AA4B2)
    val Brand = Color(0xFF3DDC97)
    val OnBrand = Color(0xFF06210F)
    val Urgent = Color(0xFFFF5A5F)
    val OnUrgent = Color(0xFF2A0506)
    val Recommend = Color(0xFFFFB020)
    val OnRecommend = Color(0xFF2B1B00)
    val Hold = Color(0xFF8A94A6)

    fun severity(s: Severity): Color = when (s) {
        Severity.URGENT -> Urgent
        Severity.RECOMMEND -> Recommend
        Severity.HOLD -> Hold
    }

    // 크기: 목업(1920px 폭)의 px 값을 차량 화면 dp/sp로 옮긴 값.
    val Gutter = 24.dp
    val PanelRadius = 24.dp
    val RowRadius = 18.dp

    /** 최소 터치 높이(ux-flow: 96px). */
    val Touch = 72.dp
    val TouchDriving = 88.dp

    val Body = 22.sp
    val BodyDriving = 30.sp
    val Meta = 17.sp
    val Title = 24.sp
    val Label = 16.sp
}

@Composable
fun DeulleulCarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DC.Brand,
            onPrimary = DC.OnBrand,
            background = DC.Bg,
            surface = DC.Surface,
            onSurface = DC.Text,
            onBackground = DC.Text,
            error = DC.Urgent,
        ),
        content = content,
    )
}

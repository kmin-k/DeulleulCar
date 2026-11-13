package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.ModeInfo
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.trip.TripCopy
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
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter

/** 마이크 아이콘(목업과 같은 path). */
val MicIcon: ImageVector by lazy {
    ImageVector.Builder("mic", 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(
            "M12 15a3 3 0 0 0 3-3V6a3 3 0 1 0-6 0v6a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.93V21h2v-2.07A7 7 0 0 0 19 12h-2Z",
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

@Composable
fun Logo(size: TextUnit = 26.sp) {
    Text(
        buildAnnotatedString {
            append("들를")
            withStyle(SpanStyle(color = DC.Brand)) { append("Car") }
        },
        color = DC.Text, fontSize = size, fontWeight = FontWeight.ExtraBold,
    )
}

@Composable
fun Chip(text: String, color: Color = DC.Sub, background: Color = DC.Surface2, modifier: Modifier = Modifier) {
    Text(
        text, color = color, fontSize = DC.Label,
        modifier = modifier.clip(RoundedCornerShape(999.dp)).background(background).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** 상단 상태 줄: 로고 · 정차/주행 · (경로 설명) · 동작 모드 · 시각 · 버튼들. */
@Composable
fun StatusBar(
    driving: Boolean,
    clock: String,
    mode: ModeInfo,
    middle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Logo()
        Chip(if (driving) "주행 중" else "정차 중", color = if (driving) DC.Brand else DC.Sub)
        Spacer(Modifier.weight(1f))
        if (middle != null) {
            Text(middle, color = DC.Sub, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
        }
        if (mode.limited) Text(mode.label, color = DC.Hold, fontSize = 14.sp, maxLines = 1)
        Text(clock, color = DC.Sub, fontSize = 22.sp)
        trailing()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(DC.Line))
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(DC.PanelRadius)).background(DC.Surface).padding(horizontal = 26.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
fun PanelTitle(text: String, count: Int? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, color = DC.Sub, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) Text("$count", color = DC.Text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 등급 배지(점 + 글자). */
@Composable
fun SeverityBadge(severity: Severity, text: String = TripCopy.severityLabel(severity), large: Boolean = false) {
    Badge(text, DC.severity(severity), large)
}

@Composable
fun Badge(text: String, color: Color, large: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.15f))
            .padding(horizontal = if (large) 16.dp else 12.dp, vertical = if (large) 8.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(if (large) 10.dp else 8.dp).clip(CircleShape).background(color))
        Text(text, color = color, fontSize = if (large) 20.sp else 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 목업의 .row: 번호 원 + 제목/보조 + 오른쪽. */
@Composable
fun ListRow(
    modifier: Modifier = Modifier,
    index: String? = null,
    indexColor: Color = DC.Brand,
    title: String,
    meta: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = DC.Touch).clip(RoundedCornerShape(DC.RowRadius)).background(DC.Surface2)
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (index != null) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(DC.Bg), contentAlignment = Alignment.Center) {
                Text(index, color = indexColor, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, color = if (dimmed) DC.Sub else DC.Text, fontSize = DC.Body, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            meta?.let { Spacer(Modifier.height(3.dp)); it() }
        }
        right?.invoke()
    }
}

@Composable
fun MetaText(text: String, color: Color = DC.Sub) = Text(text, color = color, fontSize = DC.Meta, maxLines = 2, overflow = TextOverflow.Ellipsis)

/** "도착\n18:52" */
@Composable
fun ArrivalText(label: String, time: String) {
    Column(horizontalAlignment = Alignment.End) {
        Text(label, color = DC.Sub, fontSize = 16.sp)
        Text(time, color = DC.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

enum class ButtonStyle { PRIMARY, DANGER, RECOMMEND, GHOST, NEUTRAL }

@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.NEUTRAL,
    height: Dp = DC.Touch,
    fontSize: TextUnit = 24.sp,
    enabled: Boolean = true,
) {
    val (bg, fg) = when (style) {
        ButtonStyle.PRIMARY -> DC.Brand to DC.OnBrand
        ButtonStyle.DANGER -> DC.Urgent to DC.OnUrgent
        ButtonStyle.RECOMMEND -> DC.Recommend to DC.OnRecommend
        ButtonStyle.GHOST -> Color.Transparent to DC.Sub
        ButtonStyle.NEUTRAL -> DC.Surface2 to DC.Text
    }
    Box(
        modifier.heightIn(min = height).clip(RoundedCornerShape(18.dp)).background(if (enabled) bg else DC.Surface2)
            .let { if (style == ButtonStyle.GHOST) it.border(2.dp, DC.Line, RoundedCornerShape(18.dp)) else it }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) fg else DC.Sub, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, maxLines = 1)
    }
}

/** 큰 원형 마이크 버튼. 듣는 중에는 테두리가 숨 쉬듯 커진다. */
@Composable
fun MicButton(listening: Boolean, onClick: () -> Unit, size: Dp = 108.dp) {
    Box(Modifier.size(size + 28.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(size + 24.dp).let { if (listening) it.then(Modifier.pulsing()) else it }.clip(CircleShape)
                .background(DC.Brand.copy(alpha = if (listening) 0.28f else 0.14f)),
        )
        Box(
            Modifier.size(size).clip(CircleShape).background(if (listening) DC.Text else DC.Brand)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = if (listening) "듣는 중, 누르면 멈춤" else "말하기" },
            contentAlignment = Alignment.Center,
        ) {
            Image(MicIcon, contentDescription = null, colorFilter = ColorFilter.tint(DC.OnBrand), modifier = Modifier.size(size * 0.42f))
        }
    }
}

/** 듣는 중일 때만 도는 숨쉬기 애니메이션(평소에는 프레임을 쓰지 않는다). */
@Composable
private fun Modifier.pulsing(): Modifier {
    val pulse = rememberInfiniteTransition(label = "mic")
    val s by pulse.animateFloat(1f, 1.18f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "mic-scale")
    return scale(s)
}

/** 점선 테두리(추천 카드). */
fun Modifier.dashedBorder(color: Color, radius: Dp = DC.RowRadius, width: Dp = 2.dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
    )
}

@Composable
fun Hspace(w: Dp) = Spacer(Modifier.width(w))

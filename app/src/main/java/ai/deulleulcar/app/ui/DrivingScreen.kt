package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.AppUiState
import ai.deulleulcar.app.flow.UrgentUi
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.trip.TripCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * S3 주행 미니 카드. 글자 3줄 이하, 큰 버튼 최대 2개, 조작은 음성 위주(ux-flow 원칙 3).
 * 질문(경로 근처 기회·볼일 추가·출발 확인)이 있으면 가운데를 질문 카드로 바꾼다.
 */
@Composable
fun DrivingScreen(
    state: AppUiState,
    onMic: () -> Unit,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(DC.Gutter), horizontalArrangement = Arrangement.spacedBy(DC.Gutter)) {
        NaviArea(Modifier.weight(1f).fillMaxSize())
        Panel(Modifier.width(560.dp).fillMaxSize()) {
            val question = drivingQuestion(state)
            if (question != null) {
                QuestionCard(question, onYes, onNo, Modifier.weight(1f))
            } else {
                NextStop(state, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val remaining = state.trip?.rolling?.remaining?.size ?: 0
                if (state.trip != null) Chip("남은 곳 $remaining", color = DC.Text)
                val open = state.tasks.size
                if (open > 0) {
                    val top = state.tasks.minOf { it.task.severity.ordinal }
                    SeverityBadge(Severity.entries[top], "차의 할 일 $open", large = true)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MicButton(state.voice.listening, onMic, size = 100.dp)
                Column {
                    if (state.voice.listening) {
                        Text("듣고 있어요", color = DC.Brand, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(state.voice.partial ?: "", color = DC.Text, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else {
                        Text("주행 중에는 말로만 조작해요", color = DC.Sub, fontSize = 19.sp)
                        Text("\"하나 더 들러\" · \"다음에 할게\"", color = DC.Text, fontSize = 21.sp)
                    }
                }
            }
        }
    }
}

private data class DrivingQuestion(val text: String, val yes: String, val no: String, val color: androidx.compose.ui.graphics.Color)

private fun drivingQuestion(s: AppUiState): DrivingQuestion? {
    s.opportunity?.let { o ->
        val sg = o.suggestion
        return DrivingQuestion("${sg.place.name}\n${TripCopy.title(sg.task)}", "들를래요 +${sg.detourMinutes}분", "다음에", DC.severity(sg.task.severity))
    }
    s.addition?.let { a -> return DrivingQuestion("${a.addedNames.joinToString(", ")} 추가", "들를래요 +${a.extraMinutes}분", "아니요", DC.Brand) }
    s.proposal?.let { p ->
        val first = p.pending.firstOrNull()
        return if (first != null) {
            DrivingQuestion("${first.place.name}\n${TripCopy.title(first.task)}", "들를래요 +${first.detourMinutes}분", "다음에", DC.severity(first.task.severity))
        } else {
            DrivingQuestion("들를 곳 ${p.plan.stops.size}곳 · +${p.plan.extraTotalMinutes}분\n이대로 출발할까요?", "출발", "취소", DC.Brand)
        }
    }
    return null
}

@Composable
private fun QuestionCard(q: DrivingQuestion, onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("들를까요?", color = q.color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(q.text, color = DC.Text, fontSize = DC.BodyDriving, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BigButton(q.yes, onYes, Modifier.weight(1.3f), style = if (q.color == DC.Urgent) ButtonStyle.DANGER else if (q.color == DC.Brand) ButtonStyle.PRIMARY else ButtonStyle.RECOMMEND, height = DC.TouchDriving, fontSize = 26.sp)
            BigButton(q.no, onNo, Modifier.weight(1f), style = ButtonStyle.GHOST, height = DC.TouchDriving, fontSize = 26.sp)
        }
    }
}

@Composable
private fun NextStop(state: AppUiState, modifier: Modifier) {
    val trip = state.trip
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (trip == null) {
            Text("안내 중인 들를 곳이 없어요", color = DC.Sub, fontSize = 24.sp)
            Text("\"퇴근길에 약국 들러\"", color = DC.Text, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            return@Column
        }
        val next = trip.rolling.next
        val done = trip.rolling.visited.size + trip.rolling.skipped.size
        if (next == null) {
            Text("이제 목적지로", color = DC.Sub, fontSize = 24.sp)
            Text(trip.destinationName, color = DC.Text, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("도착 ${TripCopy.time(trip.plan.arrivalAtDestination)}", color = DC.Brand, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            return@Column
        }
        val planned = trip.plan.stops.firstOrNull { it.stop.place.id == next.place.id }
        Text("다음 들를 곳 · ${done + 1}/${trip.rolling.total}", color = DC.Sub, fontSize = 24.sp)
        Text(next.place.name, color = DC.Text, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val eta = planned?.let { "도착 ${TripCopy.time(it.arrival)}" }
        val km = planned?.let { distanceKm(trip.plan.origin, it.stop.place.point) }
        Text(listOfNotNull(eta, km).joinToString(" · "), color = DC.Brand, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    }
}

private fun distanceKm(a: ai.deulleulcar.core.model.GeoPoint, b: ai.deulleulcar.core.model.GeoPoint): String {
    val km = a.distanceTo(b) / 1000.0
    return if (km < 10) "${(km * 10).roundToInt() / 10.0}km" else "${km.roundToInt()}km"
}

/** 내비가 그리는 영역 자리(지도는 Pleos 내비게이션이 담당). */
@Composable
private fun NaviArea(modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(DC.PanelRadius)).background(
            Brush.linearGradient(listOf(DC.Brand.copy(alpha = 0.08f), DC.Surface, DC.Bg)),
        ),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text("길 안내는 Pleos 내비게이션이 맡아요", color = DC.Hold, fontSize = 18.sp, modifier = Modifier.padding(28.dp))
    }
}

/** S4 긴급 알림(주행·정차 공통). 빨간 전면 카드, 3줄 설명, [들를래요][10분 뒤 다시]. */
@Composable
fun UrgentScreen(urgent: UrgentUi, repeatMinutes: Long, onAccept: () -> Unit, onLater: () -> Unit, onDetail: (() -> Unit)?) {
    val task = urgent.task
    Box(Modifier.fillMaxSize().padding(DC.Gutter)) {
        Row(
            Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(DC.Urgent.copy(alpha = 0.22f), DC.Urgent.copy(alpha = 0.06f))))
                .border(2.dp, DC.Urgent, RoundedCornerShape(28.dp)).padding(horizontal = 40.dp, vertical = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            Box(Modifier.size(140.dp).clip(RoundedCornerShape(36.dp)).background(DC.Urgent), contentAlignment = Alignment.Center) {
                Text(iconFor(task.kind), color = DC.OnUrgent, fontSize = 76.sp, fontWeight = FontWeight.Black)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SeverityBadge(Severity.URGENT, large = true)
                val copy = ai.deulleulcar.core.cartask.CopyTemplates.describe(task)
                Text(copy.what.removeSuffix("."), color = DC.Text, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 48.sp, maxLines = 2)
                val fix = urgent.fix
                val line1 = when {
                    urgent.searching -> "가장 가까운 해결 장소를 찾고 있어요…"
                    fix != null -> "가장 가까운 곳 · ${fix.place.name} (${distanceKm(fix.preview.origin, fix.place.point)})"
                    else -> "근처에서 해결 장소를 찾지 못했어요"
                }
                Text(line1, color = DC.Text, fontSize = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    fix?.let { "지금 들르면 도착이 ${it.preview.extraTotalMinutes}분 늦어져요 · ${copy.risk}" } ?: copy.risk,
                    color = DC.Sub, fontSize = 22.sp, maxLines = 2,
                )
            }
            Column(Modifier.width(360.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BigButton("들를래요", onAccept, Modifier.fillMaxWidth(), style = ButtonStyle.DANGER, height = 100.dp, fontSize = 32.sp, enabled = !urgent.searching)
                BigButton("${repeatMinutes}분 뒤 다시", onLater, Modifier.fillMaxWidth(), style = ButtonStyle.GHOST, height = 100.dp, fontSize = 30.sp)
                if (onDetail != null) BigButton("자세히", onDetail, Modifier.fillMaxWidth(), style = ButtonStyle.NEUTRAL, height = 72.dp, fontSize = 22.sp)
            }
        }
    }
}

private fun iconFor(kind: CarTaskKind): String = when (kind) {
    CarTaskKind.LowRange -> "⛽"
    is CarTaskKind.Warning -> when (kind.telltale) {
        TelltaleType.LOW_FUEL -> "⛽"
        TelltaleType.LOW_EV_BATTERY -> "⚡"
        TelltaleType.ENGINE_TEMPERATURE -> "♨"
        else -> "!"
    }
    CarTaskKind.PeriodicInspection -> "!"
}

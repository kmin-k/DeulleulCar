package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.ProposalUi
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.llm.ParseSource
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.place.PlaceDataSource
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.route.RouteSpeech
import ai.deulleulcar.core.trip.TripCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S2 계획 확인(정차). 순서 리스트 + 차의 할 일 추천(점선 카드, 아직 계획에 없음) + 요약·음성 말풍선 + [출발][수정].
 */
@Composable
fun PlanScreen(
    proposal: ProposalUi,
    listening: Boolean,
    onAccept: (Suggestion) -> Unit,
    onDecline: (Suggestion) -> Unit,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onMic: () -> Unit,
) {
    val plan = proposal.plan
    Row(Modifier.fillMaxSize().padding(DC.Gutter), horizontalArrangement = Arrangement.spacedBy(DC.Gutter)) {
        Panel(Modifier.weight(1.5f).fillMaxSize()) {
            PanelTitle("이렇게 들를게요")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                // 추천은 미리보기에서 들어갈 자리 바로 앞에 보여 준다(계획에는 아직 없음).
                val slots = proposal.pending.groupBy { s ->
                    s.preview.stops.indexOfFirst { it.stop.linkedTaskId == s.task.id }.coerceAtLeast(0)
                }
                var number = 0
                for (i in 0..plan.stops.size) {
                    slots[i]?.forEach { s -> item(key = "s-${s.task.id}") { SuggestionRow(s, onAccept, onDecline) } }
                    if (i == plan.stops.size) break
                    val stop = plan.stops[i]
                    number++
                    val n = number
                    item(key = "p-${stop.stop.place.id}") {
                        val car = stop.stop.source == StopSource.CAR
                        ListRow(
                            index = "$n",
                            indexColor = if (car) DC.Recommend else DC.Brand,
                            title = stop.stop.place.name,
                            meta = {
                                if (car) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Badge("차의 할 일", DC.Recommend)
                                        MetaText("허락한 할 일 · ${stop.stop.place.category.label}")
                                    }
                                } else {
                                    MetaText(TripCopy.stopMeta(stop, plan))
                                }
                            },
                            right = { ArrivalText("도착", TripCopy.time(stop.arrival)) },
                        )
                    }
                }
                plan.excluded.forEach { ex ->
                    item(key = "x-${ex.request.id}") {
                        val name = ex.request.candidates.firstOrNull()?.let { RouteSpeech.nameOf(it) } ?: "볼일"
                        ListRow(
                            index = "–", indexColor = DC.Hold, title = name, dimmed = true,
                            meta = { MetaText(TripCopy.excludedSentence(listOf(ex)) ?: "이번에는 빼요") },
                        )
                    }
                }
                item(key = "dest") {
                    ListRow(
                        index = "⌂", title = plan.destination.name ?: "목적지",
                        meta = { MetaText("최종 목적지") },
                        right = { ArrivalText("도착", TripCopy.time(plan.arrivalAtDestination)) },
                    )
                }
            }
        }
        Panel(Modifier.weight(1f).fillMaxSize()) {
            Text("평소보다", color = DC.Sub, fontSize = 17.sp)
            Text(
                buildAnnotatedString {
                    append("+${plan.extraTotalMinutes}분")
                    withStyle(SpanStyle(fontSize = 26.sp, color = DC.Sub, fontWeight = FontWeight.SemiBold)) {
                        append("  들를 곳 ${plan.stops.size}곳")
                    }
                },
                color = DC.Text, fontSize = 56.sp, fontWeight = FontWeight.ExtraBold,
            )
            proposal.pending.firstOrNull()?.let { s ->
                Text("차의 할 일까지 넣으면 +${s.preview.extraTotalMinutes}분", color = DC.Sub, fontSize = 20.sp, maxLines = 2)
            }
            Text(sourceLine(proposal), color = DC.Hold, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(DC.Bg).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (listening) DC.Text else DC.Brand))
                    Text(if (listening) "듣고 있어요 · ‘응’ 또는 ‘아니’" else "들를Car", color = DC.Brand, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Text(proposal.speech, color = DC.Text, fontSize = 20.sp, lineHeight = 29.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("출발", onStart, Modifier.weight(1f), style = ButtonStyle.PRIMARY, height = 84.dp, fontSize = 28.sp)
                BigButton("수정", onEdit, Modifier.weight(0.7f), style = ButtonStyle.GHOST, height = 84.dp, fontSize = 26.sp)
                MicButton(listening, onMic, size = 64.dp)
            }
        }
    }
}

private fun sourceLine(p: ProposalUi): String {
    val parse = if (p.proposal.parse.source == ParseSource.LLM) "AI가 말을 해석" else "규칙으로 말을 해석"
    val places = if (p.proposal.placeSource == PlaceDataSource.DEMO) "데모 장소" else "카카오 장소 검색"
    return "$parse · $places · 이동 시간은 추정치"
}

/** 차의 할 일 추천(점선 카드). 누르기 전까지 계획에 들어가지 않는다. */
@Composable
private fun SuggestionRow(s: Suggestion, onAccept: (Suggestion) -> Unit, onDecline: (Suggestion) -> Unit) {
    val color = DC.severity(s.task.severity)
    Row(
        Modifier.fillMaxWidth().heightIn(min = DC.Touch).dashedBorder(color).clip(RoundedCornerShape(DC.RowRadius))
            .background(color.copy(alpha = 0.06f)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(DC.Bg), contentAlignment = Alignment.Center) {
            Text("+", color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            Text(s.place.name, color = DC.Text, fontSize = DC.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Badge(if (s.task.severity == Severity.URGENT) "긴급 · 차의 할 일" else "차의 할 일", color)
                MetaText(TripCopy.title(s.task))
            }
        }
        BigButton("들를래요 +${s.detourMinutes}분", { onAccept(s) }, style = if (s.task.severity == Severity.URGENT) ButtonStyle.DANGER else ButtonStyle.RECOMMEND, height = 62.dp, fontSize = 20.sp)
        BigButton("다음에", { onDecline(s) }, style = ButtonStyle.GHOST, height = 62.dp, fontSize = 20.sp)
    }
}

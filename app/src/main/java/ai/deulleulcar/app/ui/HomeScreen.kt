package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.AppUiState
import ai.deulleulcar.app.flow.CarTaskItem
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.trip.TripCopy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 볼일 입력을 도와주는 예시 문장(정차 중 텍스트 입력 칩). */
val SampleUtterances = listOf(
    "퇴근길에 약국이랑 마트 들렀다 집에 가자",
    "세탁소, 약국, 마트 들렀다 집",
)

/**
 * S1 홈(정차). 왼쪽: 사람의 할 일(오늘 들를 곳) + 마이크. 오른쪽: 차의 할 일 카드.
 */
@Composable
fun HomeScreen(
    state: AppUiState,
    onMic: () -> Unit,
    onSubmitText: (String) -> Unit,
    onOpenTask: (String) -> Unit,
    onAcceptForLater: (String) -> Unit,
    onSnooze: (String) -> Unit,
    onCancelTrip: () -> Unit,
    onShowPlan: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(DC.Gutter), horizontalArrangement = Arrangement.spacedBy(DC.Gutter)) {
        HumanPanel(state, onMic, onSubmitText, onCancelTrip, onShowPlan, Modifier.weight(1.15f).fillMaxSize())
        CarPanel(state, onOpenTask, onAcceptForLater, onSnooze, Modifier.weight(1f).fillMaxSize())
    }
}

@Composable
private fun HumanPanel(
    state: AppUiState,
    onMic: () -> Unit,
    onSubmitText: (String) -> Unit,
    onCancelTrip: () -> Unit,
    onShowPlan: () -> Unit,
    modifier: Modifier,
) {
    val trip = state.trip
    val stops = trip?.plan?.stops.orEmpty()
    Panel(modifier) {
        PanelTitle("오늘 들를 곳", count = stops.size) {
            if (trip != null) BigButton("안내 종료", onCancelTrip, style = ButtonStyle.GHOST, height = 52.dp, fontSize = 18.sp)
            if (trip == null && state.proposal != null) {
                BigButton("확인 중인 계획 보기", onShowPlan, style = ButtonStyle.NEUTRAL, height = 52.dp, fontSize = 18.sp)
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stops.isEmpty()) {
                item {
                    Text(
                        if (trip != null) "남은 들를 곳이 없어요. ${trip.destinationName}까지 안내 중이에요." else "아직 들를 곳이 없어요.",
                        color = DC.Sub, fontSize = DC.Body, modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(stops.size) { i ->
                val s = stops[i]
                val car = s.stop.source == StopSource.CAR
                ListRow(
                    index = "${i + 1}",
                    indexColor = if (car) DC.Recommend else DC.Brand,
                    title = s.stop.place.name,
                    meta = {
                        MetaText(
                            listOfNotNull(
                                s.closesAt?.let { "${TripCopy.time(it)} 영업 종료" },
                                if (car) "차의 할 일" else s.stop.errand?.keyword,
                            ).joinToString(" · "),
                        )
                    },
                    right = { ArrivalText("도착", TripCopy.time(s.arrival)) },
                )
            }
            if (trip != null) {
                item {
                    ListRow(
                        index = "⌂", title = trip.destinationName,
                        meta = { MetaText("최종 목적지") },
                        right = { ArrivalText("도착", TripCopy.time(trip.plan.arrivalAtDestination)) },
                    )
                }
            }
        }
        AskBox(state, onMic, onSubmitText)
    }
}

/** 마이크 + 안내 문구 + (정차 중) 글자 입력. */
@Composable
private fun AskBox(state: AppUiState, onMic: () -> Unit, onSubmitText: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(DC.Bg).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MicButton(state.voice.listening, onMic, size = 96.dp)
            Column(Modifier.weight(1f)) {
                when {
                    state.planning -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = DC.Brand, strokeWidth = 3.dp, modifier = Modifier.height(26.dp))
                        Spacer(Modifier.padding(6.dp))
                        Text("가는 길에 들를 곳을 찾고 있어요…", color = DC.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    state.voice.listening -> {
                        Text("듣고 있어요", color = DC.Brand, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(state.voice.partial ?: "말씀하세요…", color = DC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    }
                    else -> {
                        Text("\"가는 길에 ○○ 들러\"", color = DC.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("여러 곳을 한 번에 말해도 순서는 알아서 짜 드려요.", color = DC.Sub, fontSize = 19.sp)
                    }
                }
            }
        }
        // 정차 중에만: 글자로 말하기(마이크가 없거나 시끄러울 때)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(DC.Surface2)
                    .border(1.dp, DC.Line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) Text("글자로 입력해도 돼요", color = DC.Hold, fontSize = 20.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = DC.Text, fontSize = 20.sp),
                    cursorBrush = SolidColor(DC.Brand),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSubmitText(text); text = "" }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            BigButton("보내기", { onSubmitText(text); text = "" }, enabled = text.isNotBlank(), height = 60.dp, fontSize = 20.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SampleUtterances.forEach { sample ->
                Text(
                    sample, color = DC.Sub, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(999.dp)).background(DC.Surface2)
                        .clickable(role = Role.Button) { onSubmitText(sample) }.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun CarPanel(
    state: AppUiState,
    onOpenTask: (String) -> Unit,
    onAcceptForLater: (String) -> Unit,
    onSnooze: (String) -> Unit,
    modifier: Modifier,
) {
    Panel(modifier) {
        PanelTitle("차의 할 일", count = state.tasks.size)
        if (state.tasks.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("✓", color = DC.Brand, fontSize = 56.sp, fontWeight = FontWeight.Black)
                Text("차가 알려 준 할 일이 없어요", color = DC.Text, fontSize = DC.Body, fontWeight = FontWeight.Bold)
                Text("경고등·타이어·연료를 계속 지켜볼게요.", color = DC.Sub, fontSize = 18.sp)
            }
            return@Panel
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.tasks, key = { it.task.id }) { item ->
                CarTaskCard(
                    item,
                    asking = state.parkedAskTaskId == item.task.id,
                    onOpen = { onOpenTask(item.task.id) },
                    onAccept = { onAcceptForLater(item.task.id) },
                    onSnooze = { onSnooze(item.task.id) },
                )
            }
        }
        Text("차의 할 일은 허락하기 전에는 경로에 넣지 않아요.", color = DC.Hold, fontSize = 15.sp)
    }
}

@Composable
fun CarTaskCard(item: CarTaskItem, asking: Boolean, onOpen: () -> Unit, onAccept: () -> Unit, onSnooze: () -> Unit) {
    val t = item.task
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DC.RowRadius)).background(DC.Surface2)
            .let { if (asking) it.border(2.dp, DC.severity(t.severity), RoundedCornerShape(DC.RowRadius)) else it }
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SeverityBadge(t.severity)
            Text(item.title, color = DC.Text, fontSize = DC.Body, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("›", color = DC.Sub, fontSize = 28.sp)
        }
        Text(item.line, color = DC.Sub, fontSize = 18.sp, lineHeight = 25.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val fix = item.bestFix
            val (left, right) = when {
                item.visited -> "해결 장소에 들렀어요 · 경고가 꺼지면 사라져요" to ""
                item.accepted -> "다음 경로를 짤 때 함께 넣어요" to "허락함"
                fix != null && t.severity != Severity.HOLD -> "가장 빠른 해결 · ${fix.place.name}" to "경로상 +${fix.detourMinutes}분"
                t.severity == Severity.HOLD -> "경로 근처에 기회가 생기면 알려 드릴게요" to "대기 중"
                else -> "해결 장소를 찾고 있어요" to ""
            }
            Text(left, color = DC.Sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(right, color = if (item.accepted || t.severity == Severity.HOLD) DC.Sub else DC.severity(t.severity), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (asking) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 4.dp)) {
                BigButton("다음 경로에 넣기", onAccept, Modifier.weight(1f), style = ButtonStyle.RECOMMEND, height = 64.dp, fontSize = 20.sp)
                BigButton("나중에", onSnooze, Modifier.weight(0.6f), style = ButtonStyle.GHOST, height = 64.dp, fontSize = 20.sp)
            }
        }
    }
}

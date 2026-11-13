package ai.deulleulcar.app.ui

import ai.deulleulcar.app.flow.DetailUi
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.cartask.CopyTemplates
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.trip.TripCopy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S5 할 일 상세(정차). 무엇 / 지금 위험한가 / 언제까지 / 어떻게 해결 + 해결 장소 후보(+N분).
 * 장소를 고르면: 안내 중이면 지금 경로에 넣고, 긴급이면 바로 안내, 나머지는 다음 경로에 넣어 둔다.
 */
@Composable
fun DetailScreen(
    detail: DetailUi,
    guiding: Boolean,
    onBack: () -> Unit,
    onChoose: (Suggestion) -> Unit,
    onLater: () -> Unit,
) {
    val item = detail.item
    val task = item.task
    val copy = CopyTemplates.describe(task, item.escalated)
    val color = DC.severity(task.severity)
    Row(Modifier.fillMaxSize().padding(DC.Gutter), horizontalArrangement = Arrangement.spacedBy(DC.Gutter)) {
        Panel(Modifier.weight(1.25f).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton("‹ 홈", onBack, style = ButtonStyle.NEUTRAL, height = 56.dp, fontSize = 20.sp)
                SeverityBadge(task.severity, large = true)
                Text(item.title, color = DC.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.padding(2.dp))
            QA("무슨 뜻이에요?", copy.what)
            QA("지금 위험해요?", copy.risk.trimEnd().let { if (it.endsWith("지만")) "$it 미루지 않는 게 좋아요." else it })
            QA("언제까지?", copy.deadline, highlight = color)
            QA("어떻게 해결해요?", TripCopy.howToFix(task))
            Spacer(Modifier.weight(1f))
            Text("감지: ${sourceOf(task.kind)} · ${detail.detectedAgo}" + if (item.escalated) " · 여러 번 미뤘어요" else "", color = DC.Sub, fontSize = 16.sp)
        }
        Panel(Modifier.weight(1f).fillMaxSize()) {
            PanelTitle("해결할 수 있는 곳")
            val options = detail.options
            when {
                options == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = DC.Brand, strokeWidth = 3.dp)
                    Text("경로 근처를 찾고 있어요…", color = DC.Sub, fontSize = 20.sp)
                }
                options.isEmpty() -> Text("가까운 해결 장소를 찾지 못했어요.", color = DC.Sub, fontSize = 20.sp, modifier = Modifier.weight(1f))
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(options, key = { it.place.id }) { o ->
                        ListRow(
                            title = o.place.name,
                            meta = { MetaText(listOfNotNull(o.place.category.label, closing(o)).joinToString(" · ")) },
                            right = { Text("+${o.detourMinutes}분", color = color, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) },
                            onClick = { onChoose(o) },
                        )
                    }
                }
            }
            if (options.isNullOrEmpty()) Spacer(Modifier.weight(1f))
            val best = options?.firstOrNull()
            val primary = when {
                item.accepted && !guiding -> "다음 경로에 넣어 뒀어요"
                guiding -> "지금 경로에 넣기"
                task.severity == Severity.URGENT -> "가장 가까운 곳 들르기"
                else -> "다음 경로에 넣어 두기"
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton(
                    primary, { best?.let(onChoose) }, Modifier.weight(1f),
                    style = if (task.severity == Severity.URGENT) ButtonStyle.DANGER else ButtonStyle.PRIMARY,
                    enabled = best != null && !(item.accepted && !guiding), height = 80.dp, fontSize = 22.sp,
                )
                BigButton("나중에", onLater, Modifier.width(150.dp), style = ButtonStyle.GHOST, height = 80.dp, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun QA(q: String, a: String, highlight: Color? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(q, color = DC.Sub, fontSize = 19.sp, modifier = Modifier.width(170.dp).padding(top = 3.dp))
        Text(a, color = highlight ?: DC.Text, fontSize = 22.sp, lineHeight = 31.sp, fontWeight = if (highlight != null) FontWeight.Bold else FontWeight.Normal)
    }
}

private fun closing(o: Suggestion): String? =
    o.preview.stops.firstOrNull { it.stop.linkedTaskId == o.task.id }?.closesAt?.let { "${TripCopy.time(it)}까지" }

private fun sourceOf(kind: CarTaskKind): String = when (kind) {
    CarTaskKind.LowRange -> "주행 가능 거리"
    CarTaskKind.PeriodicInspection -> "누적 주행거리"
    is CarTaskKind.Warning -> "차량 경고 신호"
}

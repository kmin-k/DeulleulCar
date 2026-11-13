package ai.deulleulcar.app.demo

import ai.deulleulcar.app.ui.BigButton
import ai.deulleulcar.app.ui.ButtonStyle
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.demo.DemoRunStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 데모 바(상태 표시줄 아래 한 줄). 시나리오가 돌거나 가상 주행 중일 때만 보인다.
 * 지금 무슨 일이 일어나는지 해설 + 주행 진행률 + [배속] [다음] [끝내기]. 애니메이션 없음.
 */
@Composable
fun DemoBar(ui: DemoUi, onSpeed: () -> Unit, onNext: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = DC.Gutter).heightIn(min = 68.dp).clip(RoundedCornerShape(18.dp))
            .background(DC.Surface2).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val head = ui.scenario?.let { "시나리오 ${it.number} · ${ui.step}/${ui.total}" } ?: "가상 주행"
        Text(
            head, color = DC.OnBrand, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (ui.status == DemoRunStatus.STUCK) DC.Recommend else DC.Brand)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Text(
            ui.caption ?: "가상 위치로 주행해요. 도착하면 다음 정류지로 이어 가요.",
            color = DC.Text, fontSize = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 24.sp,
            modifier = Modifier.weight(1f),
        )
        ui.drive?.let { d ->
            val text = when {
                d.dwelling -> "${d.targetName} 도착"
                d.paused -> "${d.targetName}까지 ${d.percent}% · 멈춤"
                else -> "${d.targetName}까지 ${d.percent}% · ${distance(d.remainingM)}"
            }
            Text(text, color = DC.Sub, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.45f, fill = false))
        }
        BigButton(ui.speed.label, onSpeed, style = ButtonStyle.NEUTRAL, height = 56.dp, fontSize = 19.sp)
        BigButton("다음 ▶", onNext, style = ButtonStyle.PRIMARY, height = 56.dp, fontSize = 19.sp)
        if (ui.scenario != null) BigButton("끝내기", onDismiss, style = ButtonStyle.GHOST, height = 56.dp, fontSize = 18.sp)
    }
}

private fun distance(m: Int): String = if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${m}m"

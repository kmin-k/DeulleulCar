package ai.deulleulcar.app.demo

import ai.deulleulcar.app.ui.BigButton
import ai.deulleulcar.app.ui.ButtonStyle
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.model.Gear
import ai.deulleulcar.core.model.TelltaleType
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 데모 조작 패널(데모 모드에서 상단 "데모" 버튼으로 연다). 실제 차량에서는 Virtual Console이 이 역할을 한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DemoPanel(demo: DemoControls, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val snap by demo.snapshot.collectAsState()
    Column(
        modifier.width(460.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .background(DC.Surface).border(1.dp, DC.Line, RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp))
            .padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row {
            Text("데모 조작", color = DC.Text, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            BigButton("닫기", onClose, style = ButtonStyle.GHOST, height = 48.dp, fontSize = 18.sp)
        }
        Section("기어")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("P 정차", demo::park, active = snap.gear == Gear.P)
            Small("D 주행", demo::drive, active = snap.gear == Gear.D)
        }
        Section("말하기 (가상 마이크)")
        Small("① 퇴근길 약국·마트", { demo.say(DemoControls.SCENARIO_1) })
        Small("② 세탁소·약국·마트", { demo.say(DemoControls.SCENARIO_2) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("“응”", { demo.say("응") })
            Small("“다음에”", { demo.say("다음에") })
            Small("“편의점 하나 더 들러”", { demo.say("편의점 하나 더 들러") })
        }
        Section("차량 신호")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("③ 타이어 저압", { demo.toggleTelltale(TelltaleType.TPMS) }, active = TelltaleType.TPMS in snap.telltales)
            Small("워셔액 부족", { demo.toggleTelltale(TelltaleType.WASHER_FLUID_LOW) }, active = TelltaleType.WASHER_FLUID_LOW in snap.telltales)
            Small("엔진오일 압력", { demo.toggleTelltale(TelltaleType.ENGINE_OIL_PRESSURE) }, active = TelltaleType.ENGINE_OIL_PRESSURE in snap.telltales)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("연료 25km", { demo.range(25.0) }, active = (snap.rangeKm ?: 999.0) < 30)
            Small("연료 70km", { demo.range(70.0) }, active = (snap.rangeKm ?: 999.0) in 30.0..79.9)
            Small("연료 420km", { demo.range(420.0) }, active = (snap.rangeKm ?: 0.0) >= 80)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("점검 주기 지남", { demo.inspectionDue(true) }, active = (snap.odometerKm ?: 0.0) >= 10_000)
            Small("점검 완료", { demo.inspectionDue(false) }, active = (snap.odometerKm ?: 0.0) < 10_000)
        }
        Section("내비")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Small("경유지 도착", demo::arriveNext)
            Small("목적지 도착", demo::arriveDestination)
            Small("회전 200m 앞", { demo.turnAhead(200.0) })
            Small("회전 지남", { demo.turnAhead(null) })
        }
        Text("TTS는 소리 대신 화면 아래 자막으로 보여 줘요.", color = DC.Sub, fontSize = 15.sp)
    }
}

@Composable
private fun Section(text: String) = Text(text, color = DC.Sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Small(text: String, onClick: () -> Unit, active: Boolean = false) =
    BigButton(text, onClick, style = if (active) ButtonStyle.PRIMARY else ButtonStyle.NEUTRAL, height = 54.dp, fontSize = 18.sp)

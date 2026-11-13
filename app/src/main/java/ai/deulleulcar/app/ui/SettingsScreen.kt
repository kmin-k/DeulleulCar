package ai.deulleulcar.app.ui

import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.app.settings.Settings
import ai.deulleulcar.app.settings.maskKey
import ai.deulleulcar.app.ui.theme.DC
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * S6 설정(정차). API 키(선택), 집·회사 위치, 데모 모드, SDK 연결 상태.
 * 키는 이 기기 앱 저장소에만 둔다. 화면에도 앞 4자리만 보여 준다.
 */
@Composable
fun SettingsScreen(
    settings: Settings,
    features: List<Pair<String, FeatureState>>,
    onBack: () -> Unit,
    onSaveKeys: (gemini: String, kakao: String) -> Unit,
    onDemoMode: (Boolean) -> Unit,
    onDemoClock: (Boolean) -> Unit,
    onSetHomeHere: () -> Unit,
    onSetWorkHere: () -> Unit,
    onResetPlaces: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(DC.Gutter), horizontalArrangement = Arrangement.spacedBy(DC.Gutter)) {
        Panel(Modifier.weight(1.2f).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                BigButton("‹ 홈", onBack, style = ButtonStyle.NEUTRAL, height = 56.dp, fontSize = 20.sp)
                Text("설정", color = DC.Text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PanelTitle("API 키 (선택)")
                var gemini by remember(settings.geminiApiKey) { mutableStateOf("") }
                var kakao by remember(settings.kakaoRestApiKey) { mutableStateOf("") }
                KeyField("Gemini API 키", settings.geminiApiKey, gemini, { gemini = it }, "있으면 말을 AI가 해석해요. 없으면 규칙으로 해석해요.")
                KeyField("카카오 REST 키", settings.kakaoRestApiKey, kakao, { kakao = it }, "있으면 실제 장소를 찾아요. 없으면 데모 구역의 가상 장소를 써요.")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigButton(
                        "저장", {
                            onSaveKeys(gemini.ifBlank { settings.geminiApiKey }, kakao.ifBlank { settings.kakaoRestApiKey })
                            gemini = ""; kakao = ""
                        },
                        style = ButtonStyle.PRIMARY, enabled = gemini.isNotBlank() || kakao.isNotBlank(),
                    )
                    BigButton("키 모두 지우기", { onSaveKeys("", "") }, style = ButtonStyle.GHOST, enabled = settings.hasGemini || settings.hasKakao)
                }
                Text(
                    "키는 이 기기의 앱 저장소에만 저장되고 앱 파일(APK)에는 들어가지 않아요. AI에는 말한 문장만 보내고 위치·개인정보는 보내지 않아요.",
                    color = DC.Sub, fontSize = 16.sp, lineHeight = 23.sp,
                )
            }
        }
        Panel(Modifier.weight(1f).fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PanelTitle("데모")
                SwitchRow("데모 모드", "차량·내비·위치를 가상으로 움직여 시나리오를 보여 줘요(앱이 다시 시작돼요).", settings.demoMode, onDemoMode)
                if (settings.demoMode) {
                    SwitchRow("데모 시각 (평일 18:40)", "영업시간 시나리오를 재현하려고 퇴근 시각으로 맞춰요.", settings.demoClock, onDemoClock)
                }
                PanelTitle("집 · 회사")
                Text(
                    "집 ${if (settings.home != null) "저장됨" else "데모 좌표"} · 회사 ${if (settings.work != null) "저장됨" else "데모 좌표"}",
                    color = DC.Text, fontSize = 20.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("지금 위치를 집으로", onSetHomeHere, Modifier.weight(1f), height = 60.dp, fontSize = 18.sp)
                    BigButton("지금 위치를 회사로", onSetWorkHere, Modifier.weight(1f), height = 60.dp, fontSize = 18.sp)
                }
                BigButton("데모 좌표로 되돌리기", onResetPlaces, style = ButtonStyle.GHOST, height = 56.dp, fontSize = 18.sp, enabled = settings.home != null || settings.work != null)
                PanelTitle("차량 연결 상태")
                features.forEach { (name, st) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(stateColor(st)))
                        Text(name, color = DC.Text, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        Text(stateLabel(st), color = DC.Sub, fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyField(label: String, saved: String, value: String, onChange: (String) -> Unit, help: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(label, color = DC.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (saved.isBlank()) "없음" else "저장됨 ${maskKey(saved)}", color = if (saved.isBlank()) DC.Hold else DC.Brand, fontSize = 17.sp)
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(DC.Bg)
                .border(1.dp, DC.Line, RoundedCornerShape(16.dp)).padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(if (saved.isBlank()) "붙여 넣기" else "바꾸려면 새 키 입력", color = DC.Hold, fontSize = 19.sp)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = DC.Text, fontSize = 19.sp),
                visualTransformation = PasswordVisualTransformation(),
                cursorBrush = SolidColor(DC.Brand),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(help, color = DC.Sub, fontSize = 15.sp)
    }
}

@Composable
private fun SwitchRow(title: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DC.RowRadius)).background(DC.Surface2)
            .clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DC.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(help, color = DC.Sub, fontSize = 15.sp)
        }
        Spacer(Modifier.size(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = DC.Brand, checkedThumbColor = DC.OnBrand),
        )
    }
}

private fun stateColor(s: FeatureState) = when (s) {
    FeatureState.ACTIVE -> DC.Brand
    FeatureState.UNKNOWN -> DC.Hold
    FeatureState.NOT_SUPPORTED -> DC.Hold
    FeatureState.PERMISSION_DENIED, FeatureState.FAILED -> DC.Urgent
}

private fun stateLabel(s: FeatureState) = when (s) {
    FeatureState.ACTIVE -> "연결됨"
    FeatureState.UNKNOWN -> "확인 중"
    FeatureState.NOT_SUPPORTED -> "이 차량은 지원 안 함"
    FeatureState.PERMISSION_DENIED -> "권한 없음"
    FeatureState.FAILED -> "연결 실패"
}

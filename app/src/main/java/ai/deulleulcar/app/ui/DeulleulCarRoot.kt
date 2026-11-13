package ai.deulleulcar.app.ui

import ai.deulleulcar.app.AppContainer
import ai.deulleulcar.app.demo.DemoBar
import ai.deulleulcar.app.demo.DemoPanel
import ai.deulleulcar.app.voice.SpokenLine
import ai.deulleulcar.app.flow.AppUiState
import ai.deulleulcar.app.flow.Screen
import ai.deulleulcar.app.ui.theme.DC
import ai.deulleulcar.core.trip.TripCopy
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 화면 선택: 긴급 알림(S4) > 주행 중 미니 카드(S3) > 정차 중 화면(S1·S2·S5·S6).
 * 주행 중에는 설정·상세·글자 입력을 열지 않는다(주의분산 최소화).
 */
@Composable
fun DeulleulCarRoot(container: AppContainer, onDemoMode: (Boolean) -> Unit) {
    val c = container.controller
    val state by c.state.collectAsState()
    val settings by container.settingsStore.settings.collectAsState()
    val clock by produceState(TripCopy.time(container.now())) {
        while (true) {
            value = TripCopy.time(container.now())
            delay(5_000)
        }
    }
    var demoOpen by rememberSaveable { mutableStateOf(false) }
    val demo = container.demo
    val demoUi = demo?.director?.ui?.collectAsState()?.value
    val demoLine = container.demoCaptions?.collectAsState()?.value

    Box(Modifier.fillMaxSize().background(DC.Bg)) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                driving = state.driving,
                clock = clock,
                mode = state.mode,
                middle = statusMiddle(state),
            ) {
                if (container.demo != null) {
                    BigButton("데모", { demoOpen = !demoOpen }, style = if (demoOpen) ButtonStyle.PRIMARY else ButtonStyle.NEUTRAL, height = 44.dp, fontSize = 17.sp)
                }
                if (!state.driving && state.screen != Screen.SETTINGS) {
                    BigButton("설정", c::openSettings, style = ButtonStyle.NEUTRAL, height = 44.dp, fontSize = 17.sp)
                }
            }
            if (demo != null && demoUi != null && demoUi.visible) {
                DemoBar(
                    demoUi, onSpeed = demo.director::toggleSpeed, onNext = demo.director::next, onDismiss = demo.director::dismiss,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val urgent = state.urgent
                when {
                    urgent != null -> UrgentScreen(
                        urgent, repeatMinutes = 10, onAccept = c::urgentAccept, onLater = c::urgentLater,
                        onDetail = if (state.driving) null else c::urgentDetails,
                    )
                    state.driving -> DrivingScreen(state, c::onMicTapped, onYes = { c.reply(true) }, onNo = { c.reply(false) })
                    state.screen == Screen.SETTINGS -> SettingsScreen(
                        settings = settings,
                        features = featureList(container),
                        onBack = c::goHome,
                        onSaveKeys = { g, k -> container.settingsStore.update { it.copy(geminiApiKey = g, kakaoRestApiKey = k) } },
                        onDemoMode = onDemoMode,
                        onDemoClock = { on -> container.settingsStore.update { it.copy(demoClock = on) } },
                        onSetHomeHere = { container.saveCurrentLocation(home = true) },
                        onSetWorkHere = { container.saveCurrentLocation(home = false) },
                        onResetPlaces = { container.settingsStore.update { it.copy(home = null, work = null) } },
                    )
                    state.screen == Screen.DETAIL && state.detail != null -> DetailScreen(
                        state.detail!!, guiding = state.trip != null, onBack = c::goHome, onChoose = c::chooseFix,
                        onLater = { c.snoozeTask(state.detail!!.item.task.id) },
                    )
                    state.screen == Screen.PLAN && state.proposal != null -> PlanScreen(
                        state.proposal!!, state.voice.listening,
                        onAccept = c::acceptSuggestion, onDecline = c::declineSuggestion,
                        onStart = c::confirmPlan, onEdit = c::cancelProposal, onMic = c::onMicTapped,
                    )
                    else -> HomeScreen(
                        state,
                        onMic = c::onMicTapped,
                        onSubmitText = c::submitUtterance,
                        onOpenTask = c::openTask,
                        onAcceptForLater = c::acceptTaskForLater,
                        onSnooze = c::snoozeTask,
                        onCancelTrip = c::cancelTrip,
                        onShowPlan = c::showPlan,
                    )
                }
                Captions(state, demoLine, onDismissNotice = c::dismissNotice, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
        if (demo != null) {
            AnimatedVisibility(
                demoOpen, modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally { it }, exit = slideOutHorizontally { it },
            ) {
                DemoPanel(demo, onClose = { demoOpen = false })
            }
        }
    }
}

private fun statusMiddle(s: AppUiState): String? = when {
    s.screen == Screen.PLAN && s.proposal != null && !s.driving -> "\"${s.proposal.proposal.utterance}\""
    s.trip != null -> "→ ${s.trip.destinationName} · 남은 곳 ${s.trip.rolling.remaining.size}"
    else -> null
}

private fun featureList(container: AppContainer) = buildList {
    container.vehicle.features.value.forEach { (f, st) -> add("차량 · ${f.label}" to st) }
    add("음성 인식" to container.speechInput.state.value)
    add("음성 안내" to container.speechOutput.state.value)
    add("내비게이션" to container.navi.state.value)
    add("위치" to container.location.state.value)
}

private val ai.deulleulcar.app.vehicle.VehicleFeature.label: String
    get() = when (this) {
        ai.deulleulcar.app.vehicle.VehicleFeature.TELLTALES -> "경고등"
        ai.deulleulcar.app.vehicle.VehicleFeature.RANGE -> "주행 가능 거리"
        ai.deulleulcar.app.vehicle.VehicleFeature.GEAR -> "기어"
        ai.deulleulcar.app.vehicle.VehicleFeature.ODOMETER -> "누적 주행거리"
        ai.deulleulcar.app.vehicle.VehicleFeature.BRAKE_FLUID -> "브레이크액"
        ai.deulleulcar.app.vehicle.VehicleFeature.ENGINE_OIL -> "엔진오일"
        ai.deulleulcar.app.vehicle.VehicleFeature.WASHER_FLUID -> "워셔액"
    }

/**
 * 아래쪽 자막: 안내(오류 등)와 앱이 방금 한 말. 데모 모드는 소리 대신 가짜 TTS가 말한 문장([demoLine])을
 * 모두 띄운다(도착 안내·긴급 안내 포함).
 */
@Composable
private fun Captions(state: AppUiState, demoLine: SpokenLine?, onDismissNotice: () -> Unit, modifier: Modifier) {
    val notice = state.notice
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            onDismissNotice()
        }
    }
    val spoken = demoLine?.text ?: state.voice.spoken
    val spokenKey: Any? = demoLine ?: state.voice.spoken
    var showSpoken by remember { mutableStateOf(false) }
    LaunchedEffect(spokenKey) {
        showSpoken = spoken != null
        delay(9_000)
        showSpoken = false
    }
    // S2는 말풍선이 따로 있으므로 자막을 겹쳐 띄우지 않는다.
    val onPlan = state.screen == Screen.PLAN && state.proposal != null && !state.driving && state.urgent == null
    val text = notice ?: spoken?.takeIf { showSpoken && !onPlan }
    AnimatedVisibility(text != null, modifier = modifier.padding(bottom = 18.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(
            (if (notice != null) "" else "🔊 ") + (text ?: ""),
            color = DC.Text, fontSize = 19.sp, maxLines = 3,
            modifier = Modifier.widthIn(max = 1000.dp).clip(RoundedCornerShape(16.dp)).background(DC.Surface2.copy(alpha = 0.96f))
                .clickable(role = Role.Button, onClick = onDismissNotice).padding(horizontal = 22.dp, vertical = 12.dp),
        )
    }
}

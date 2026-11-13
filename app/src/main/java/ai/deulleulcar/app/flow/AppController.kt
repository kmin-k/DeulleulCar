package ai.deulleulcar.app.flow

import ai.deulleulcar.app.location.LocationSource
import ai.deulleulcar.app.navi.NaviPort
import ai.deulleulcar.app.navi.NaviPortEvent
import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.app.vehicle.VehicleSignalSource
import ai.deulleulcar.app.voice.SpeechInput
import ai.deulleulcar.app.voice.SpeechOutput
import ai.deulleulcar.app.voice.SttEvent
import ai.deulleulcar.app.voice.VoiceAnnouncer
import ai.deulleulcar.core.cartask.CarTaskClassifier
import ai.deulleulcar.core.cartask.CarTaskUpdate
import ai.deulleulcar.core.model.CarSignal
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskStatus
import ai.deulleulcar.core.model.DriveState
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Severity
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.navi.GuidanceStatus
import ai.deulleulcar.core.navi.NaviCommand
import ai.deulleulcar.core.navi.NaviEvent
import ai.deulleulcar.core.navi.RollingWaypointMachine
import ai.deulleulcar.core.plan.OpportunityPolicy
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.route.Josa
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.route.RouteSpeech
import ai.deulleulcar.core.trip.KnownPlaces
import ai.deulleulcar.core.trip.PlanResult
import ai.deulleulcar.core.trip.Reply
import ai.deulleulcar.core.trip.ReplyInterpreter
import ai.deulleulcar.core.trip.TripCopy
import ai.deulleulcar.core.trip.TripPlanner
import ai.deulleulcar.core.voice.SpeechPriority
import ai.deulleulcar.core.voice.Utterance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/**
 * @property urgentRepeat 긴급 알림에서 "나중에"를 고르면 이만큼 뒤에 다시 알린다(ux-flow: 10분, 끌 수 없음).
 * @property startupWindow 시작 직후 이 시간 동안 들어온 권장·보류 할 일은 하나씩 말하지 않고 한 줄로 요약한다.
 * @property autoListen 질문(“출발할까요?” 등)을 말한 뒤 정차 중이면 마이크를 자동으로 연다.
 */
data class ControllerConfig(
    val urgentRepeat: Duration = Duration.ofMinutes(10),
    val startupWindow: Duration = Duration.ofSeconds(3),
    val autoListen: Boolean = true,
    val autoListenTimeoutMs: Long = 6_000,
)

/**
 * 앱 흐름 전체를 잇는 컨트롤러(화면 상태 + 음성 + 내비 + 차량 신호).
 *
 * 규칙(spec·ux-flow):
 * - 사람의 볼일: 발화 → [TripPlanner] → 계획 제안(S2) → "응"/출발 → [RollingWaypointMachine] → [NaviPort].
 * - 차의 할 일: **사용자가 수락하기 전에는 경로에 넣지 않는다.** 긴급은 즉시 S4 + TTS, 권장은 정차 중 한 번 묻고
 *   경로를 짤 때 "+N분" 추천, 보류는 목록에만. 주행 중 기회는 [OpportunityPolicy]로 주행당 1회.
 * - 말 걸기는 [VoiceAnnouncer](회전 300m 이내 일반 문장 보류)를 거친다.
 *
 * 모든 상태 변경은 [scope](메인 스레드 하나)에서 일어난다. 무거운 계산만 [compute]로 넘긴다.
 */
class AppController(
    private val scope: CoroutineScope,
    private val vehicle: VehicleSignalSource,
    private val speechIn: SpeechInput,
    private val speechOut: SpeechOutput,
    private val announcer: VoiceAnnouncer,
    private val navi: NaviPort,
    private val location: LocationSource,
    private val planner: TripPlanner,
    private val places: () -> KnownPlaces,
    /** 계획에 쓰는 현재 시각(데모 모드는 "평일 18:40" 고정 가능). */
    private val now: () -> LocalDateTime,
    mode: ModeInfo,
    private val config: ControllerConfig = ControllerConfig(),
    private val clock: Clock = Clock.systemDefaultZone(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    /** 위치를 모를 때(또는 데모 장소인데 데모 구역 밖일 때) 쓸 출발점. */
    private val fallbackOrigin: () -> GeoPoint = { places().workPoint },
) {
    private val _state = MutableStateFlow(AppUiState(mode = mode))
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    val classifier = CarTaskClassifier(clock)
    private val replies = ReplyInterpreter()
    private val opportunities = OpportunityPolicy()

    private var machine: RollingWaypointMachine? = null
    private var tripId: String? = null
    /** 주행 중 기회 판단의 기준이 된 남은 계획(수락하면 여기에 합친다). */
    private var tripRemainingPlan: RoutePlan? = null
    private val visitedTaskIds = mutableSetOf<String>()
    /** 주행 중이라 말하지 못한 권장 할 일(정차하면 한 번 묻는다). */
    private val pendingParkedAsk = linkedSetOf<String>()
    private val askedTaskIds = mutableSetOf<String>()
    private val urgentRepeatJobs = mutableMapOf<String, Job>()
    private val bestFix = mutableMapOf<String, Suggestion>()

    private var started = false
    private var startupUntil: java.time.Instant = java.time.Instant.MIN
    private var autoListening = false
    private var planJob: Job? = null
    private var hintJob: Job? = null
    private val jobs = mutableListOf<Job>()

    // ───────────────────────── 시작·종료 ─────────────────────────

    fun start() {
        if (started) return
        started = true
        startupUntil = clock.instant().plus(config.startupWindow)
        speechOut.start()
        speechIn.start()
        navi.start()
        location.start()
        vehicle.start()

        jobs += scope.launch { vehicle.signals.collect { onSignal(it) } }
        jobs += scope.launch { navi.events.collect { onNaviEvent(it) } }
        jobs += scope.launch { location.proximity.collect { onMachineEvent(it.toNaviEvent()) } }
        jobs += scope.launch { speechIn.events.collect { onStt(it) } }

        // 이미 켜져 있는 경고·현재 기어를 먼저 반영(늦게 구독하면 지난 신호를 못 받는다)
        val snap = vehicle.snapshot.value
        snap.gear?.let { onSignal(CarSignal.GearChanged(it)) }
        snap.telltales.forEach { (type, st) -> onSignal(CarSignal.Telltale(type, st)) }
        snap.rangeKm?.let { onSignal(CarSignal.RemainingRange(it)) }
        snap.odometerKm?.let { onSignal(CarSignal.Odometer(it)) }

        jobs += scope.launch {
            delay(config.startupWindow.toMillis())
            startupSummary()
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        urgentRepeatJobs.values.forEach { it.cancel() }
        urgentRepeatJobs.clear()
        planJob?.cancel()
        hintJob?.cancel()
        started = false
    }

    fun setMode(mode: ModeInfo) = _state.update { it.copy(mode = mode) }

    // ───────────────────────── 화면 이동 ─────────────────────────

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS) }

    fun goHome() = _state.update { it.copy(screen = Screen.HOME, detail = null) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun showNotice(text: String) = _state.update { it.copy(notice = text) }

    /** 홈에서 확인 중인 계획(S2)으로 돌아간다. */
    fun showPlan() = _state.update { if (it.proposal != null) it.copy(screen = Screen.PLAN) else it }

    fun openTask(taskId: String) {
        val item = _state.value.tasks.firstOrNull { it.task.id == taskId } ?: return
        _state.update { it.copy(screen = Screen.DETAIL, detail = DetailUi(item, null, agoText(item.task))) }
        scope.launch {
            val options = safely { fixOptionsFor(item.task, limit = 3) }.orEmpty()
            _state.update { s ->
                if (s.detail?.item?.task?.id != taskId) s else s.copy(detail = s.detail.copy(options = options))
            }
        }
    }

    // ───────────────────────── 음성·텍스트 입력 ─────────────────────────

    fun onMicTapped() {
        if (speechIn.listening.value) {
            speechIn.stopListening()
            return
        }
        speechOut.stopAll()
        autoListening = false
        speechIn.listen()
    }

    /** 말(STT 최종 결과)이나 정차 중 텍스트 입력. 지금 묻고 있는 질문이 있으면 그 답으로 먼저 읽는다. */
    fun submitUtterance(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(voice = it.voice.copy(heard = text, partial = null), notice = null) }
        val reply = replies.interpret(text)
        if (reply == Reply.YES || reply == Reply.NO) {
            if (answer(reply == Reply.YES)) return
        }
        if (reply == Reply.UNKNOWN && currentQuestion() != null && looksLikeChatter(text)) {
            say("잘 못 알아들었어요. ‘응’이나 ‘아니’로 말해 주세요.")
            return
        }
        request(text)
    }

    private fun looksLikeChatter(text: String) = text.split(' ').size <= 2 && !text.contains("들")

    private fun onStt(e: SttEvent) {
        when (e) {
            SttEvent.Listening -> _state.update { it.copy(voice = it.voice.copy(listening = true, partial = null)) }
            is SttEvent.Partial -> _state.update { it.copy(voice = it.voice.copy(partial = e.text)) }
            is SttEvent.Final -> {
                autoListening = false
                _state.update { it.copy(voice = it.voice.copy(listening = false, partial = null)) }
                submitUtterance(e.text)
            }
            is SttEvent.NoResult -> {
                val quiet = autoListening
                autoListening = false
                _state.update {
                    it.copy(
                        voice = it.voice.copy(listening = false, partial = null),
                        notice = if (quiet) it.notice else "잘 못 들었어요. 다시 말해 주세요.",
                    )
                }
            }
            is SttEvent.Error -> {
                val quiet = autoListening
                autoListening = false
                _state.update {
                    it.copy(
                        voice = it.voice.copy(listening = false, partial = null),
                        notice = if (quiet) it.notice else "음성 인식을 쓸 수 없어요. 버튼이나 글자로 답해 주세요.",
                    )
                }
            }
            SttEvent.Ended -> _state.update { it.copy(voice = it.voice.copy(listening = false)) }
        }
    }

    // ───────────────────────── 질문과 대답 ─────────────────────────

    private sealed interface Question {
        data class Urgent(val ui: UrgentUi) : Question
        data class Opportunity(val ui: OpportunityUi) : Question
        data class Addition(val ui: AdditionUi) : Question
        data class PlanSuggestion(val suggestion: Suggestion) : Question
        data object ConfirmPlan : Question
        data class ParkedAsk(val taskId: String) : Question
    }

    private fun currentQuestion(): Question? {
        val s = _state.value
        s.urgent?.let { return Question.Urgent(it) }
        s.opportunity?.let { return Question.Opportunity(it) }
        s.addition?.let { return Question.Addition(it) }
        s.proposal?.let { p -> return p.pending.firstOrNull()?.let { Question.PlanSuggestion(it) } ?: Question.ConfirmPlan }
        s.parkedAskTaskId?.let { return Question.ParkedAsk(it) }
        return null
    }

    /** 화면의 큰 예/아니오 버튼(주행 중 질문 카드). 지금 묻고 있는 질문에 답한다. */
    fun reply(yes: Boolean) {
        answer(yes)
    }

    /** 긴급 알림에서 "자세히"(정차 중). 거절이 아니므로 할 일 상태는 그대로 둔다. */
    fun urgentDetails() {
        val u = _state.value.urgent ?: return
        _state.update { it.copy(urgent = null) }
        openTask(u.task.id)
    }

    /** @return 질문이 있어서 대답으로 처리했으면 true. */
    private fun answer(yes: Boolean): Boolean {
        when (val q = currentQuestion() ?: return false) {
            is Question.Urgent -> if (yes) urgentAccept() else urgentLater()
            is Question.Opportunity -> if (yes) opportunityAccept() else opportunityDecline()
            is Question.Addition -> if (yes) additionAccept() else additionDecline()
            is Question.PlanSuggestion -> if (yes) acceptSuggestion(q.suggestion) else declineSuggestion(q.suggestion)
            Question.ConfirmPlan -> if (yes) confirmPlan() else cancelProposal()
            is Question.ParkedAsk -> if (yes) acceptTaskForLater(q.taskId) else snoozeTask(q.taskId)
        }
        return true
    }

    // ───────────────────────── 사람의 볼일: 계획 ─────────────────────────

    private fun request(text: String) {
        if (machine?.state?.status == GuidanceStatus.GUIDING) addWhileGuiding(text) else proposePlan(text)
    }

    private fun proposePlan(text: String) {
        planJob?.cancel()
        _state.update { it.copy(planning = true, parkedAskTaskId = null) }
        planJob = scope.launch {
            val origin = origin()
            val tasks = classifier.tasks
            val accepted = tasks.filter { it.status == CarTaskStatus.ACCEPTED && it.id !in visitedTaskIds }
            val suggestible = tasks.filter { it.status == CarTaskStatus.OPEN || it.status == CarTaskStatus.SNOOZED }
            val result = safely {
                withContext(compute) { planner.plan(text, origin, places(), now(), accepted, suggestible) }
            }
            _state.update { it.copy(planning = false) }
            when (result) {
                null -> say("지금은 경로를 짤 수 없어요. 잠시 뒤에 다시 말해 주세요.")
                is PlanResult.NoErrands ->
                    say("어디에 들를지 잘 못 들었어요. ‘퇴근길에 약국 들러’처럼 말해 주세요.")
                is PlanResult.NothingReachable ->
                    say(TripCopy.excludedSentence(result.plan.excluded) ?: "들를 수 있는 곳을 찾지 못했어요.")
                is PlanResult.Ready -> {
                    val pending = result.proposal.suggestions.take(MAX_PLAN_SUGGESTIONS)
                    val speech = TripCopy.proposalSpeech(result.proposal.plan, pending.firstOrNull())
                    _state.update {
                        it.copy(
                            screen = Screen.PLAN,
                            proposal = ProposalUi(result.proposal, result.proposal.plan, pending, speech),
                        )
                    }
                    ask(speech)
                }
            }
        }
    }

    fun acceptSuggestion(s: Suggestion) {
        val p = _state.value.proposal ?: return
        val merged = planner.merge(p.plan, s)
        if (merged == null) {
            dropPending(p, s, "${s.place.name}에는 지금 들를 수 없어요.")
            return
        }
        classifier.accept(s.task.id)
        val rest = p.pending.filter { it.task.id != s.task.id }
        updateProposal(p.copy(plan = merged, pending = rest), "${s.place.name}까지 넣었어요.")
        refreshTasks()
    }

    fun declineSuggestion(s: Suggestion) {
        val p = _state.value.proposal ?: return
        classifier.decline(s.task.id)
        dropPending(p, s, "알겠어요. 목록에 남겨 둘게요.")
        refreshTasks()
    }

    private fun dropPending(p: ProposalUi, s: Suggestion, lead: String) =
        updateProposal(p.copy(pending = p.pending.filter { it.task.id != s.task.id }), lead)

    /** 추천 하나에 답한 뒤: 남은 추천이 있으면 그것을, 없으면 출발을 묻는다. 남은 추천은 새 계획 기준으로 다시 잰다. */
    private fun updateProposal(p: ProposalUi, lead: String) {
        val refreshed = p.copy(pending = p.pending.mapNotNull { planner.reevaluate(p.plan, it) })
        val follow = refreshed.pending.firstOrNull()?.let { TripCopy.suggestionSpeech(it) } ?: "출발할까요?"
        val speech = "$lead $follow"
        _state.update { it.copy(proposal = refreshed.copy(speech = speech)) }
        ask(speech)
    }

    fun confirmPlan() {
        val p = _state.value.proposal ?: return
        // 답하지 않은 추천은 거절이 아니다: 상태를 바꾸지 않고 목록에 그대로 둔다.
        startTrip(p.plan, "출발할게요. ${firstStopSentence(p.plan)}")
    }

    fun cancelProposal() {
        val p = _state.value.proposal ?: return
        reopenUnvisited(p.plan.stops.map { it.stop })
        _state.update { it.copy(proposal = null, screen = Screen.HOME) }
        say("알겠어요. 계획을 취소했어요.")
    }

    private fun firstStopSentence(plan: RoutePlan): String {
        val first = plan.stops.firstOrNull() ?: return "${plan.destination.name ?: "목적지"}까지 안내할게요."
        return "먼저 ${Josa.ieyo(RouteSpeech.nameOf(first.stop))}."
    }

    // ───────────────────────── 안내(롤링 경유지) ─────────────────────────

    private fun startTrip(plan: RoutePlan, speech: String?) {
        endTrip(arrived = false, cancelNavi = false)
        val stops = plan.orderedStops.distinctBy { it.place.id }
        val m = RollingWaypointMachine(plan.destination, stops)
        machine = m
        tripId = UUID.randomUUID().toString()
        tripRemainingPlan = plan
        stops.forEach { location.addProximityAlert(it.place.id, it.place.point) }
        _state.update {
            it.copy(proposal = null, screen = Screen.HOME, trip = TripUi(tripId!!, plan, m.state), parkedAskTaskId = null)
        }
        execute(m.handle(NaviEvent.Start))
        speech?.let { say(it) }
        checkOpportunity()
        refreshFixHints()
    }

    /** 사용자가 안내를 끝냄(S1 "안내 종료"). */
    fun cancelTrip() {
        if (machine == null) return
        endTrip(arrived = false, cancelNavi = true)
        say("안내를 끝냈어요.")
    }

    private fun endTrip(arrived: Boolean, cancelNavi: Boolean) {
        val m = machine ?: return
        val st = m.state
        // 수락했지만 들르지 못한 차의 할 일은 다시 열어 둔다(목록에 남고 다음에 다시 추천).
        reopenUnvisited(st.remaining + st.skipped)
        if (cancelNavi) navi.cancelRoute()
        location.removeAllProximityAlerts()
        tripId?.let { opportunities.endTrip(it) }
        machine = null
        tripId = null
        tripRemainingPlan = null
        _state.update { it.copy(trip = null, opportunity = null, addition = null) }
        refreshTasks()
        if (arrived) {
            TripCopy.remainingSummary(activeTasks())?.let { say(it) }
        }
    }

    private fun reopenUnvisited(stops: List<Stop>) {
        stops.filter { it.source == StopSource.CAR }.mapNotNull { it.linkedTaskId }.forEach { id ->
            if (classifier.find(id)?.status == CarTaskStatus.ACCEPTED && id !in visitedTaskIds) classifier.reopen(id)
        }
    }

    private fun execute(commands: List<NaviCommand>) {
        if (commands.isNotEmpty()) navi.execute(commands)
        val m = machine ?: return
        _state.update { s -> s.trip?.let { s.copy(trip = it.copy(rolling = m.state)) } ?: s }
    }

    private fun onNaviEvent(e: NaviPortEvent) {
        when (e) {
            is NaviPortEvent.Machine -> onMachineEvent(e.event)
            is NaviPortEvent.Error -> _state.update { it.copy(notice = "내비 응답 오류(${e.code}). 경로를 다시 요청해 주세요.") }
            else -> Unit
        }
    }

    private fun onMachineEvent(event: NaviEvent) {
        val m = machine ?: return
        val before = m.state.visited.size
        execute(m.handle(event))
        val st = m.state
        st.visited.drop(before).forEach { stop ->
            location.removeProximityAlert(stop.place.id)
            stop.linkedTaskId?.let { visitedTaskIds += it }
        }
        if (st.status == GuidanceStatus.FINISHED) {
            endTrip(arrived = true, cancelNavi = false)
            return
        }
        if (st.visited.size > before) {
            tripRemainingPlan = null
            refreshTripPlan()
            checkOpportunity()
        }
    }

    /** 남은 정류지 기준으로 도착 예정 시각을 다시 계산한다. */
    private fun refreshTripPlan() {
        val m = machine ?: return
        val trip = _state.value.trip ?: return
        scope.launch {
            val plan = safely {
                withContext(compute) { planner.remainingPlan(m.state.remaining, origin(), trip.plan.destination, now()) }
            } ?: return@launch
            tripRemainingPlan = plan
            _state.update { s -> s.trip?.let { s.copy(trip = it.copy(plan = plan)) } ?: s }
        }
    }

    private suspend fun remainingPlan(): RoutePlan? {
        val m = machine ?: return null
        val dest = _state.value.trip?.plan?.destination ?: return null
        tripRemainingPlan?.let { if (it.stops.map { s -> s.stop.place.id } == m.state.remaining.map { s -> s.place.id }) return it }
        return withContext(compute) { planner.remainingPlan(m.state.remaining, origin(), dest, now()) }
            .also { tripRemainingPlan = it }
    }

    /** 안내 중 계획을 바꾼다(차의 할 일 수락, 볼일 추가). 내비에는 롤링 머신이 앞 2곳만 다시 넣는다. */
    private fun replaceRemaining(plan: RoutePlan) {
        val m = machine ?: return
        tripRemainingPlan = plan
        plan.orderedStops.forEach { location.addProximityAlert(it.place.id, it.place.point) }
        execute(m.handle(NaviEvent.RemainingReplaced(plan.orderedStops)))
        _state.update { s -> s.trip?.let { s.copy(trip = it.copy(plan = plan, rolling = m.state)) } ?: s }
    }

    // 주행 중 "하나 더 들러"
    private fun addWhileGuiding(text: String) {
        planJob?.cancel()
        _state.update { it.copy(planning = true) }
        planJob = scope.launch {
            val base = safely { remainingPlan() }
            val result = base?.let { b -> safely { withContext(compute) { planner.addErrands(text, b, origin(), now()) } } }
            _state.update { it.copy(planning = false) }
            val plan = result?.second
            if (base == null || plan == null) {
                say(if (result?.first?.request?.errands.isNullOrEmpty()) "어디에 더 들를지 잘 못 들었어요." else "경로 근처에서 찾지 못했어요.")
                return@launch
            }
            val baseIds = base.stops.map { it.stop.place.id }.toSet()
            val added = plan.stops.filter { it.stop.place.id !in baseIds }.map { RouteSpeech.nameOf(it.stop) }
            val extra = RoutePlan.roundMinutes(plan.totalTime.minus(base.totalTime))
            val speech = "${Josa.eulReul(added.joinToString(", "))} 넣으면 ${extra}분 더 걸려요. 들를까요?"
            _state.update { it.copy(addition = AdditionUi(plan, added, extra, speech)) }
            ask(speech)
        }
    }

    fun additionAccept() {
        val a = _state.value.addition ?: return
        _state.update { it.copy(addition = null) }
        replaceRemaining(a.plan)
        say("${Josa.eulReul(a.addedNames.joinToString(", "))} 넣었어요.")
    }

    fun additionDecline() {
        _state.update { it.copy(addition = null) }
        say("알겠어요. 그대로 갈게요.")
    }

    // ───────────────────────── 차의 할 일 ─────────────────────────

    private fun onSignal(signal: CarSignal) {
        if (signal is CarSignal.GearChanged) onDriveState(DriveState.from(signal.gear))
        when (val u = classifier.onSignal(signal)) {
            is CarTaskUpdate.Created -> {
                refreshTasks()
                onTaskRaised(u.task)
            }
            is CarTaskUpdate.SeverityChanged -> {
                refreshTasks()
                if (u.after.severity.ordinal < u.before.severity.ordinal) onTaskRaised(u.after)
            }
            is CarTaskUpdate.Resolved -> onTaskResolved(u.task)
            CarTaskUpdate.None -> if (signal !is CarSignal.GearChanged) refreshTasks()
        }
    }

    private fun onTaskRaised(task: CarTask) {
        when (task.severity) {
            Severity.URGENT -> raiseUrgent(task)
            Severity.RECOMMEND -> {
                if (clock.instant().isBefore(startupUntil)) return
                if (_state.value.driving) {
                    pendingParkedAsk += task.id // 주행 중에는 배지만, 정차하면 한 번 묻는다
                    checkOpportunity()
                } else {
                    askParked(task)
                }
            }
            Severity.HOLD -> Unit // 목록에만
        }
        refreshFixHints()
    }

    private fun onTaskResolved(task: CarTask) {
        urgentRepeatJobs.remove(task.id)?.cancel()
        pendingParkedAsk -= task.id
        bestFix -= task.id
        visitedTaskIds -= task.id
        _state.update { s ->
            s.copy(
                urgent = s.urgent?.takeIf { it.task.id != task.id },
                opportunity = s.opportunity?.takeIf { it.suggestion.task.id != task.id },
                parkedAskTaskId = s.parkedAskTaskId?.takeIf { it != task.id },
                detail = s.detail?.takeIf { it.item.task.id != task.id },
                screen = if (s.screen == Screen.DETAIL && s.detail?.item?.task?.id == task.id) Screen.HOME else s.screen,
                proposal = s.proposal?.let { p -> p.copy(pending = p.pending.filter { it.task.id != task.id }) },
                notice = "해결됐어요: ${TripCopy.title(task)}",
            )
        }
        refreshTasks()
    }

    private fun askParked(task: CarTask) {
        if (task.id in askedTaskIds) return
        askedTaskIds += task.id
        pendingParkedAsk -= task.id
        _state.update { it.copy(parkedAskTaskId = task.id) }
        ask(TripCopy.parkedRecommendSpeech(task, classifier.isEscalated(task)))
    }

    /** "다음 경로에 넣어 둘게요": 지금은 경로에 넣지 않고, 다음에 계획을 짤 때 해결지를 함께 넣는다. */
    fun acceptTaskForLater(taskId: String) {
        classifier.accept(taskId) ?: return
        _state.update { it.copy(parkedAskTaskId = null) }
        refreshTasks()
        say("좋아요. 다음 경로를 짤 때 넣어 둘게요.")
    }

    fun snoozeTask(taskId: String) {
        val task = classifier.decline(taskId) ?: return
        _state.update {
            it.copy(
                parkedAskTaskId = it.parkedAskTaskId?.takeIf { id -> id != taskId },
                screen = if (it.screen == Screen.DETAIL) Screen.HOME else it.screen,
                detail = null,
            )
        }
        refreshTasks()
        if (task.severity == Severity.URGENT) scheduleUrgentRepeat(taskId)
        say("알겠어요. 목록에 남겨 둘게요.")
    }

    /** S5에서 해결 장소 하나를 골랐다. 안내 중이면 지금 경로에 합치고, 아니면 긴급은 바로 안내, 나머지는 다음 경로에. */
    fun chooseFix(option: Suggestion) {
        val task = classifier.find(option.task.id) ?: return
        _state.update { it.copy(screen = Screen.HOME, detail = null) }
        when {
            machine != null -> mergeIntoTrip(task, option)
            task.severity == Severity.URGENT -> {
                classifier.accept(task.id)
                startTrip(option.preview, "${option.place.name}로 안내할게요.")
            }
            else -> acceptTaskForLater(task.id)
        }
    }

    private fun mergeIntoTrip(task: CarTask, option: Suggestion) {
        scope.launch {
            val base = safely { remainingPlan() } ?: return@launch
            val fresh = planner.reevaluate(base, option) ?: option
            val merged = planner.merge(base, fresh, origin(), now())
            if (merged == null) {
                say("${option.place.name}에는 지금 들를 수 없어요.")
                return@launch
            }
            classifier.accept(task.id)
            replaceRemaining(merged)
            refreshTasks()
            say("${option.place.name}${if (Josa.hasFinalConsonant(option.place.name)) "을" else "를"} 경로에 넣었어요.")
        }
    }

    // 긴급(S4)
    private fun raiseUrgent(task: CarTask) {
        urgentRepeatJobs.remove(task.id)?.cancel()
        val title = TripCopy.title(task)
        _state.update { it.copy(urgent = UrgentUi(task, null, searching = true, title = title)) }
        scope.launch {
            val fix = safely { fixOptionsFor(task, limit = 1) }?.firstOrNull()
            val current = classifier.find(task.id) ?: return@launch
            _state.update { s ->
                if (s.urgent?.task?.id != task.id) s else s.copy(urgent = UrgentUi(current, fix, false, TripCopy.title(current)))
            }
            announcer.announce(
                Utterance(TripCopy.urgentSpeech(current, fix), SpeechPriority.URGENT, shortText = TripCopy.urgentShort(current), expiresAfter = null),
            )
        }
    }

    fun urgentAccept() {
        val u = _state.value.urgent ?: return
        _state.update { it.copy(urgent = null) }
        val fix = u.fix
        if (fix == null) {
            say("근처에서 해결할 곳을 찾지 못했어요. 안전한 곳에 세워 주세요.", SpeechPriority.URGENT)
            return
        }
        chooseFix(fix)
    }

    fun urgentLater() {
        val u = _state.value.urgent ?: return
        _state.update { it.copy(urgent = null) }
        classifier.decline(u.task.id)
        refreshTasks()
        scheduleUrgentRepeat(u.task.id)
        say("${config.urgentRepeat.toMinutes()}분 뒤에 다시 알려 드릴게요.")
    }

    private fun scheduleUrgentRepeat(taskId: String) {
        urgentRepeatJobs.remove(taskId)?.cancel()
        urgentRepeatJobs[taskId] = scope.launch {
            delay(config.urgentRepeat.toMillis())
            urgentRepeatJobs.remove(taskId)
            val task = classifier.find(taskId) ?: return@launch
            if (task.severity == Severity.URGENT && task.status != CarTaskStatus.ACCEPTED) raiseUrgent(task)
        }
    }

    // 주행 중 기회(S3 카드)
    private fun checkOpportunity() {
        val m = machine ?: return
        val trip = tripId ?: return
        val s = _state.value
        if (!s.driving || s.urgent != null || s.opportunity != null || s.addition != null) return
        if (m.state.status != GuidanceStatus.GUIDING) return
        scope.launch {
            val candidates = classifier.tasks.filter {
                it.severity != Severity.URGENT && (it.status == CarTaskStatus.OPEN || it.status == CarTaskStatus.SNOOZED)
            }
            if (candidates.isEmpty()) return@launch
            val base = safely { remainingPlan() } ?: return@launch
            val list = safely { withContext(compute) { planner.suggestions(base, candidates, origin(), now()) } }.orEmpty()
            if (_state.value.opportunity != null || tripId != trip) return@launch
            val pick = opportunities.next(trip, list) ?: return@launch
            val speech = TripCopy.suggestionSpeech(pick, classifier.isEscalated(pick.task))
            _state.update { it.copy(opportunity = OpportunityUi(pick, speech)) }
            say(speech)
        }
    }

    fun opportunityAccept() {
        val o = _state.value.opportunity ?: return
        _state.update { it.copy(opportunity = null) }
        val task = classifier.find(o.suggestion.task.id) ?: return
        mergeIntoTrip(task, o.suggestion)
    }

    fun opportunityDecline() {
        val o = _state.value.opportunity ?: return
        _state.update { it.copy(opportunity = null) }
        classifier.decline(o.suggestion.task.id)
        refreshTasks()
        say("알겠어요. 목록에 남겨 둘게요.")
    }

    // 주행·정차 전환
    private fun onDriveState(next: DriveState) {
        val prev = _state.value.drive
        if (prev == next) return
        _state.update { it.copy(drive = next) }
        if (next == DriveState.PARKED) {
            // 주행 중 미뤄 둔 권장 할 일을 한 번 묻고, 미뤄 둔 긴급은 다시 알린다(ux-flow: "다음 정차 때").
            classifier.tasks.firstOrNull { it.severity == Severity.URGENT && it.status == CarTaskStatus.SNOOZED }
                ?.let { raiseUrgent(it); return }
            pendingParkedAsk.firstNotNullOfOrNull { classifier.find(it) }?.let { askParked(it) }
            pendingParkedAsk.clear()
        } else {
            _state.update { it.copy(parkedAskTaskId = null) }
            checkOpportunity()
        }
    }

    private fun startupSummary() {
        val waiting = classifier.tasks.filter { it.severity != Severity.URGENT }
        TripCopy.remainingSummary(waiting)?.let { say(it) }
        waiting.forEach { askedTaskIds += it.id } // 요약했으니 하나씩 다시 묻지 않는다
    }

    // ───────────────────────── 목록·힌트 ─────────────────────────

    private fun activeTasks(): List<CarTask> = classifier.tasks

    private fun refreshTasks() {
        val items = classifier.tasks
            .sortedWith(compareBy<CarTask> { it.severity.ordinal }.thenBy { it.detectedAt })
            .map { t ->
                val escalated = classifier.isEscalated(t)
                CarTaskItem(t, TripCopy.title(t), TripCopy.oneLiner(t, escalated), escalated, bestFix[t.id], t.id in visitedTaskIds)
            }
        _state.update { s ->
            s.copy(
                tasks = items,
                detail = s.detail?.let { d -> items.firstOrNull { it.task.id == d.item.task.id }?.let { d.copy(item = it) } ?: d },
            )
        }
    }

    /** 홈 카드의 "가장 빠른 해결 · ○○ 경로상 +N분". 정차 중에만 계산한다. */
    private fun refreshFixHints() {
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(HINT_DEBOUNCE_MS)
            val targets = classifier.tasks.filter { it.severity != Severity.HOLD || machine != null }
            for (t in targets) {
                val fix = safely { fixOptionsFor(t, limit = 1) }?.firstOrNull()
                if (fix != null) bestFix[t.id] = fix else bestFix -= t.id
            }
            refreshTasks()
        }
    }

    private suspend fun fixOptionsFor(task: CarTask, limit: Int): List<Suggestion> {
        val base = remainingPlan() ?: withContext(compute) { planner.directPlan(origin(), places().homePoint, now()) }
        return withContext(compute) { planner.fixOptions(task, base, origin(), now(), limit) }
    }

    // ───────────────────────── 도우미 ─────────────────────────

    private fun origin(): GeoPoint {
        val here = location.location.value?.point ?: return fallbackOrigin()
        val zone = fallbackOrigin()
        // 데모 장소로 도는데 데모 구역에서 멀리 있으면(에뮬레이터 기본 위치 등) 데모 구역 출발로 본다.
        return if (!_state.value.mode.kakao && here.distanceTo(zone) > DEMO_ZONE_RADIUS_M) zone else here
    }

    private fun say(text: String, priority: SpeechPriority = SpeechPriority.NORMAL) {
        _state.update { it.copy(voice = it.voice.copy(spoken = text)) }
        announcer.announce(Utterance(text, priority))
    }

    /** 질문을 말하고, 정차 중이면 다 말한 뒤 마이크를 연다(말 한 번, 확인 한 번). */
    private fun ask(text: String) {
        _state.update { it.copy(voice = it.voice.copy(spoken = text)) }
        if (_state.value.driving || !config.autoListen) {
            announcer.announce(Utterance(text))
            return
        }
        speechOut.speak(text, SpeechPriority.NORMAL) { completed ->
            scope.launch {
                val ready = speechIn.state.value == FeatureState.ACTIVE
                if (completed && ready && currentQuestion() != null && !_state.value.driving && !speechIn.listening.value) {
                    autoListening = true
                    speechIn.listen(config.autoListenTimeoutMs)
                }
            }
        }
    }

    private fun agoText(task: CarTask): String {
        val min = Duration.between(task.detectedAt, clock.instant()).toMinutes()
        return when {
            min < 1 -> "방금"
            min < 60 -> "${min}분 전"
            min < 60 * 24 -> "${min / 60}시간 전"
            else -> "${min / 60 / 24}일 전"
        }
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _state.update { it.copy(notice = "처리 중 문제가 생겼어요: ${e.javaClass.simpleName}") }
        null
    }

    companion object {
        const val MAX_PLAN_SUGGESTIONS = 2
        const val HINT_DEBOUNCE_MS = 300L
        const val DEMO_ZONE_RADIUS_M = 30_000.0
    }
}

package ai.deulleulcar.app

import ai.deulleulcar.app.demo.DemoControls
import ai.deulleulcar.app.flow.AppController
import ai.deulleulcar.app.flow.ModeInfo
import ai.deulleulcar.app.location.FakeLocationSource
import ai.deulleulcar.app.location.LocationSource
import ai.deulleulcar.app.location.PleosLocationSource
import ai.deulleulcar.app.navi.FakeNaviPort
import ai.deulleulcar.app.navi.NaviPort
import ai.deulleulcar.app.navi.PleosNaviPort
import ai.deulleulcar.app.sdk.FeatureState
import ai.deulleulcar.app.settings.Settings
import ai.deulleulcar.app.settings.SettingsStore
import ai.deulleulcar.app.vehicle.FakeVehicleSignalSource
import ai.deulleulcar.app.vehicle.PleosVehicleSignalSource
import ai.deulleulcar.app.vehicle.VehicleSignalSource
import ai.deulleulcar.app.voice.FakeSpeechInput
import ai.deulleulcar.app.voice.FakeSpeechOutput
import ai.deulleulcar.app.voice.PleosSpeechInput
import ai.deulleulcar.app.voice.PleosSpeechOutput
import ai.deulleulcar.app.voice.SpeechInput
import ai.deulleulcar.app.voice.SpeechOutput
import ai.deulleulcar.app.voice.VoiceAnnouncer
import ai.deulleulcar.core.llm.GeminiClient
import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.llm.LlmErrandParser
import ai.deulleulcar.core.place.DemoPlaceSearch
import ai.deulleulcar.core.place.DemoSeed
import ai.deulleulcar.core.place.PlaceQuery
import ai.deulleulcar.core.place.PlaceSearch
import ai.deulleulcar.core.place.PlaceSearchFactory
import ai.deulleulcar.core.place.PlaceSearchResult
import ai.deulleulcar.core.trip.KnownPlaces
import ai.deulleulcar.core.trip.TripPlanner
import ai.deulleulcar.core.voice.Utterance
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 수동 DI 컨테이너. 앱 전체에서 어댑터 인스턴스를 하나씩만 만든다.
 * @param useFakes true면 SDK 대신 Fake 구현(데모 모드·SDK 없는 기기). 설정의 데모 모드를 바꾸면 컨테이너를 새로 만든다.
 */
class AppContainer(
    context: Context,
    val settingsStore: SettingsStore,
    val useFakes: Boolean = settingsStore.settings.value.demoMode,
) {
    private val appContext = context.applicationContext
    private val createdAt = System.currentTimeMillis()

    /** 어댑터끼리 잇는 앱 수명 스코프(메인 스레드). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val settings: Settings get() = settingsStore.settings.value

    private val vehicleLazy = lazy {
        if (useFakes) FakeVehicleSignalSource() else PleosVehicleSignalSource(appContext)
    }
    val vehicle: VehicleSignalSource by vehicleLazy

    private val speechInputLazy = lazy { if (useFakes) FakeSpeechInput() else PleosSpeechInput(appContext) }
    val speechInput: SpeechInput by speechInputLazy

    private val speechOutputLazy = lazy { if (useFakes) FakeSpeechOutput() else PleosSpeechOutput(appContext) }
    val speechOutput: SpeechOutput by speechOutputLazy

    /** 말 걸기는 모두 여기로. TBT 거리에 따라 보류·즉시를 고른다. */
    val announcer: VoiceAnnouncer by lazy { VoiceAnnouncer(speechOutput) }

    private val naviLazy = lazy {
        (if (useFakes) FakeNaviPort() else PleosNaviPort(appContext)).also { port ->
            // 상태 머신의 Speak 명령 → 일반 우선순위 발화, TBT 거리 → 발화 보류 판단
            port.onSpeak = { text -> announcer.announce(Utterance(text)) }
            port.nextTurn
                .map { it?.distanceM }
                .distinctUntilChanged()
                .onEach { announcer.onTurnDistance(it) }
                .launchIn(appScope)
        }
    }
    val navi: NaviPort by naviLazy

    private val locationLazy = lazy {
        if (useFakes) FakeLocationSource(initial = DemoSeed.default.work) else PleosLocationSource(appContext)
    }
    val location: LocationSource by locationLazy

    /** 집·회사: 설정에 저장한 곳, 없으면 데모 구역 좌표. */
    fun knownPlaces(): KnownPlaces {
        val seed = DemoSeed.default
        return KnownPlaces(home = settings.home ?: seed.home, work = settings.work ?: seed.work)
    }

    /**
     * 계획에 쓰는 시각. 데모 모드에서 "데모 시각"을 켜면 시나리오 1의 평일 18:40에서 시작해 실제 시간만큼 흐른다.
     */
    fun now(): LocalDateTime {
        if (!(useFakes && settings.demoClock)) return LocalDateTime.now()
        return demoStart.plusNanos((System.currentTimeMillis() - createdAt) * 1_000_000)
    }

    private val demoStart: LocalDateTime by lazy {
        var day = LocalDateTime.now().toLocalDate()
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        day.atTime(LocalTime.of(18, 40))
    }

    fun modeInfo(s: Settings = settings) = ModeInfo(demo = useFakes, llm = s.hasGemini, kakao = s.hasKakao && !useFakes)

    /** 키가 바뀌면 다음 검색부터 새 키로. 데모 모드는 항상 데모 장소(데모 위치와 맞추기 위해). */
    private val placeSearch: PlaceSearch = object : PlaceSearch {
        private var cachedKey: String? = null
        private var cached: PlaceSearch? = null

        override suspend fun search(query: PlaceQuery): PlaceSearchResult = current().search(query)

        @Synchronized
        private fun current(): PlaceSearch {
            val key = if (useFakes) null else settings.kakaoRestApiKey.takeIf { it.isNotBlank() }
            if (cached == null || key != cachedKey) {
                cachedKey = key
                cached = PlaceSearchFactory.create(kakaoRestApiKey = key, demo = DemoPlaceSearch())
            }
            return cached!!
        }
    }

    /** LLM에는 발화 텍스트만 간다. 키는 호출할 때마다 설정에서 읽는다(바꾸면 바로 반영). */
    val planner: TripPlanner by lazy {
        val gemini = GeminiClient(apiKeyProvider = { settings.geminiApiKey.takeIf { it.isNotBlank() } })
        TripPlanner(HybridErrandParser(LlmErrandParser(gemini)), placeSearch)
    }

    private val controllerLazy = lazy {
        AppController(
            scope = appScope,
            vehicle = vehicle,
            speechIn = speechInput,
            speechOut = speechOutput,
            announcer = announcer,
            navi = navi,
            location = location,
            planner = planner,
            places = ::knownPlaces,
            now = ::now,
            mode = modeInfo(),
        ).also { c ->
            settingsStore.settings.map { modeInfo(it) }.distinctUntilChanged().onEach { c.setMode(it) }.launchIn(appScope)
        }
    }
    val controller: AppController by controllerLazy

    /** 데모 모드일 때만 있다(가짜 차량·내비·위치를 손으로 움직이는 패널). */
    val demo: DemoControls? by lazy {
        if (!useFakes) null else DemoControls(
            vehicle = vehicle as FakeVehicleSignalSource,
            navi = navi as FakeNaviPort,
            location = location as FakeLocationSource,
            speechInput = speechInput as FakeSpeechInput,
            controller = controller,
        )
    }

    /** 런타임 권한 결과가 온 뒤: 권한이 없어 꺼져 있던 위치·차량 신호를 다시 켠다(이미 켜져 있으면 그대로). */
    fun onPermissionsChanged() {
        if (locationLazy.isInitialized()) location.start()
        if (vehicleLazy.isInitialized() && vehicle.features.value.values.any { it == FeatureState.PERMISSION_DENIED }) {
            vehicle.stop()
            vehicle.start()
        }
    }

    /** 설정 "지금 위치를 집/회사로". 위치를 모르면 안내만 띄운다. */
    fun saveCurrentLocation(home: Boolean) {
        val here = location.location.value?.point
        if (here == null) {
            controller.showNotice("아직 현재 위치를 몰라요. 잠시 뒤에 다시 눌러 주세요.")
            return
        }
        val point = here.copy(name = null)
        settingsStore.update { if (home) it.copy(home = point) else it.copy(work = point) }
        controller.showNotice(if (home) "지금 위치를 집으로 저장했어요." else "지금 위치를 회사로 저장했어요.")
    }

    /** 컨테이너를 버릴 때(데모 모드 전환). 만든 어댑터만 놓는다. */
    fun release() {
        if (controllerLazy.isInitialized()) controller.stop()
        if (vehicleLazy.isInitialized()) vehicle.stop()
        if (speechInputLazy.isInitialized()) speechInput.release()
        if (speechOutputLazy.isInitialized()) speechOutput.release()
        if (naviLazy.isInitialized()) navi.release()
        if (locationLazy.isInitialized()) location.release()
        appScope.cancel()
    }
}

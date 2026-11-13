package ai.deulleulcar.core.trip

import ai.deulleulcar.core.cartask.CarTaskCatalog
import ai.deulleulcar.core.llm.HybridErrandParser
import ai.deulleulcar.core.llm.ParseOutcome
import ai.deulleulcar.core.model.CarTask
import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.Errand
import ai.deulleulcar.core.model.GeoPoint
import ai.deulleulcar.core.model.Place
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.Stop
import ai.deulleulcar.core.model.StopSource
import ai.deulleulcar.core.model.TelltaleType
import ai.deulleulcar.core.parser.DestinationHint
import ai.deulleulcar.core.parser.TimeHint
import ai.deulleulcar.core.place.Amenity
import ai.deulleulcar.core.place.CorridorSearch
import ai.deulleulcar.core.place.PlaceDataSource
import ai.deulleulcar.core.place.PlaceQuery
import ai.deulleulcar.core.place.PlaceSearch
import ai.deulleulcar.core.plan.PlanMerger
import ai.deulleulcar.core.plan.Suggestion
import ai.deulleulcar.core.plan.SuggestionEngine
import ai.deulleulcar.core.route.RouteOptimizer
import ai.deulleulcar.core.route.RoutePlan
import ai.deulleulcar.core.route.RouteRequest
import ai.deulleulcar.core.route.StopRequest
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime

/** 사용자가 정해 둔 집·회사. 이름은 내비 목적지 이름과 도착 안내("이제 집으로 갈게요")에 쓴다. */
data class KnownPlaces(val home: GeoPoint, val work: GeoPoint) {
    val homePoint: GeoPoint get() = home.copy(name = HOME_NAME)
    val workPoint: GeoPoint get() = work.copy(name = WORK_NAME)

    companion object {
        const val HOME_NAME = "집"
        const val WORK_NAME = "회사"
    }
}

/**
 * 계획 제안 하나(아직 확정 전, S2 화면).
 * @property plan 사람의 볼일(+ 이미 허락한 차의 할 일)만 들어간 계획
 * @property suggestions 경로에 겸사겸사 넣을 수 있는 차의 할 일 추천. **계획에 들어간 것이 아니다.**
 */
data class PlanProposal(
    val utterance: String,
    val parse: ParseOutcome,
    val plan: RoutePlan,
    val suggestions: List<Suggestion>,
    val placeSource: PlaceDataSource,
)

sealed interface PlanResult {
    data class Ready(val proposal: PlanProposal) : PlanResult

    /** 볼일을 하나도 찾지 못함. */
    data class NoErrands(val parse: ParseOutcome) : PlanResult

    /** 볼일은 알아들었지만 들를 수 있는 곳이 하나도 없음. [plan.excluded]에 이유가 있다. */
    data class NothingReachable(val parse: ParseOutcome, val plan: RoutePlan) : PlanResult
}

/**
 * 말한 볼일 → 장소 검색 → 순서 최적화 → 차의 할 일 추천까지 묶은 계획 담당 (시나리오 1~3).
 *
 * 개인정보 원칙: LLM에는 발화 텍스트만 간다([HybridErrandParser]). 위치는 장소 검색(카카오/데모)에만 쓴다.
 * 상태 없음. 계획 확정·내비 연동은 앱 컨트롤러가 한다.
 */
class TripPlanner(
    private val parser: HybridErrandParser,
    private val placeSearch: PlaceSearch,
    private val optimizer: RouteOptimizer = RouteOptimizer(),
    private val engine: SuggestionEngine = SuggestionEngine(optimizer),
    private val corridorRadiusM: Int = 1_500,
    private val candidatesPerErrand: Int = 3,
    private val fixSearchRadiusM: Int = 2_500,
) {
    private val corridor = CorridorSearch(placeSearch)

    /**
     * @param accepted 사용자가 미리 허락한 차의 할 일(ACCEPTED). 해결지를 찾아 계획에 바로 넣는다.
     * @param suggestible 추천 후보(OPEN·SNOOZED). 계획에는 넣지 않고 [PlanProposal.suggestions]로만 돌려준다.
     */
    suspend fun plan(
        utterance: String,
        origin: GeoPoint,
        places: KnownPlaces,
        at: LocalDateTime,
        accepted: List<CarTask> = emptyList(),
        suggestible: List<CarTask> = emptyList(),
    ): PlanResult {
        val parse = parser.parse(utterance)
        val request = parse.request
        if (request.errands.isEmpty()) return PlanResult.NoErrands(parse)
        val destination = resolveDestination(request.destination, request.timeHint, origin, places)

        var source = PlaceDataSource.KAKAO
        val stopRequests = request.errands.mapIndexed { i, errand ->
            val (stops, src) = searchErrand(errand, origin, destination)
            if (src == PlaceDataSource.DEMO) source = PlaceDataSource.DEMO
            StopRequest("errand-$i", stops)
        }
        var plan = optimizer.optimize(RouteRequest(origin, destination, stopRequests, at))
        if (plan.stops.isEmpty()) return PlanResult.NothingReachable(parse, plan)

        for (task in accepted) {
            val best = fixOptions(task, plan, origin, at, limit = 1).firstOrNull() ?: continue
            plan = merge(plan, best, origin, at) ?: plan
        }
        val suggestions = suggestions(plan, suggestible, origin, at)
        return PlanResult.Ready(PlanProposal(utterance, parse, plan, suggestions, source))
    }

    /** 이미 있는 계획에 볼일을 더한다(주행 중 "하나 더 들러"). 기존 정류지는 장소를 고정하고 순서만 다시 짠다. */
    suspend fun addErrands(
        utterance: String,
        current: RoutePlan,
        from: GeoPoint,
        at: LocalDateTime,
    ): Pair<ParseOutcome, RoutePlan?> {
        val parse = parser.parse(utterance)
        val errands = parse.request.errands
        if (errands.isEmpty()) return parse to null
        val base = current.asRequests()
        val added = errands.mapIndexed { i, errand ->
            StopRequest("more-${base.size + i}-${errand.keyword}", searchErrand(errand, from, current.destination).first)
        }
        val plan = optimizer.optimize(RouteRequest(from, current.destination, base + added, at, current.pinnedFirstRequestId))
        val gained = plan.stops.any { s -> added.any { it.id == s.requestId } }
        return parse to plan.takeIf { gained }
    }

    /** 남은 정류지만으로 [from]에서 다시 짠 계획(주행 중 추천·병합의 기준). */
    fun remainingPlan(remaining: List<Stop>, from: GeoPoint, destination: GeoPoint, at: LocalDateTime): RoutePlan =
        optimizer.optimize(RouteRequest(from, destination, remaining.map { StopRequest.of(it) }, at))

    /** 경로 근처에서 [tasks]를 해결할 수 있는 추천(강도 순). 계획은 바꾸지 않는다. */
    suspend fun suggestions(plan: RoutePlan, tasks: List<CarTask>, from: GeoPoint, at: LocalDateTime): List<Suggestion> {
        if (tasks.isEmpty()) return emptyList()
        val fix = tasks.associate { it.id to fixPlaces(it, from, plan.destination, corridorRadiusM) }
        return engine.suggest(plan, tasks, from, at) { fix[it.id].orEmpty() }
    }

    /**
     * [task]를 해결할 수 있는 곳들을 우회가 작은 순으로(S4 긴급 알림의 "가장 가까운 곳", S5 해결 장소 목록).
     * 우회 기준은 적용하지 않는다(사용자가 직접 고르는 목록).
     */
    suspend fun fixOptions(task: CarTask, plan: RoutePlan, from: GeoPoint, at: LocalDateTime, limit: Int = 3): List<Suggestion> {
        val places = fixPlaces(task, from, plan.destination, fixSearchRadiusM)
        return places
            .mapNotNull { engine.evaluate(plan, task, it, from, at) }
            .sortedWith(compareBy<Suggestion> { it.displaced.size }.thenBy { it.detourMinutes })
            .take(limit)
    }

    /**
     * 사용자가 수락한 추천을 계획에 합친다([PlanMerger.accept]). 할 일 상태 갱신은 호출자가 한다.
     * @return 합친 새 계획. 해결지에 들를 수 없게 됐으면 null.
     */
    fun merge(plan: RoutePlan, suggestion: Suggestion, from: GeoPoint = plan.origin, at: LocalDateTime = plan.departAt): RoutePlan? =
        PlanMerger(optimizer).accept(plan, suggestion, from, at)

    /** 계획이 바뀐 뒤 같은 해결 장소의 우회 시간을 다시 잰다. 들를 수 없게 됐으면 null. */
    fun reevaluate(plan: RoutePlan, s: Suggestion, from: GeoPoint = plan.origin, at: LocalDateTime = plan.departAt): Suggestion? =
        engine.evaluate(plan, s.task, s.place, from, at)

    /** 정류지 없이 [from] → [destination] 직행 계획. */
    fun directPlan(from: GeoPoint, destination: GeoPoint, at: LocalDateTime): RoutePlan =
        optimizer.optimize(RouteRequest(from, destination, emptyList(), at))

    fun resolveDestinationHint(hint: DestinationHint?, timeHint: TimeHint?, places: KnownPlaces): GeoPoint? = when (hint) {
        DestinationHint.Home -> places.homePoint
        DestinationHint.Work -> places.workPoint
        is DestinationHint.Named -> null
        null -> if (timeHint == TimeHint.COMMUTE_WORK) places.workPoint else places.homePoint
    }

    private suspend fun resolveDestination(
        hint: DestinationHint?,
        timeHint: TimeHint?,
        origin: GeoPoint,
        places: KnownPlaces,
    ): GeoPoint {
        resolveDestinationHint(hint, timeHint, places)?.let { return it }
        val name = (hint as DestinationHint.Named).name
        val found = safeSearch(PlaceQuery(PlaceCategory.OTHER, origin, keyword = name, radiusM = 20_000, limit = 1))
        return found.firstOrNull()?.point ?: places.homePoint
    }

    private suspend fun searchErrand(errand: Errand, from: GeoPoint, to: GeoPoint): Pair<List<Stop>, PlaceDataSource> {
        val keyword = errand.placeName ?: errand.keyword.takeIf { errand.category == PlaceCategory.OTHER }
        val template = PlaceQuery(errand.category, from, keyword = keyword, radiusM = corridorRadiusM, limit = 5)
        return try {
            val result = corridor.search(template, from, to)
            result.hits.take(candidatesPerErrand).map { Stop(it.place, StopSource.HUMAN, errand = errand) } to result.source
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList<Stop>() to PlaceDataSource.DEMO
        }
    }

    private suspend fun fixPlaces(task: CarTask, from: GeoPoint, to: GeoPoint, radiusM: Int): List<Place> {
        val info = CarTaskCatalog.info(task.kind)
        return info.fixPlaces.flatMap { category ->
            val template = PlaceQuery(category, from, radiusM = radiusM, requiredAmenities = amenitiesFor(task.kind, category), limit = 5)
            try {
                corridor.search(template, from, to).hits.take(3).map { it.place }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }.distinctBy { it.id }
    }

    private suspend fun safeSearch(query: PlaceQuery): List<Place> = try {
        placeSearch.search(query).places
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** 타이어 공기압은 주유소 중에서도 공기주입기가 있는 곳만(속성을 아는 데모 데이터에서만 걸러진다). */
        fun amenitiesFor(kind: CarTaskKind, category: PlaceCategory): Set<Amenity> =
            if (kind == CarTaskKind.Warning(TelltaleType.TPMS) && category == PlaceCategory.GAS_STATION) {
                setOf(Amenity.AIR_PUMP)
            } else {
                emptySet()
            }
    }
}

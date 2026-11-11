package ai.deulleulcar.core.cartask

import ai.deulleulcar.core.model.CarTaskKind
import ai.deulleulcar.core.model.PlaceCategory
import ai.deulleulcar.core.model.TelltaleType

/**
 * 차의 할 일 종류별 설명 자료 (docs/telltale-severity.md).
 * @property meaning 쉬운 말 의미 — 3단 문구의 "무엇". "~요"로 끝나는 한 문장.
 * @property action 사용자가 할 행동 — "언제까지 ~하는 게 좋아요"의 "~" 부분 (예: "공기압을 채우는").
 * @property fixPlaces 해결 장소 업종. 앞쪽이 우선.
 * @property stopFirst 긴급일 때 정비소보다 "안전한 곳에 세우기"가 먼저인 경고.
 */
data class CarTaskInfo(
    val meaning: String,
    val action: String,
    val fixPlaces: List<PlaceCategory>,
    val stopFirst: Boolean = false,
)

object CarTaskCatalog {
    private val repair = listOf(PlaceCategory.REPAIR_SHOP)

    fun info(kind: CarTaskKind): CarTaskInfo = when (kind) {
        CarTaskKind.LowRange -> CarTaskInfo(
            meaning = "연료·배터리가 부족해요",
            action = "주유나 충전을 하는",
            fixPlaces = listOf(PlaceCategory.GAS_STATION, PlaceCategory.EV_CHARGING),
        )
        CarTaskKind.PeriodicInspection -> CarTaskInfo(
            meaning = "정기 점검 시기예요",
            action = "정비소에서 점검을 받는",
            fixPlaces = repair,
        )
        is CarTaskKind.Warning -> telltale(kind.telltale)
    }

    private fun telltale(type: TelltaleType): CarTaskInfo = when (type) {
        TelltaleType.ENGINE_OIL_PRESSURE ->
            CarTaskInfo("엔진오일 압력이 낮아요", "안전한 곳에 세우고 점검을 받는", repair, stopFirst = true)
        TelltaleType.ENGINE_TEMPERATURE ->
            CarTaskInfo("엔진이 과열됐어요", "안전한 곳에 세워 식힌 뒤 점검을 받는", repair, stopFirst = true)
        TelltaleType.EV_SYSTEM ->
            CarTaskInfo("전기차 구동 시스템에 이상이 있어요", "가까운 정비소에서 점검을 받는", repair)
        TelltaleType.POWER_DOWN ->
            CarTaskInfo("출력이 제한됐어요", "무리하게 달리지 말고 점검을 받는", repair)
        TelltaleType.MDPS ->
            CarTaskInfo("핸들 보조 장치에 이상이 있어 핸들이 무거워질 수 있어요", "천천히 달려서 점검을 받는", repair)
        TelltaleType.H2_LEAK ->
            CarTaskInfo("수소 누출이 감지됐어요", "안전한 곳에 세우고 긴급 연락을 하는", repair, stopFirst = true)
        TelltaleType.TPMS ->
            CarTaskInfo(
                "타이어 공기압이 낮아요", "공기압을 채우는",
                listOf(PlaceCategory.GAS_STATION, PlaceCategory.TIRE_SHOP),
            )
        TelltaleType.ENGINE -> CarTaskInfo("엔진 점검이 필요해요", "정비소에서 점검을 받는", repair)
        TelltaleType.ABS -> CarTaskInfo("미끄럼 방지 제동 장치에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.ESC -> CarTaskInfo("차체 자세 제어 장치에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.AIRBAG -> CarTaskInfo("에어백 점검이 필요해요", "정비소에서 점검을 받는", repair)
        TelltaleType.LOW_VOLT_BATTERY ->
            CarTaskInfo("12V 배터리 충전 상태가 좋지 않아요", "배터리 점검을 받는", repair)
        TelltaleType.ELECTRONIC_PARKING_BRAKE ->
            CarTaskInfo("전자식 주차 브레이크에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.RBS -> CarTaskInfo("회생 제동 장치에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.PARTICLE_FILTER ->
            CarTaskInfo("매연 저감 필터 재생이 필요해요", "한동안 고속으로 달리거나 정비소에 들르는", repair)
        TelltaleType.H2_SENSOR -> CarTaskInfo("수소 센서 점검이 필요해요", "정비소에서 점검을 받는", repair)
        TelltaleType.MASTER_WARNING ->
            CarTaskInfo("확인이 필요한 경고가 있어요", "차량 화면을 확인하고 정비소에 들르는", repair)
        TelltaleType.AFS -> CarTaskInfo("적응형 전조등에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.LED_HEADLAMP -> CarTaskInfo("LED 전조등에 이상이 있어요", "정비소에서 점검을 받는", repair)
        TelltaleType.EMERGENCY_CALL ->
            CarTaskInfo("긴급 호출 장치 점검이 필요해요", "정비소에서 점검을 받는", repair)
        TelltaleType.STATUS_INDICATOR, TelltaleType.UNKNOWN ->
            error("할 일이 아닌 경고등: $type")
    }
}

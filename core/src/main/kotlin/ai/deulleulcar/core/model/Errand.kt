package ai.deulleulcar.core.model

/**
 * 사람의 할 일(말한 볼일) 하나.
 * @property keyword 발화에서 찾은 원문 표현 (예: "약국", "슈퍼", "햇살약국")
 * @property category 장소 검색에 쓸 업종
 * @property placeName 사용자가 특정 상호를 말한 경우 그 이름. 없으면 업종으로 검색한다.
 */
data class Errand(
    val keyword: String,
    val category: PlaceCategory,
    val placeName: String? = null,
)

/** 정류지가 어디서 왔는지. 차의 할 일은 사용자가 허락한 뒤에만 [CAR]로 계획에 들어온다. */
enum class StopSource { HUMAN, CAR }

/**
 * 계획에 들어간 정류지.
 * @property linkedTaskId [StopSource.CAR]이면 연결된 [CarTask.id]. 사람의 볼일이면 null.
 * @property errand [StopSource.HUMAN]이면 원래 볼일.
 */
data class Stop(
    val place: Place,
    val source: StopSource,
    val linkedTaskId: String? = null,
    val errand: Errand? = null,
) {
    init {
        require(source != StopSource.CAR || linkedTaskId != null) { "차의 할 일 정류지는 task id가 필요합니다" }
    }
}

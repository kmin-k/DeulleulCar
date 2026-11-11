package ai.deulleulcar.app.sdk

/** SDK 기능 하나의 사용 가능 상태. 진단 로그·설정 화면(T14)에서 "왜 안 되는지" 보여 줄 때 쓴다. */
enum class FeatureState {
    /** 아직 확인 전. */
    UNKNOWN,

    /** 구독(또는 초기화) 성공. */
    ACTIVE,

    /** 이 차량(에뮬레이터)이 지원하지 않음. */
    NOT_SUPPORTED,

    /** 권한이 없음(매니페스트 누락 또는 런타임 미승인). */
    PERMISSION_DENIED,

    /** 그 밖의 오류(서비스 연결 실패 등). */
    FAILED,
}

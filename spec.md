# 들를Car (DeulleulCar) Spec

> 가는 길에, 한 번에. 내가 말한 볼일은 최적 경로로 바로 짜 주고, 차가 감지한 할 일은 놓치지 않게 계속 보여 준다.

## 목표
- 현대차그룹 차량용 앱 개발 공모전 **대학생 부문 수상**
- 1차 서류: 기획서(접수양식) + GitHub(소스·release APK) + 가능하면 시연 영상. 숏폼은 하지 않음
- 2차 PT(12/3~4)에서 그대로 확장해 시연할 수 있는 구조로 MVP 구현

## 핵심 컨셉
| 구분 | 사람의 할 일 (말한 것) | 차의 할 일 (감지한 것) |
|---|---|---|
| 입력 | 음성: "퇴근길에 약국이랑 마트 들러" | Vehicle SDK: 경고등, 타이어 저압, 연료, 엔진오일, 워셔액, 브레이크액 |
| 처리 | 바로 최적 순서로 편성 (영업시간, 우회 시간 고려) | 위험도·우선도로 분류. **사용자가 허락하기 전에는 동선에 넣지 않음** |
| 결과 | NaviHelper 경유지 세팅 | 강하게 추천하거나, 할 일 목록에 남겨 계속 알게 함 |

### 차의 할 일 등급 (초안)
| 등급 | 예시 | 앱 동작 |
|---|---|---|
| 긴급 | 브레이크 관련 경고등, 주행 가능 거리 30km 미만 | 바로 TTS로 알림: 무슨 뜻인지, 계속 달려도 되는지, 가장 가까운 해결 장소. 수락하면 경유지 추가 |
| 권장 | 타이어 저압(TPMS 경고등), 주행 가능 거리 80km 미만, 엔진오일 경고등 | 경로를 짤 때 "이번 경로에 겸사겸사 넣을까요? +3분"으로 강하게 추천 |
| 보류 | 워셔액 부족, 정기 점검 | 할 일 목록에 남김. 홈 카드에 계속 표시하고, 시동·정차 때 요약. 경로 근처에 해결 기회가 있으면 알림 |

## 범위
- 포함:
  - 음성 볼일 입력 (STT) → LLM이 구조화 → 장소 검색 → 순서 최적화 → 음성 확인 (TTS) → NaviHelper 경유지 세팅
  - 경유지는 한 번에 최대 2개만 넣을 수 있음 → 앱이 전체 계획을 들고 있다가 **경유지 도착 이벤트마다 다음 정류지를 넣는 방식**(이하 "롤링 경유지")
  - 차량 경고 감지 → 등급 분류 → 쉬운 말로 설명 (LLM + 규칙 템플릿) → 할 일 목록 유지 → 경로 근처 기회 알림 → 허락하면 계획에 합침
  - 운전 중 화면 최소화: 큰 카드 1~2개, 조작은 음성 위주
  - API 키가 없거나 오프라인이어도 데모가 돌아가는 규칙 기반 fallback
  - 기획서 (접수양식 02 섹션 1~7), README, release APK, 시연 영상
- 제외:
  - 결제, 픽업 주문, 매장 예약 연동 (기획서에 확장 계획으로만 기재)
  - 실차 검증 (에뮬레이터에서만 검증)
  - 자체 지도 렌더링 (안내는 Pleos Navigation이 담당)
  - 숏폼

## 결정사항
| 항목 | 결정 | 이유 |
|---|---|---|
| 앱 이름 / 팀명 | 들를Car (DeulleulCar) / DeulleulCarStudio. APK는 `DeulleulCarStudio_DeulleulCar_release.apk` | "들를까?"라는 앱의 핵심 질문을 그대로 이름으로 씀 + car 언어유희 |
| 아이디어 | 심부름 라우터 + 경고등 통역사를 하나로 합친 앱 | 둘 다 결과가 "경로 위의 할 일"이라 하나로 묶임. 차량특화성과 일상 효용을 동시에 확보 |
| 차가 감지한 할 일 | 허락 전에는 동선에 넣지 않고, 등급별로 추천하거나 목록에 남김 | 사용자 결정. 경고를 바로 해결하지 않는 현실적인 운전 습관을 반영 |
| 경유지 2개 제한 | 롤링 경유지로 해결 (`onWayPointArrived`가 오면 다음 정류지 투입) | NaviHelper는 경유지를 최대 2개(FIRST/SECOND)까지만 받음 |
| 기획서 형식 | 공식 접수양식 DOCX의 02 섹션을 채움. 화면 시안은 별지로 첨부 | 제출 규정 |
| 익명성 | 레포, 커밋 작성자, README, 첨부자료에 실명·개인정보를 넣지 않음 | 접수양식 안내: "개발자 실명 노출 금지" |
| 부문 | 대학생 | 사용자 정보 |
| LLM | Gemini API 무료 티어(`gemini-3.5-flash-lite`). `LlmClient` 인터페이스로 감싸서 Claude(Haiku 5.5)로도 바꿀 수 있게 함. 키는 APK에 넣지 않고 설정 화면에서 입력, 키가 없으면 규칙 기반으로 동작 | Claude API는 무료 티어가 없음. Gemini 무료 티어는 입력 데이터가 Google 제품 개선에 쓰이므로 데모 데이터만 보내고 개인정보는 보내지 않음 |
| 장소·영업시간 | 장소 검색은 카카오 로컬. 영업시간은 업종별 기본값 + 데모 구역에서 직접 모은 큐레이션 JSON. `HoursProvider` 인터페이스로 분리 | 카카오 로컬에는 영업시간이 없음. Google Places 연동은 기획서에 확장 계획으로만 기재 |
| 이름 표기 | 익명 팀명을 APK, 레포, 커밋 작성자에 사용. 실명은 접수양식 01에만 기재 | 실명 노출 금지 조항 |
| 차량 신호 소스 | 타이어 = Safety telltales의 TPMS(+Tire API 지원 시 보조), 연료/배터리 = Energy 주행 가능 거리(+fuel percent·SOC 지원 시 보조), 주행/정차 = 기어(P=정차, 그 외=주행), 기타 경고 = telltales에 있는 것만 | Virtual Console에서 재현 가능한 신호 기준 (2026-11-11 문서 확인). 각 도메인은 `checkXXXCapabilities`로 지원 여부 확인 후 사용 |

## 기술 스택 / 제약
- Android (Kotlin), minSdk 28 이상, Gradle 8 이상, Pleos Connect Emulator (AAOS 기반)
- Pleos Connect SDK
  - Maven: `https://nexus-playground.pleos.ai/repository/maven-releases/` — `ai.pleos.playground:Vehicle:2.3.2`, `NaviHelper:3.0.5`, `SpeechToText:3.3.1`, `TextToSpeech:3.3.1`, `FusedLocation:1.1.3`
  - Vehicle: Safety(telltales), Energy/Odometer(주행 가능 거리), 기어(주행 상태), Tire·Fuel(지원 시 보조) — 권한 `pleos.car.permission.CAR_SAFETY`, `CAR_ENERGY`, `CAR_DRIVE`, `CAR_INFO`
  - 권한: `NAVI_ROUTE`, `NAVI_ROUTE_SEARCH`, `FUSED_LOCATION`, `STT_SERVICE`, `TTS_SERVICE` (접두사 `pleos.car.permission.`)
  - Virtual Console: `adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/ (Gear, Telltales, Remaining Range 등)
  - NaviHelper: `requestRouteWithWaypoints`, `addWaypoint`, `onWayPointArrived`, `onRouteStateInfo`, `onTBTInfo`, `onDestinationArrived` (도착 후 슬롯 동작은 T13에서 확인)
  - FusedLocation: 현재 위치, `addProximityAlert`
  - STT: ON_DEVICE 모드 (앱 등록 불필요) / TTS
- UI: Jetpack Compose (차량 화면 기준 큰 터치 영역, 다크 테마)
- 외부 API (공공 + 무료 할당량 범위): 장소 검색(카카오 로컬), 유가(오피넷). 영업시간 데이터 출처는 미결
- 제약
  - Vehicle 권한은 Pleos App Market 승인 범위 안에서만 사용
  - 에뮬레이터 Virtual Console로 차량 값을 시뮬레이션
  - 운전 중 주의분산 최소화

## 완료 기준 (검증 가능하게)
- [ ] release APK가 Pleos Connect Emulator에서 설치·실행되고 crash 없이 3개 시나리오를 완주
- [ ] 시나리오 1: 음성 "퇴근길에 약국이랑 마트 들러" → 정류지 2개 이상이 최적 순서로 나오고 "+N분" 안내 → 확인하면 NaviHelper 경로 요청이 성공
- [ ] 시나리오 2: 정류지가 3개 이상이면 롤링 경유지가 동작 (도착 이벤트 후 다음 정류지가 들어감)
- [ ] 시나리오 3: Virtual Console에서 TPMS 경고등을 켬 → 권장 할 일로 등록 → **경로에 자동으로 추가되지 않음** → 다음 경로를 짤 때 추천 → 수락하면 계획에 합쳐짐
- [ ] 긴급 경고(주행 가능 거리 30km 미만 등)는 즉시 TTS 안내 + 해결 장소 제안. 보류 항목은 홈 카드에 계속 남음
- [ ] API 키 없이도 규칙 기반 fallback으로 시나리오 1~3이 동작
- [ ] 단위 테스트 통과: 순서 최적화(영업시간 반영), 등급 분류기, 볼일 파서 fallback, 롤링 경유지 상태 머신
- [ ] GitHub: `apk/{이름}_{앱이름}_release.apk`, README에 4개 필수 항목(앱 이름·한 줄 소개 / 실행 방법 / 테스트 환경·설정 / APK 위치), 실명 0건
- [ ] 기획서: 접수양식 02의 1~7항을 모두 작성. 심사 4개 항목(차량특화성·창의성·기술활용성·사업성)이 각각 명시적으로 드러남. 수치에는 출처 표기
- [ ] 시연 영상 60~120초: 시나리오 1·3이 들어가고 화면 녹화 포함

## 미결 사항
1. ~~커밋 작성자 이메일~~ → 해결: GitHub noreply 주소 사용, 원격 `kmin-k/DeulleulCar` 연결
2. **Telltales API 세부 항목**: 지원하는 경고등 종류를 T12에서 확인한 뒤 등급표 확정
3. **Gemini 무료 티어 호출 한도**: 문서에 수치가 없음. T7에서 AI Studio로 확인하고, 부족하면 Haiku 5.5로 전환할지 다시 질문

## 작업 계획
> 표시: 👤 = 사용자가 직접 하는 작업, 🤖 = Claude가 하는 작업. 개발 작업은 작업 1개 = 커밋 1개.
> 의존성: Phase 1·2는 에뮬레이터 없이 진행할 수 있음 (Phase 2는 JDK만 필요). Phase 3부터는 T0 완료가 필요.

### Phase 0. 환경 (👤)
- [x] **T0** Android Studio 설치 → Pleos Connect Emulator 설치, AVD 생성, Virtual Console 설정 ([공식 가이드](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)) — 검증: `adb devices`에 에뮬레이터가 보이고, Vehicle Sample App이 구동됨
- [x] **T0b** 키 발급: Gemini(AI Studio), 카카오 REST, 오피넷(선택) → `local.properties`에만 저장 — 검증: 키가 git에 추적되지 않음

### Phase 1. 기획 근거 (🤖)
- [x] **T1** 리서치: 운전자 수·차량 등록 대수, 운전 중 볼일·경유 행태, 경고등 방치·타이어 공기압 관련 통계, 경쟁 서비스 비교 → `docs/research.md` — 검증: 모든 수치에 출처 URL
- [x] **T2** UX 설계: 시나리오 3개의 화면 흐름, 음성 대화 스크립트, 주행·정차 상태별 UI 규칙, 화면 시안(HTML 목업 → PNG) → `docs/ux/` — 검증: 시나리오 1~3을 모든 화면이 커버함

### Phase 2. 코어 로직 (🤖, 순수 Kotlin + JVM 테스트)
- [x] **T3** 프로젝트 스캐폴드: Gradle KTS, `:core`(순수 Kotlin) / `:app`(Compose), Pleos SDK 의존성, `.gitignore`, 커밋 작성자 익명 설정 — 검증: `gradlew :core:test` 및 `assembleDebug` 성공
- [x] **T4** 도메인 모델 + 차량 할 일 등급 분류기 (긴급 / 권장 / 보류) — 검증: 경고 유형별 등급 단위 테스트
- [x] **T5** 볼일 파서 규칙 기반 fallback ("A랑 B 들러", "~가는 길에" 등) — 검증: 대표 발화 20개 테스트
- [x] **T6** `LlmClient` + Gemini 구현 (JSON 스키마 응답), 실패하면 fallback으로 전환 — 검증: fake 서버 테스트 + 실제 호출 1회 확인
- [x] **T7** 장소 검색(카카오 로컬) + `HoursProvider`(업종 기본값 + 큐레이션 JSON) — 검증: 응답 파싱 테스트, 영업 중 판정 테스트
- [x] **T8** 경로 순서 최적화: 정류지 5개 이하 완전 탐색, 이동시간 추정, 영업 종료 제약, "+N분" 계산 — 검증: 영업 종료가 임박한 곳이 앞으로 오는 케이스 등 테스트
- [x] **T9** 롤링 경유지 상태 머신 (최대 2개씩 투입, 도착 이벤트가 오면 다음 정류지) — 검증: 정류지 3~5개 시퀀스 테스트
- [x] **T10** 차량 할 일과 계획 병합: 경로 근처 해결 기회 탐지, 추천 문구와 +N분, **허락 전에는 계획에 반영하지 않음** — 검증: 거절이면 미반영, 수락이면 순서 재계산되는 테스트

### Phase 3. SDK 연동 (🤖, T0 필요)
- [x] **T11** Vehicle 어댑터: telltales, 타이어, 연료, 엔진오일, 워셔액, 브레이크액 → 차량 할 일 이벤트 — 검증: Virtual Console에서 값을 바꾸면 앱 상태가 바뀜
- [ ] **T12** STT(ON_DEVICE)·TTS 어댑터 — 검증: 에뮬레이터 마이크 발화가 텍스트로 변환되고 음성 응답이 출력됨
- [ ] **T13** NaviHelper + FusedLocation 어댑터 (`requestRouteWithWaypoints`, `onWayPointArrived`, 근접 알림) — 검증: 경로 요청 성공 이벤트, 도착 이벤트 처리

> 기기 검증 현황 (2026-11-12)
> - T11 ✅ Virtual Console 값 변경(TPMS ON, 주행 가능 거리 25km, 기어 P↔D)이 앱 신호로 들어옴. 브레이크액·엔진오일·워셔액 레벨은 에뮬레이터 미지원(NOT_SUPPORTED) → 앱은 해당 기능만 끄고 정상 동작.
> - T12 ◐ TTS 재생·긴급 끼어들기·대기열 진행 확인(에뮬레이터는 완료 콜백이 최대 16초 늦어 감시 타이머 추가). STT 실제 발화는 마지막 기기 검증 때 확인.
> - T13 ◐ FusedLocation 수신, 경로 요청이 내비 앱까지 전달되는 것 확인. 에뮬레이터 내비 앱이 화면을 그리지 못해 응답·슬롯 동작은 미측정 → `SlotSyncStrategy.ReRequestRoute`(안전한 기본값) 유지.
> - 에뮬레이터가 노트북에 무거워 기기 검증은 마지막에 한 번에 몰아서 한다.

### Phase 4. UI (🤖)
- [ ] **T14** Compose UI: 홈(사람의 할 일 / 차의 할 일 카드), 계획 확인, 주행 중 미니 카드, 긴급 알림, 설정(API 키 입력) — 검증: 시나리오 1~3을 수동으로 완주
- [ ] **T15** 데모 모드: 키 없이 시나리오를 재현할 수 있는 시드 데이터 + 위치 시뮬레이션 — 검증: 키를 지우고도 시나리오 1~3 완주

### Phase 5. 제출물
- [ ] **T16** 🤖 release 서명 빌드 → `apk/{팀명}_{앱이름}_release.apk`, README 작성 — 검증: 클린 설치 후 실행, README 4개 항목, 실명 grep 0건
- [ ] **T17** 🤖 시연 영상 스크립트(60~120초) → 👤 녹화 (`adb screenrecord` 명령 제공) — 검증: 시나리오 1·3 포함
- [ ] **T18** 🤖 기획서: 접수양식 DOCX 02의 1~7항 작성 + 별지(화면 시안, 아키텍처도) — 검증: 7개 항목 체크리스트, 심사 4개 항목 매핑, 02에 개인정보 미기재
- [ ] **T19** 👤 접수양식 01·03·04(인적사항, 서명) 작성 후 이메일 제출, GitHub push

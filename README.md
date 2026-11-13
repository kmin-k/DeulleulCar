# 들를Car (DeulleulCar)

> 말 한마디로 오늘 들를 곳을 경로 위에 놓고, 차가 알려 주는 할 일까지 같은 경로에서 처리해 주는 Pleos Connect 앱.

팀 DeulleulCarStudio · 2026 HMG 차량용 앱 공모전(대학생 부문)

## 1. 앱 소개

들를Car는 운전자의 할 일과 차의 할 일을 한 경로 위에서 함께 다룹니다.

- **사람의 할 일**: "퇴근길에 약국이랑 마트 들렀다 집에 가자"처럼 말하면 볼일을 알아듣고, 영업시간과 우회 시간을 따져 들를 순서를 정한 뒤 "+N분"으로 안내합니다. 확인하면 Pleos 내비에 경유지를 넣습니다.
- **롤링 경유지**: 내비는 경유지를 2곳까지만 받으므로, 앱이 전체 계획을 들고 있다가 경유지에 도착할 때마다 다음 정류지를 넣습니다.
- **차의 할 일**: 차량 경고(타이어 공기압, 주행 가능 거리 등)를 감지해 쉬운 말로 설명하고 등급을 매깁니다.
  - **긴급**: 바로 음성으로 알리고 해결할 곳을 제안 (예: 주행 가능 거리 30km 미만)
  - **권장**: 할 일 목록에 넣고, 다음에 경로를 짤 때 경로 근처의 해결 장소를 추천
  - **보류**: 목록에만 남겨 둠
- **허락 전에는 경로를 바꾸지 않습니다.** 차의 할 일은 운전자가 "응"이라고 해야 계획에 합쳐집니다.
- 주행 중에는 큰 카드 1~2개만 보여 주고 조작은 음성 위주로 합니다.

기획 문서: [docs/proposal.md](docs/proposal.md) · UX 흐름과 화면 시안: [docs/ux/](docs/ux/) ([ux-flow.md](docs/ux/ux-flow.md), [mockups](docs/ux/mockups/))

## 2. APK 위치

[`apk/DeulleulCarStudio_DeulleulCar_release.apk`](apk/DeulleulCarStudio_DeulleulCar_release.apk) (release 서명, versionName 1.0.0)

## 3. 테스트 환경·설정

| 항목 | 내용 |
|---|---|
| 기기 | Pleos Connect Emulator (AAOS 기반, AVD `Pleos_Connect_v2.1.0`) |
| Android | minSdk 28, targetSdk/compileSdk 37 |
| 빌드 도구 | Android Studio 내장 JDK(jbr, Java 17), Gradle Wrapper, AGP 9.5.1, Kotlin 2.4.21 |
| 차량 신호 | 에뮬레이터 Virtual Console (`adb forward tcp:8088 tcp:8088` → http://127.0.0.1:8088/) 또는 앱 데모 모드 |
| API 키 | 필요 없음(선택). 키가 없으면 데모 모드로 시작 |

## 4. 실행 방법

### 설치

```bash
adb install -r apk/DeulleulCarStudio_DeulleulCar_release.apk
```

앱 목록에서 **들를Car**를 실행합니다. 처음 실행하면 마이크와 Pleos 차량·내비·위치·음성 권한을 요청하니 모두 허용해 주세요.

### 데모 모드 (키 없이 바로 시연)

- API 키가 없으면 **데모 모드가 자동으로 켜진 상태로** 시작합니다. 차량·내비·위치를 가상으로 움직이고, 장소는 데모 구역의 가상 장소를, 말 해석은 규칙 기반을 씁니다.
- 화면 위쪽의 **[데모]** 버튼 → "데모 조작" 패널에서 시나리오 버튼을 한 번 누르면 처음부터 자동으로 진행됩니다.
  - **① 퇴근길 볼일**: "퇴근길에 약국이랑 마트 들렀다 집에 가자" → 영업시간을 반영한 순서와 "+N분" 안내 → 내비 경유지 설정 → 가상 주행
  - **② 정류지 3곳**: "세탁소, 약국, 마트 들렀다 집" → 내비에는 앞의 2곳만 넣고 도착할 때마다 다음 정류지를 투입(롤링 경유지)
  - **③ 타이어 저압**: 주행 중 TPMS 경고 → 말하지 않고 배지만 표시, 경로에 자동으로 넣지 않음 → 정차하면 한 번 묻기 → 다음 경로를 짤 때 근처 주유소 추천 → 수락하면 계획에 합쳐짐
  - **④ 연료 부족**: 주행 가능 거리 25km → 긴급 음성 안내와 가장 가까운 주유소 제안 → 허락하면 들르기
- 진행 중에는 위쪽 데모 바에 해설이 나오고 **[배속] [다음 ▶] [끝내기]** 로 조절합니다. 데모 패널의 "직접 조작"으로 기어, 경고등, 주행 가능 거리 등을 손으로 바꿀 수도 있습니다.
- 에뮬레이터 TTS는 소리 대신 화면 아래 자막으로도 보여 줍니다.

### 실제 SDK로 실행

**[설정]** 에서 "데모 모드"를 끄면 Pleos SDK(차량·내비·위치·STT·TTS)에 연결합니다. Virtual Console에서 Telltales의 TPMS, Remaining Range, Gear 등을 바꾸면 앱 상태가 바뀝니다. 설정에 Gemini API 키와 카카오 REST 키를 넣으면 AI 해석과 실제 장소 검색을 씁니다(둘 다 선택).

## 5. 사용 SDK·기술 스택

| 구분 | 내용 |
|---|---|
| Pleos Connect SDK | Vehicle 2.3.2 (Safety telltales, Energy 주행 가능 거리, 기어), NaviHelper 3.0.5 (경유지 경로 요청, 경유지 도착 이벤트), SpeechToText 3.3.1 (ON_DEVICE), TextToSpeech 3.3.1, FusedLocation 1.1.3 (현재 위치, 근접 알림) |
| AI | Gemini API (`gemini-3.5-flash-lite`, JSON 스키마 응답). `LlmClient` 인터페이스로 분리, 실패하거나 키가 없으면 규칙 기반 파서로 전환 |
| 장소 | 카카오 로컬 REST API(장소 검색), 영업시간은 업종 기본값 + 큐레이션 데이터 |
| 앱 | Kotlin, Jetpack Compose(다크 테마, 큰 터치 영역), Coroutines, OkHttp, kotlinx.serialization |
| 테스트 | JUnit 5, kotlinx-coroutines-test, OkHttp MockWebServer |

## 6. 프로젝트 구조

```
core/   순수 Kotlin 도메인 로직 (Android 의존 없음, JVM 테스트)
        볼일 파서, LLM 클라이언트, 장소·영업시간, 순서 최적화, 롤링 경유지 상태 머신,
        차량 할 일 등급 분류기, 계획 병합, 데모 시나리오 대본·주행 시뮬레이션
app/    Android 앱 (Compose UI, Pleos SDK 어댑터, 데모 모드용 Fake 구현)
docs/   기획서 초안, 리서치, UX 흐름·화면 시안
apk/    제출용 release APK
```

### 빌드·테스트

```bash
./gradlew :core:test :app:testDebugUnitTest   # 단위 테스트
./gradlew :app:assembleRelease                 # release APK
```

release 서명은 저장소에 없는 `keystore.properties`(+ keystore 파일)를 읽습니다. 파일이 없으면 서명하지 않은 release APK를 만들고 경고만 출력합니다.

## 7. 개인정보·키 정책

- APK와 저장소에는 **API 키가 들어 있지 않습니다.** 키는 설정 화면에서 입력하며 기기의 앱 저장소에만 저장됩니다. 개발용 키는 git에서 제외한 `local.properties`에만 둡니다.
- Gemini에는 사용자가 말한 문장만 보내고 위치·개인정보는 보내지 않습니다. 시연에는 데모 데이터만 사용합니다.
- 서명 키와 비밀번호는 저장소에 올리지 않습니다(`.gitignore`).
- 저장소·커밋·문서에는 팀명만 쓰고 개인 실명은 넣지 않습니다.

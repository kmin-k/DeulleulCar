# 경고등(Telltales) 등급표

> 출처: Pleos Connect Virtual Console의 Safety → Telltales 목록 (2026-11-11 에뮬레이터 v2.1.0에서 확인).
> 원칙: 운전자가 해야 할 **행동**이 있는 경고등만 "차의 할 일"로 만든다. 상태 표시등(방향지시등, 전조등 등)은 무시한다.
> BLINK(점멸)는 한 단계 위 등급으로 올린다(보류 → 권장 → 긴급).
> 문구는 정비 판단을 대신하지 않고 "가까운 정비소에서 점검을 권해요" 수준의 판단 보조에 그친다.

## 🔴 긴급 — 즉시 안내 (안전 위협 가능)
| Telltale | 의미 (쉬운 말) | 권장 행동 |
|---|---|---|
| Engine Oil Pressure | 엔진오일 압력이 낮아요 | 안전한 곳에 세우고 점검 |
| Engine Temperature | 엔진이 과열됐어요 | 안전한 곳에 세우고 식히기, 점검 |
| EV System | 전기차 구동 시스템에 이상이 있어요 | 가까운 정비소 점검 |
| Power Down | 출력이 제한됐어요 | 무리한 주행을 피하고 점검 |
| MDPS | 핸들 보조 장치에 이상이 있어요 (핸들이 무거워질 수 있음) | 저속 주행, 점검 |
| H2 Leak | 수소 누출이 감지됐어요 | 즉시 정차, 긴급 연락 |
| (Energy) 주행 가능 거리 < 30km | 연료·배터리가 거의 없어요 | 가장 가까운 주유·충전 |
| (Safety) 브레이크액 부족 (`registerBrakeFluidLevelState` LOW/CRITICALLY_LOW) | 브레이크액이 부족해요 | 안전한 곳에 세우고 정비소 점검 |

## 🟠 권장 — 오늘~며칠 안에 (경로 편성 때 강하게 추천)
| Telltale | 의미 | 해결 장소 카테고리 |
|---|---|---|
| TPMS | 타이어 공기압이 낮아요 | 공기주입기가 있는 주유소·휴게소, 타이어 전문점 |
| Engine | 엔진 점검이 필요해요 | 정비소 |
| ABS | 미끄럼 방지 제동 장치에 이상이 있어요 | 정비소 |
| ESC | 차체 자세 제어 장치에 이상이 있어요 | 정비소 |
| Airbag | 에어백 점검이 필요해요 | 정비소 |
| Low Volt Battery | 12V 배터리 충전 상태가 좋지 않아요 | 정비소, 배터리 점검 |
| Electronic Parking Brake | 전자식 주차 브레이크에 이상이 있어요 | 정비소 |
| RBS | 회생 제동 장치에 이상이 있어요 | 정비소 |
| Particle Filter | 매연 저감 필터 재생이 필요해요 | 정비소 (일정 시간 고속 주행 안내) |
| H2 Sensor | 수소 센서 점검이 필요해요 | 정비소 |
| Master Warning | 확인이 필요한 경고가 있어요 | 차량 화면 확인 후 정비소 |
| (Energy) 주행 가능 거리 < 80km | 연료·배터리가 부족해요 | 주유소·충전소 |
| Low Fuel (SDK `LOW_FUEL`) | 연료가 얼마 남지 않았어요 | 주유소 |
| Low EV Battery (SDK `LOW_EV_BATTERY`) | 배터리가 얼마 남지 않았어요 | 충전소 |
| (Safety) 엔진오일 부족 (`registerEngineOilLevelState` LOW/CRITICALLY_LOW) | 엔진오일이 부족해요 | 정비소 (보충·교환) |

## ⚪ 보류 — 목록에 유지, 기회가 있을 때만
| 신호 | 의미 | 해결 장소 |
|---|---|---|
| AFS | 적응형 전조등에 이상이 있어요 | 정비소 |
| LED Headlamp | LED 전조등에 이상이 있어요 | 정비소 |
| Emergency Call | 긴급 호출 장치 점검이 필요해요 | 정비소 |
| (Odometer) 정기 점검 주기 도달 | 정기 점검 시기예요 (예: 마지막 점검 후 10,000km) | 정비소 |
| (Safety) 워셔액 부족 (`registerLowWasherFluidWaringState` true) | 워셔액이 부족해요 | 주유소, 정비소 |

## 무시 (상태 표시등 — 할 일 아님)
Parking Brake, Seat Belt, Door Open, ESC Off, Auto Hold, Auto High Beam, Headlamp Low/High, Position Lamp, Front/Rear Fog Lamp, Passenger Airbag Off/On, Turn Signal Left/Right, Preheat, Immobilizer

## spec 대비 변경
- 브레이크액·워셔액 경고는 Telltales 목록에 없음 → 긴급 데모는 **Engine Oil Pressure / 주행 가능 거리**, 보류 데모는 **Odometer 정기 점검**으로 대체
- 시연 추천 세트: 시나리오 3 = TPMS(권장), 시나리오 4 = Remaining Range 25km(긴급), 보류 = Odometer 점검 주기
- (2026-11-12) Vehicle SDK 2.3.2에 Safety 레벨 API(브레이크액·엔진오일·워셔액)와 SDK 경고등 `LOW_FUEL`·`LOW_EV_BATTERY`가 있어 위 표에 추가. 레벨 신호는 경고등이 아니라 capability가 있을 때만 구독하며, 앱은 부족 = ON, 정상 = OFF로 바꿔 같은 분류기에 넣는다. Virtual Console 목록에는 없어 에뮬레이터에서 재현되지 않을 수 있음.

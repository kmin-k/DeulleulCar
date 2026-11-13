#!/usr/bin/env bash
# 에뮬레이터 기기 검증을 한 번에 몰아서 실행한다 (노트북 부하 때문에 에뮬레이터는 이때만 켠다).
# 사용: bash scripts/device-check.sh        (git bash, 저장소 루트에서)
# 결과: build/device-check/ 에 로그와 화면 캡처. 끝나면 에뮬레이터를 끈다(KEEP=1 이면 유지).
set -u
SDK="${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk"
A="$SDK/platform-tools/adb.exe"; E="$SDK/emulator/emulator.exe"
AVD="${AVD:-Pleos_Connect_v2.1.0}"; PORT="${PORT:-18088}"   # 8088은 이 PC에서 막혀 있음
H="http://127.0.0.1:$PORT"; OUT="build/device-check"; mkdir -p "$OUT"
APK="app/build/outputs/apk/debug/app-debug.apk"
probe() { "$A" shell "am start -n ai.deulleulcar.app/.debug.ProbeActivity $*" >/dev/null 2>&1; }
put()   { curl -s -m 5 -o /dev/null -w "PUT $1 -> %{http_code}\n" -X PUT "$H/vehicle/$1"; }
step()  { echo; echo "=== $*"; }

step "1. 에뮬레이터 기동"
if ! "$A" get-state >/dev/null 2>&1; then
  ("$E" -avd "$AVD" -no-snapshot-save -gpu auto >/dev/null 2>&1 &)
fi
for i in $(seq 1 120); do
  [ "$("$A" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && { echo "부팅 완료 (~$((i*5))s)"; break; }
  sleep 5
done
sleep 45; "$A" shell uptime
"$A" install -r "$APK" | tail -1
"$A" forward tcp:$PORT tcp:8088 >/dev/null
"$A" emu geo fix 127.0364 37.5006 >/dev/null

step "2. Virtual Console 세션 유지(1초 heartbeat)"
( for i in $(seq 1 900); do curl -s -m 2 -o /dev/null -X POST "$H/browser/session?client=dc-check"; sleep 1; done ) &
HB=$!; sleep 8
"$A" logcat -c
"$A" logcat -v time 'DC/*:*' '[Navi]a:*' 'AndroidRuntime:E' '*:S' > "$OUT/logcat.txt" 2>&1 &
LC=$!

step "3. T11 차량 신호"
probe "--es cmd vehicle_start"; sleep 15
put "telltales/simulator?color=Red&state=On&type=TPMS"; sleep 4
put "odometer?remaining_range_m=25000"; sleep 4
put "gear?gear=Drive"; sleep 4
put "gear?gear=Park"; sleep 4

step "4. T12 TTS (스피커로 소리가 나는지 들어 주세요)"
probe "--es cmd tts_start,tts_say --es text '들를Car 음성 안내 점검입니다'"; sleep 12
probe "--es cmd tts_urgent"; sleep 12

step "5. T12 STT — 신호가 뜨면 마이크에 '퇴근길에 약국이랑 마트 들러' 라고 말해 주세요"
probe "--es cmd stt_listen --es timeout 10000"; sleep 2; echo ">>> 지금 말하세요 (10초)"; sleep 12

step "6. T13 위치·경로"
probe "--es cmd loc_start,navi_start"; sleep 20
probe "--es cmd navi_route --es n 3"; sleep 40
probe "--es cmd navi_wpinfo"; sleep 10

step "7. T14 앱 화면 캡처"
"$A" shell am start -n ai.deulleulcar.app/.MainActivity >/dev/null; sleep 15
"$A" exec-out screencap -p > "$OUT/home.png"

kill $HB $LC 2>/dev/null
step "결과 요약"
grep -E "CarSignal|tts (state|done)|onStart|onDone|watchdog|stt event|Final|onRout|Waypoint|FATAL" "$OUT/logcat.txt" | grep -v "Probe: loc:" | tail -40
echo; echo "전체 로그: $OUT/logcat.txt, 화면: $OUT/home.png"
[ "${KEEP:-0}" = "1" ] || { "$A" emu kill >/dev/null 2>&1; echo "에뮬레이터 종료"; }

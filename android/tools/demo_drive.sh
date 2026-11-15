#!/usr/bin/env bash
# 시연용 주행 한 번 (#54): 유효 주행(1 km·3분 이상) 하나, 급제동 1회 포함. 약 4분.
# Pleos Virtual Console HTTP API 로 차량 신호를 넣는다 (vc.sh 와 같은 API). bash·curl 만 쓰므로 맥·윈도우(Git Bash) 모두 된다.
#   bash tools/demo_drive.sh            # 에뮬레이터와 앱을 켠 뒤 실행
# 순서: D단 → 0→60 km/h → 60 km/h 100초 → 급제동(60→20, 1초) → 60 km/h 100초 → 천천히 정지 → P단 → 정차 대기
set -euo pipefail

SDK="${ANDROID_HOME:-${LOCALAPPDATA:+$LOCALAPPDATA/Android/Sdk}}"
SDK="${SDK:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
PORT=18088
H="http://localhost:$PORT"

CRUISE_KPH=60
CRUISE_SEC=100
HARD_BRAKE_TO_KPH=20
HARD_BRAKE_PCT=80
HARD_BRAKE_SEC=1
STOP_SEC=15
# 앱의 시연 설정: 정차 10초 뒤 주행을 끝낸다 (AppConfig.DEMO_SESSION_END_IDLE_MS). 여유를 둔다
IDLE_WAIT_SEC=15
STEP_SEC=0.1

# 한 번 실패하면 경고만 하고 이어 간다. 콘솔이 거부한 요청(HTTP 4xx·5xx)도 실패로 본다
put() { curl -s -f -m 2 -X PUT "$H$1" >/dev/null || echo "경고: $1 실패" >&2; }
set_speed() { put "/vehicle/controls?speed=$1&brake=${2:-0}&steering=0&accelerator=0"; }

# 콘솔이 지금 받은 속도(km/h). 읽지 못하면 빈 값
speed_now() { curl -s -m 2 "$H/vehicle/state" | grep -o '"speed_kph": *[0-9.]*' | grep -o '[0-9.]*$'; }

# 속도 0, P단이 됐는지 콘솔에서 읽어 확인하고, 아니면 다시 넣는다(도중에 콘솔 연결이 끊겨 명령이 무시된 경우)
ensure_stopped() {
  local v
  for _ in 1 2 3 4 5; do
    v=$(speed_now)
    [ -n "$v" ] && [ "${v%.*}" = 0 ] && return 0
    set_speed 0; put "/vehicle/gear?gear=Park"; sleep 1
  done
  echo "경고: 속도가 0 이 되지 않았다(지금 ${v:-?} km/h). 콘솔 연결을 확인한다" >&2
}

# from to seconds [brake%] : 0.1초 간격으로 속도를 바꾼다. 콘솔은 속도를 정수(km/h)로만 받아 반올림해 보낸다
ramp() {
  local from=$1 to=$2 sec=$3 brake=${4:-0}
  for v in $(awk -v a="$from" -v b="$to" -v s="$sec" -v d="$STEP_SEC" 'BEGIN { n = int(s / d); if (n < 1) n = 1; for (i = 1; i <= n; i++) printf "%d\n", int(a + (b - a) * i / n + 0.5) }'); do
    set_speed "$v" "$brake"; sleep "$STEP_SEC"
  done
  echo "ramp $from -> $to km/h in ${sec}s (brake $brake%)"
}

hold() { set_speed "$1"; echo "hold $1 km/h for $2s"; sleep "$2"; }

"$ADB" forward "tcp:$PORT" tcp:8088 >/dev/null
# 콘솔은 3초 안에 하트비트가 있어야 쓰기를 받는다
( while true; do curl -s -m 2 -X POST "$H/browser/session?client=demo-drive" >/dev/null || true; sleep 1; done ) &
HEARTBEAT=$!
DONE=0
# 끝까지 가지 못하고 멈추면(Ctrl+C 등) 차를 D단·주행 속도로 두지 않게 속도 0, P단으로 되돌린다
finish() {
  if [ "$DONE" != 1 ]; then
    echo "중단: 속도 0, P단으로 되돌린다" >&2
    ensure_stopped
  fi
  kill "$HEARTBEAT" 2>/dev/null || true
}
trap finish EXIT
sleep 2

put "/vehicle/gear?gear=Drive"; echo "gear Drive"
ramp 0 "$CRUISE_KPH" 10
hold "$CRUISE_KPH" "$CRUISE_SEC"
ramp "$CRUISE_KPH" "$HARD_BRAKE_TO_KPH" "$HARD_BRAKE_SEC" "$HARD_BRAKE_PCT"; echo "급제동 1회"
ramp "$HARD_BRAKE_TO_KPH" "$CRUISE_KPH" 5
hold "$CRUISE_KPH" "$CRUISE_SEC"
ramp "$CRUISE_KPH" 0 "$STOP_SEC"
put "/vehicle/gear?gear=Park"; echo "gear Park"
ensure_stopped
echo "정차 대기 ${IDLE_WAIT_SEC}s (앱이 주행을 끝내고 주행 종료 화면으로 간다)"
sleep "$IDLE_WAIT_SEC"
DONE=1
echo "끝. 로그: adb logcat -s PleosPet"

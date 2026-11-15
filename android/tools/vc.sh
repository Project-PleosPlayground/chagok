#!/bin/zsh
# Pleos Virtual Console HTTP API 로 차량 신호 주입 (에뮬레이터 안 8088 포트, adb forward 로 18088).
# 11/3 확인: 브라우저 하트비트(POST /browser/session) 가 3초 안에 있어야 emulator_active 가 되고 쓰기가 먹는다.
#   vc.sh up                         # adb forward + 하트비트 시작 (백그라운드)
#   vc.sh gear Drive|Park|Reverse|Neutral
#   vc.sh drive <kph> [brake_pct] [steering_deg] [accel_pct]   # 예: vc.sh drive 60 0 0
#   vc.sh steer <deg>                # -540..540 (콘솔 +90 → PERF_STEERING_ANGLE -90.0, 핸들 각 스케일)
#   vc.sh turn Off|BlinkLeft|BlinkRight
#   vc.sh odo <km> [usage_min]
#   vc.sh state                      # controls·gear 요약
#   vc.sh ramp <from_kph> <to_kph> <seconds> [brake_pct]   # 0.1초 간격 램프 (급가속·급제동 재현)
#   vc.sh soc <pct>                  # 배터리 잔량 (11/15)
#   vc.sh down                       # 하트비트 중지
set -e
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
H=http://localhost:18088
PIDF=/tmp/pleos_vc_heartbeat.pid
case "$1" in
  up)
    $ADB forward tcp:18088 tcp:8088 >/dev/null
    [ -f $PIDF ] && kill $(cat $PIDF) 2>/dev/null || true
    ( while true; do curl -s -m 2 -X POST "$H/browser/session?client=vc-sh" >/dev/null; sleep 1; done ) &
    echo $! > $PIDF; sleep 1.5
    curl -s $H/vehicle/state | python3 -c "import sys,json; c=json.load(sys.stdin)['controls']; print('emulator_active =', c['emulator_active'])" ;;
  down) [ -f $PIDF ] && kill $(cat $PIDF) 2>/dev/null; rm -f $PIDF; echo stopped ;;
  gear) curl -s -X PUT "$H/vehicle/gear?gear=$2" | python3 -c "import sys,json; print('gear =', json.load(sys.stdin)['actual']['value'])" ;;
  drive) curl -s -X PUT "$H/vehicle/controls?speed=${2:-0}&brake=${3:-0}&steering=${4:-0}&accelerator=${5:-0}" >/dev/null; echo "speed=${2:-0} brake=${3:-0} steering=${4:-0}" ;;
  steer) s=$(curl -s $H/vehicle/state | python3 -c "import sys,json; c=json.load(sys.stdin)['controls']; print(c['speed_kph'], c['brake_pct'])"); set -- steer $2 ${=s}
         curl -s -X PUT "$H/vehicle/controls?speed=$3&brake=$4&steering=$2&accelerator=0" >/dev/null; echo "steering=$2" ;;
  soc) curl -s -X PUT "$H/vehicle/ev-battery?field=state_of_charge&value=$2" >/dev/null; echo "soc=$2" ;;
  turn) curl -s -X PUT "$H/vehicle/turn-signal?mode=$2" >/dev/null; echo "turn=$2" ;;
  odo) curl -s -X PUT "$H/vehicle/odometer?traveled_distance_km=$2${3:+&usage_time_min=$3}" >/dev/null; echo "odometer=$2 km" ;;
  state) curl -s $H/vehicle/state | python3 -c "import sys,json; d=json.load(sys.stdin); c=d['controls']; print({k:c[k] for k in ['speed_kph','brake_pct','steering_deg','emulator_active']}, 'gear:', (d['gear'].get('actual') or {}).get('value'), 'turn:', d['lights']['turn_signal_mode'])" ;;
  ramp) python3 - "$2" "$3" "$4" "${5:-0}" <<'P'
import sys,time,urllib.request
v0,v1,sec,brake=float(sys.argv[1]),float(sys.argv[2]),float(sys.argv[3]),sys.argv[4]
n=max(1,int(sec*10))
for i in range(1,n+1):
    v=v0+(v1-v0)*i/n
    urllib.request.urlopen(urllib.request.Request(f"http://localhost:18088/vehicle/controls?speed={round(v)}&brake={brake}&steering=0&accelerator=0",method="PUT"),timeout=2).read()
    time.sleep(0.1)
print(f"ramp {v0}->{v1} km/h in {sec}s, brake={brake}")
P
    ;;
  *) sed -n '2,13p' "$0" ;;
esac

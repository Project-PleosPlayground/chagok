#!/usr/bin/env python3
"""7-1 검증 시나리오 생성기. 손으로 쓰면 속도 램프 숫자가 틀리기 쉬워서 스크립트로 만든다.
   출력: engine/src/test/resources/scenarios/L05~L09 (차선변경만). S01~S16 은 팀 계약 JSON 이 정본 (11/4)
   속도는 100ms 간격으로 램프를 찍는다. 정차는 10초에 걸쳐 부드럽게 (브레이크 미사용 → 급제동 아님)."""
import json, os
OUT = os.path.join(os.path.dirname(__file__), '..', 'engine', 'src', 'test', 'resources', 'scenarios')
os.makedirs(OUT, exist_ok=True)
KMH = 1/3.6

class B:
    """속도는 '변경 시 콜백'이 아니라 1Hz 이상으로 들어온다고 가정한다 (AAOS registerCallback 의 sensor rate).
       그래서 hold() 도 1초마다 현재 속도를 다시 찍는다. 안 찍으면 스펙 2-2 의 stale(5초 무응답) 에 걸린다."""
    def __init__(s): s.sig=[]; s.t=0; s.v=0.0
    def at(s, t, **kv):
        if "speed" in kv: s.v = kv["speed"]
        s.sig.append({"t": int(t), **kv}); return s
    def ramp(s, v0, v1, dur_ms, step=100):
        n = dur_ms//step
        for i in range(1, n+1): s.at(s.t + i*step, speed=round(v0 + (v1-v0)*i/n, 4))
        s.t += dur_ms; return s
    def hold(s, ms):
        for k in range(1000, ms+1, 1000): s.at(s.t + k, speed=round(s.v, 4))
        s.t += ms; return s
    def gentle_stop(s, v): return s.ramp(v, 0.0, 10000)

LANE_ONLY = {'S05_lane_change_signaled','S06_lane_change_no_signal','S07_lane_change_tick_signal','S07b_lane_change_wrong_signal','S08_curve','S09_signal_only'}
def write(name, b, expect, endAt=None, override=10, extra=None, config=None):
    if name not in LANE_ONLY: return   # 나머지는 팀 계약 JSON(S01~S16) 이 정본
    name = 'L' + name[1:]
    d = {"name": name, "signals": b.sig, "tickEveryMs": 1000, "endAt": endAt or (b.t + 200000)}
    if override is not None: d["distanceKmOverride"] = override
    if config: d["config"] = config
    d["expect"] = expect
    if extra: d.update(extra)
    with open(os.path.join(OUT, name + '.json'), 'w') as f: json.dump(d, f, indent=1, ensure_ascii=False)
    print('wrote', name)

def start(b, v=0.0):
    b.at(0, gear="D", speed=v, brake=False, steering=0, turnSignal="OFF"); return b

# S1 정상 가속 0→60 km/h 8초 (a≈2.08 < 2.78)
b = start(B()); b.ramp(0, 60*KMH, 8000).hold(5000).gentle_stop(60*KMH)
write('S01_normal_accel', b, {"events": [], "valid": True, "score": 100})

# S2 급가속(출발) 0→50 km/h 2.5초 (a≈5.56 ≥ A2) → 2단계, fromStop
b = start(B()); b.ramp(0, 50*KMH, 2500).hold(5000).gentle_stop(50*KMH)
write('S02_harsh_accel_from_stop', b, {"events": [{"type": "HARSH_ACCEL", "severity": 2, "fromStop": True}],
      "valid": True, "scoreByMode": {"WEIGHTED": 94, "RECORD_ONLY": 96}})

# S2b 급가속(주행 중) 40→70 km/h 2.5초 (a≈3.33, A1 이상 A2 미만)
b = start(B(), 40*KMH); b.hold(3000).ramp(40*KMH, 70*KMH, 2500).hold(5000).gentle_stop(70*KMH)
write('S02b_harsh_accel_moving', b, {"events": [{"type": "HARSH_ACCEL", "severity": 1, "fromStop": False}], "valid": True, "score": 96})

# S3 급제동 60→10 km/h 2초, 페달 (a≈-6.94 ≥ B2). 끝 속도 2.78 ≥ 2.0
b = start(B(), 60*KMH); b.hold(3000); b.at(b.t, brake=True); b.ramp(60*KMH, 10*KMH, 2000); b.at(b.t, brake=False); b.hold(5000).gentle_stop(10*KMH)
write('S03_harsh_brake', b, {"events": [{"type": "HARSH_BRAKE", "severity": 2}], "valid": True,
      "scoreByMode": {"WEIGHTED": 93, "RECORD_ONLY": 95}, "liveEventCount": 1,
      "cues": [{"type": "SESSION_START"}, {"type": "HARSH_BRAKE"}]},
      config={"feedback": {"anyTypeMinMs": 0}})

# S4 회생제동 감속 60→20 km/h 2.5초, 페달 0 → 이벤트 없음
b = start(B(), 60*KMH); b.hold(3000).ramp(60*KMH, 20*KMH, 2500).hold(5000).gentle_stop(20*KMH)
write('S04_regen_decel_no_pedal', b, {"events": [], "valid": True, "score": 100})

# 공통 차선변경 조작: 80 km/h, θ1=15. t0 에 -20, +1s 에 +20, +2s 에 0
def lane(b, signal=None, signal_on_ms=1500, signal_lead_ms=2000, wrong=False):
    v = 80*KMH
    t_lc = b.t + 10000
    if signal:
        b.at(t_lc - signal_lead_ms, turnSignal=signal); b.at(t_lc - signal_lead_ms + signal_on_ms, turnSignal="OFF")
    b.at(t_lc, steering=-20); b.at(t_lc + 1000, steering=20); b.at(t_lc + 2000, steering=0)
    b.t = t_lc + 2000
    return b

b = start(B(), 80*KMH); lane(b, "LEFT"); b.hold(5000).gentle_stop(80*KMH)
write('S05_lane_change_signaled', b, {"events": [{"type": "LANE_CHANGE_SIGNALED", "severity": 1}], "valid": True, "score": 100})

b = start(B(), 80*KMH); lane(b, None); b.hold(5000).gentle_stop(80*KMH)
write('S06_lane_change_no_signal', b, {"events": [{"type": "LANE_CHANGE_NO_SIGNAL", "severity": 1}], "valid": True, "score": 94})

b = start(B(), 80*KMH); lane(b, "LEFT", signal_on_ms=500); b.hold(5000).gentle_stop(80*KMH)
write('S07_lane_change_tick_signal', b, {"events": [{"type": "LANE_CHANGE_NO_SIGNAL", "severity": 1}], "valid": True, "score": 94})

b = start(B(), 80*KMH); lane(b, "RIGHT"); b.hold(5000).gentle_stop(80*KMH)
write('S07b_lane_change_wrong_signal', b, {"events": [{"type": "LANE_CHANGE_WRONG_SIGNAL", "severity": 1}], "valid": True, "score": 94})

# S8 커브: 50 km/h (θ1=25), +30 6초 유지 후 0
b = start(B(), 50*KMH); b.hold(5000); b.at(b.t, steering=30); b.at(b.t + 6000, steering=0); b.t += 6000; b.hold(8000).gentle_stop(50*KMH)
write('S08_curve', b, {"events": [], "valid": True, "score": 100})

# S9 깜빡이만 5초
b = start(B(), 80*KMH); b.hold(5000); b.at(b.t, turnSignal="LEFT"); b.at(b.t + 5000, turnSignal="OFF"); b.t += 5000; b.hold(5000).gentle_stop(80*KMH)
write('S09_signal_only', b, {"events": [], "valid": True, "score": 100})

# S10 짧은 세션 0.5 km, 2분 → valid=false (속도 적분: 15 km/h 로 120초 = 0.5 km)
b = start(B(), 15*KMH); b.hold(120000).gentle_stop(15*KMH)
write('S10_short_session', b, {"events": [], "valid": False, "validated": False}, override=None)

# S11 종합: S2 + S2b + S3 + S6 in 10 km
b = start(B()); b.ramp(0, 50*KMH, 2500).hold(5000)                     # S2
b.ramp(50*KMH, 40*KMH, 5000).hold(3000).ramp(40*KMH, 70*KMH, 2500).hold(5000)   # S2b (완만히 40 으로 내려온 뒤)
b.ramp(70*KMH, 60*KMH, 5000).hold(3000); b.at(b.t, brake=True); b.ramp(60*KMH, 10*KMH, 2000); b.at(b.t, brake=False); b.hold(5000)   # S3
b.ramp(10*KMH, 80*KMH, 20000).hold(3000); lane(b, None); b.hold(5000).gentle_stop(80*KMH)   # S6
write('S11_combined_10km', b, {"events": [
    {"type": "HARSH_ACCEL", "severity": 2, "fromStop": True}, {"type": "HARSH_ACCEL", "severity": 1},
    {"type": "HARSH_BRAKE", "severity": 2}, {"type": "LANE_CHANGE_NO_SIGNAL"}],
    "valid": True, "scoreByMode": {"WEIGHTED": 77, "RECORD_ONLY": 81}})

# S12 후진 중 정지: gear R, -3 m/s → 0 을 1초에
b = B(); b.at(0, gear="D", speed=5.0, brake=False, steering=0, turnSignal="OFF"); b.hold(5000).gentle_stop(5.0)
b.at(b.t, gear="R"); b.at(b.t + 1000, speed=-3.0); b.t += 1000; b.hold(3000); b.at(b.t, brake=True); b.ramp(-3.0, 0.0, 1000); b.at(b.t, brake=False)
b.hold(2000); b.at(b.t, gear="D"); b.hold(3000); b.ramp(0, 30*KMH, 10000).hold(5000).gentle_stop(30*KMH)
write('S12_reverse_stop', b, {"events": [], "valid": True, "score": 100})

# S13 신호 유실: 50 km/h 주행 중 speed 콜백 8초 끊김 → 재개 시 30 km/h
b = start(B(), 50*KMH); b.hold(5000); b.at(b.t, speed=50*KMH); b.t += 8000; b.at(b.t, speed=30*KMH); b.hold(5000).gentle_stop(30*KMH)
write('S13_stale_resume', b, {"events": [], "valid": True, "score": 100})

# S14 음성 빈도 제한: F_same=60초. 급제동 0, 15, 40, 70초에 4회 → cue 는 1번째와 4번째만
def brake_burst(b):
    b.at(b.t, brake=True); b.ramp(60*KMH, 10*KMH, 2000); b.at(b.t, brake=False); b.hold(1000); b.ramp(10*KMH, 60*KMH, 12000)
b = start(B(), 60*KMH); b.hold(3000)
t_first = b.t
for gap in (15000, 25000, 30000):
    t_start = b.t; brake_burst(b); b.hold(max(0, gap - (b.t - t_start)))
brake_burst(b); b.hold(5000).gentle_stop(60*KMH)
write('S14_feedback_rate_limit', b, {"events": [{"type": "HARSH_BRAKE"}]*4, "valid": True,
      "cues": [{"type": "SESSION_START"}, {"type": "HARSH_BRAKE", "atMs": 7500}, {"type": "HARSH_BRAKE"}]},
      config={"feedback": {"sameTypeMinMs": 60000, "anyTypeMinMs": 0, "maxPerTypePerSession": 10}})

# S15 인정 전 이벤트 전달: 0.4 km 지점 급제동, 1 km·3 분 뒤 차선변경, 10 km 까지 (속도 적분)
b = start(B(), 60*KMH); b.hold(24000)                     # 60 km/h × 24 s = 0.4 km
b.at(b.t, brake=True); b.ramp(60*KMH, 10*KMH, 2000); b.at(b.t, brake=False); b.hold(1000); b.ramp(10*KMH, 80*KMH, 10000)
b.hold(200000)                                             # 80 km/h × 200 s ≈ 4.4 km, 3분 경과
lane(b, None); b.hold(5000).gentle_stop(80*KMH)
write('S15_prevalidation_events', b, {"events": [{"type": "HARSH_BRAKE"}, {"type": "LANE_CHANGE_NO_SIGNAL"}],
      "valid": True, "validated": True, "validatedEventCount": 1, "liveEventCount": 2}, override=None)

# S16 미인정 세션: 0.6 km, 2분, 급제동 1회 후 정차
b = start(B(), 30*KMH); b.hold(60000); b.at(b.t, brake=True); b.ramp(30*KMH, 2.2, 1000); b.at(b.t, brake=False); b.hold(1000); b.ramp(2.2, 30*KMH, 10000); b.hold(30000).gentle_stop(30*KMH)
write('S16_unvalidated_with_event', b, {"events": [{"type": "HARSH_BRAKE"}], "valid": False, "validated": False}, override=None)

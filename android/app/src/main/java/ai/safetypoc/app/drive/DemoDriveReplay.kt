package ai.safetypoc.app.drive

import engine.DefaultSafetyScoreEngine
import engine.EngineConfig
import engine.GearState
import engine.SessionResult
import engine.VehicleSignal

/**
 * 시연 주행 한 번을 차량 없이 엔진에 흘려 결과를 만든다 (디버그 탭). `tools/demo_drive.sh` 와 같은 주행이다:
 * D단 → 0→60 km/h → 60 km/h 유지 → 급제동(60→20 km/h, 1초) → 60 km/h 유지 → 정지 → P단.
 * 실제 엔진 탐지·점수 계산을 그대로 거친다. 주행은 [startWallMs] 에 시작한다 (미션은 도전한 뒤 시작한 주행만 센다).
 *
 * [long] 이면 약 20분 시내 주행: 신호 대기와 출발을 다섯 번 되풀이하고, 급가속 3번(초반에 몰림)과 급제동 2번(중반·막바지)이 난다.
 * AI 종합 분석이 충분한 재료로 나오는지 볼 때 쓴다 (11/14).
 */
object DemoDriveReplay {
    private const val STEP_MS = 100L
    private const val MS_PER_S = 1000L
    private const val KPH_TO_MPS = 1f / 3.6f
    private const val ACCEL_S = 10
    private const val CRUISE_KPH = 60f
    private const val CRUISE_S = 100
    private const val HARD_BRAKE_TO_KPH = 20f
    private const val HARD_BRAKE_S = 1
    private const val RECOVER_S = 5
    private const val STOP_S = 15

    /** 구간 하나: [seconds] 동안 [fromKph] → [toKph], [brake] 는 페달을 밟는지 */
    private data class Leg(val fromKph: Float, val toKph: Float, val seconds: Int, val brake: Boolean = false)

    private val LEGS = listOf(
        Leg(0f, CRUISE_KPH, ACCEL_S),
        Leg(CRUISE_KPH, CRUISE_KPH, CRUISE_S),
        Leg(CRUISE_KPH, HARD_BRAKE_TO_KPH, HARD_BRAKE_S, brake = true),
        Leg(HARD_BRAKE_TO_KPH, CRUISE_KPH, RECOVER_S),
        Leg(CRUISE_KPH, CRUISE_KPH, CRUISE_S),
        Leg(CRUISE_KPH, 0f, STOP_S, brake = true),
    )

    private const val CITY_KPH = 50f
    private const val GENTLE_START_S = 10
    private const val HARSH_START_S = 3
    private const val CITY_CRUISE_S = 100
    private const val CITY_BRAKE_TO_KPH = 25f
    private const val CITY_BRAKE_S = 1
    private const val CITY_RECOVER_S = 10
    private const val SIGNAL_WAIT_S = 20
    private const val LONG_IDLE_END_MS = 3 * 60_000L

    /** 신호 하나 사이 구간: 출발(급하게 / 부드럽게) → 달림 → (급제동) → 달림 → 천천히 정지 → 신호 대기 */
    private fun block(harshStart: Boolean, harshBrake: Boolean): List<Leg> = buildList {
        add(Leg(0f, CITY_KPH, if (harshStart) HARSH_START_S else GENTLE_START_S))
        add(Leg(CITY_KPH, CITY_KPH, CITY_CRUISE_S))
        if (harshBrake) {
            add(Leg(CITY_KPH, CITY_BRAKE_TO_KPH, CITY_BRAKE_S, brake = true))
            add(Leg(CITY_BRAKE_TO_KPH, CITY_KPH, CITY_RECOVER_S))
        }
        add(Leg(CITY_KPH, CITY_KPH, CITY_CRUISE_S))
        add(Leg(CITY_KPH, 0f, STOP_S, brake = true))
        add(Leg(0f, 0f, SIGNAL_WAIT_S, brake = true))
    }

    private val LONG_LEGS = block(harshStart = true, harshBrake = false) + block(harshStart = true, harshBrake = false) +
        block(harshStart = false, harshBrake = true) + block(harshStart = true, harshBrake = false) +
        block(harshStart = false, harshBrake = true)

    private fun legs(long: Boolean) = if (long) LONG_LEGS else LEGS

    /** 엔진 시각 [startMs] 부터의 신호 목록 */
    fun signals(startMs: Long, long: Boolean = false): List<VehicleSignal> {
        val out = ArrayList<VehicleSignal>()
        var t = startMs
        var meters = 0f
        var brake = false
        out += VehicleSignal.Gear(t, GearState.D)
        out += VehicleSignal.BrakePedal(t, false)
        out += VehicleSignal.Speed(t, 0f)
        out += VehicleSignal.TripDistance(t, 0f)
        for (leg in legs(long)) {
            if (leg.brake != brake) { brake = leg.brake; out += VehicleSignal.BrakePedal(t, brake) }
            val steps = (leg.seconds * MS_PER_S / STEP_MS).toInt()
            for (i in 1..steps) {
                t += STEP_MS
                val mps = (leg.fromKph + (leg.toKph - leg.fromKph) * i / steps) * KPH_TO_MPS
                meters += mps * STEP_MS / MS_PER_S
                out += VehicleSignal.Speed(t, mps)
                if (i % (MS_PER_S / STEP_MS).toInt() == 0) out += VehicleSignal.TripDistance(t, meters)
            }
        }
        out += VehicleSignal.TripDistance(t, meters)
        out += VehicleSignal.BrakePedal(t, false)
        out += VehicleSignal.Gear(t, GearState.P)
        return out
    }

    /** 주행 길이 (ms) */
    fun durationMs(long: Boolean = false): Long = legs(long).sumOf { it.seconds } * MS_PER_S

    /** 새 엔진에 흘려 끝난 주행 결과를 낸다. 세션이 열리지 않으면 null */
    fun run(config: EngineConfig, startWallMs: Long, long: Boolean = false): SessionResult? {
        // 디버그 빌드는 정차 뒤 곧 주행을 끝낸다(AppConfig). 긴 주행의 신호 대기에서 끊기지 않게 스펙 기본값(3분)으로 돌린다
        val engine = DefaultSafetyScoreEngine(if (long) config.copy(sessionEndIdleMs = maxOf(config.sessionEndIdleMs, LONG_IDLE_END_MS)) else config)
        for (s in signals(startWallMs, long)) {
            engine.onSignal(s)
            engine.onTick(s.timestampMs)
        }
        return engine.endSessionNow(startWallMs + durationMs(long))
    }
}

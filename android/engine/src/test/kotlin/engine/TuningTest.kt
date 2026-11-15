package engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 스펙 6-1 원격 조정 수치: 범위·간격·묶음·관계 규칙, 적용, 세션 고정, 버전 기록 */
class TuningTest {

    private val base = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)

    /** 코드 기본값(블루링크 프리셋)을 원격 값 형태로 쓴 것 */
    private val defaults: Map<String, Double> = mapOf(
        "accel1Kmhs" to 10.0, "accel2Kmhs" to 15.0, "brake1Kmhs" to 13.0, "brake2Kmhs" to 19.5,
        "penaltyAccel" to 4.0, "penaltyAccelSevere" to 6.0, "penaltyBrake" to 5.0, "penaltyBrakeSevere" to 7.5,
        "penaltyLaneNoSignal" to 6.0, "penaltyLaneWrongSignal" to 6.0, "penaltyWeaving" to 8.0, "penaltySpeedingPer60s" to 5.0,
        "capAccel" to 30.0, "capBrake" to 35.0, "capLane" to 30.0, "capWeaving" to 16.0, "capSpeeding" to 25.0,
        "feedbackSameTypeMinSec" to 90.0, "feedbackAnyTypeMinSec" to 20.0, "feedbackMaxPerType" to 3.0,
        "brakeStableSec" to 1.5, "brakeStableAccel" to -1.0, "accelDelaySec" to 1.0, "sessionStartDelaySec" to 3.0,
        "praiseFirstSignaled" to 1.0,
        "laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
        "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 10.0,
        "steeringRatio" to 1.0, "steeringInverted" to 0.0, "signalMinOnSec" to 1.0,
        "sessionEndIdleSec" to 180.0,
    )

    private fun with(vararg kv: Pair<String, Double>) = defaults + kv

    @Nested
    inner class 범위와_간격 {
        @Test
        fun `모든 수치가 표에 한 번씩 있다`() {
            assertEquals(TunableParam.entries.map { it.key }.toSet(), defaults.keys)
        }

        @Test
        fun `코드 기본값은 모두 통과한다`() {
            assertEquals(emptyList<String>(), EngineTuning.problems(defaults))
        }

        @Test
        fun `공단 프리셋도 간격 위에 있다`() {
            val kotsa = with("accel1Kmhs" to 11.0, "accel2Kmhs" to 16.5, "brake1Kmhs" to 7.5, "brake2Kmhs" to 11.25)
            assertEquals(emptyList<String>(), EngineTuning.problems(kotsa))
        }

        @Test
        fun `최솟값과 최댓값은 받고 한 칸 밖과 칸 사이는 거절한다`() {
            for (p in TunableParam.entries) {
                assertTrue(p.isOnGrid(p.min), "${p.key} min")
                assertTrue(p.isOnGrid(p.max), "${p.key} max")
                assertFalse(p.isOnGrid(p.min - p.step), "${p.key} min-step")
                assertFalse(p.isOnGrid(p.max + p.step), "${p.key} max+step")
                assertFalse(p.isOnGrid(p.min + p.step / 2), "${p.key} 칸 사이")
                assertFalse(p.isOnGrid(Double.NaN), "${p.key} NaN")
                assertFalse(p.isOnGrid(Double.POSITIVE_INFINITY), "${p.key} Inf")
            }
        }

        @Test
        fun `상한을 뺀 수치는 비트필드로 담을 수 있게 칸 수가 2의 거듭제곱 - 1 이다`() {
            val caps = TunableParam.entries.filter { it.key.startsWith("cap") }
            for (p in TunableParam.entries - caps.toSet()) {
                val n = p.maxSteps + 1
                assertTrue(n and (n - 1) == 0, "${p.key} maxSteps=${p.maxSteps}")
            }
            caps.forEach { assertEquals(100.0, it.max, it.key) }
        }

        @Test
        fun `0_1 간격의 부동소수 오차는 통과하고 엔진 값은 정확하다`() {
            // -3.2 + 0.1 × 22 = -1.0000000000000004
            assertTrue(TunableParam.BRAKE_STABLE_ACCEL.isOnGrid(-1.0))
            val cfg = EngineTuning.apply(base, with("brakeStableAccel" to -0.7, "brakeStableSec" to 0.3), 2)
            assertEquals(-0.7f, cfg.feedback.brakeStableAccel)
            assertEquals(300L, cfg.feedback.brakeStableMs)
        }

        @Test
        fun `범위 밖 값 하나면 전부 거절한다`() {
            val bad = listOf(
                "accel1Kmhs" to 4.75, "brake2Kmhs" to 37.0, "penaltyAccel" to -0.5, "capBrake" to 101.0,
                "feedbackAnyTypeMinSec" to 0.0, "feedbackMaxPerType" to 16.0, "feedbackMaxPerType" to 2.5,
                "brakeStableAccel" to 0.0, "laneBand1Kmh" to 25.0, "laneTheta1Band3Deg" to 5.0,
                "steeringRatio" to 0.5, "steeringInverted" to 2.0, "praiseFirstSignaled" to 0.5,
                "signalMinOnSec" to 0.0, "sessionEndIdleSec" to 30.0,
            )
            for (kv in bad) assertTrue(EngineTuning.problems(with(kv)).isNotEmpty(), "${kv.first}=${kv.second}")
        }

        @Test
        fun `모르는 이름은 거절한다`() {
            assertTrue(EngineTuning.problems(mapOf("theta0Deg" to 5.0)).isNotEmpty())
        }
    }

    @Nested
    inner class 묶음과_관계 {
        @Test
        fun `묶음 일부만 오면 거절하고 묶음 없는 수치는 혼자 와도 된다`() {
            assertTrue(EngineTuning.problems(mapOf("accel1Kmhs" to 10.0)).isNotEmpty())
            assertTrue(EngineTuning.problems(mapOf("laneBand1Kmh" to 30.0)).isNotEmpty())
            assertTrue(EngineTuning.problems(mapOf("feedbackSameTypeMinSec" to 90.0)).isNotEmpty())
            assertEquals(emptyList<String>(), EngineTuning.problems(mapOf("capBrake" to 40.0)))
            assertEquals(emptyList<String>(), EngineTuning.problems(emptyMap()))
        }

        @Test
        fun `관계 규칙을 어기면 거절한다`() {
            val bad = listOf(
                with("accel1Kmhs" to 16.0),                                  // A1 > A2
                with("brake2Kmhs" to 12.0),                                  // B2 < B1
                with("penaltyAccelSevere" to 3.5),                           // 2단계 < 1단계
                with("penaltyBrake" to 8.0),
                with("feedbackAnyTypeMinSec" to 95.0),                       // F_all > F_same
                with("brake1Kmhs" to 5.0, "brakeStableAccel" to -1.4),       // 5 km/h/s ≈ 1.389 m/s² → -1.4 는 더 세다
                with("laneBand2Kmh" to 30.0),                                // 구간 속도 같음
                with("laneBand3Kmh" to 55.0),                                // 구간 속도 거꾸로
                with("laneTheta1Band2Deg" to 30.0),                          // 빠른 구간 θ1 이 더 큼
            )
            bad.forEachIndexed { i, m -> assertTrue(EngineTuning.problems(m).isNotEmpty(), "case $i") }
        }

        @Test
        fun `경계는 받는다`() {
            assertEquals(emptyList<String>(), EngineTuning.problems(with("accel2Kmhs" to 10.0)))           // A1 = A2
            assertEquals(emptyList<String>(), EngineTuning.problems(with("feedbackAnyTypeMinSec" to 90.0))) // F_all = F_same
            assertEquals(emptyList<String>(), EngineTuning.problems(with("brake1Kmhs" to 5.0, "brakeStableAccel" to -1.3)))
        }
    }

    @Nested
    inner class 적용 {
        @Test
        fun `기본값을 그대로 보내면 원래 설정과 같다`() {
            val cfg = EngineTuning.apply(base, defaults, 5)
            assertEquals(base.copy(thresholds = base.thresholds.copy(name = EngineTuning.REMOTE_THRESHOLDS_NAME), paramsVersion = 5), cfg)
        }

        @Test
        fun `km_h_s 는 프리셋과 같은 계산으로 m_s2 가 된다`() {
            val cfg = EngineTuning.apply(base, with("accel1Kmhs" to 11.0, "accel2Kmhs" to 16.5, "brake1Kmhs" to 7.5, "brake2Kmhs" to 11.25), 1)
            assertEquals(Presets.KOTSA.copy(name = EngineTuning.REMOTE_THRESHOLDS_NAME), cfg.thresholds)
        }

        @Test
        fun `초는 ms 로, 0_1 값은 켬·끔으로, 구간은 엔진 순서로 바뀐다`() {
            val cfg = EngineTuning.apply(
                base,
                with(
                    "feedbackSameTypeMinSec" to 120.0, "feedbackAnyTypeMinSec" to 15.0, "sessionEndIdleSec" to 300.0,
                    "signalMinOnSec" to 0.4, "praiseFirstSignaled" to 0.0, "steeringInverted" to 1.0, "steeringRatio" to 14.5,
                    "laneBand1Kmh" to 40.0, "laneBand2Kmh" to 70.0, "laneBand3Kmh" to 100.0,
                    "laneTheta1Band1Deg" to 20.0, "laneTheta1Band2Deg" to 12.5, "laneTheta1Band3Deg" to 8.0,
                ),
                9,
            )
            assertEquals(120_000L, cfg.feedback.sameTypeMinMs)
            assertEquals(15_000L, cfg.feedback.anyTypeMinMs)
            assertEquals(300_000L, cfg.sessionEndIdleMs)
            assertEquals(400L, cfg.laneChange.signalMinOnMs)
            assertFalse(cfg.feedback.praiseFirstSignaled)
            assertTrue(cfg.laneChange.steeringInverted)
            assertEquals(14.5f, cfg.laneChange.steeringRatio)
            assertEquals(listOf(100 to 8f, 70 to 12.5f, 40 to 20f), cfg.laneChange.theta1ByKmh)
            assertEquals(9, cfg.paramsVersion)
        }

        @Test
        fun `모든 수치는 값을 바꾸면 설정이 바뀐다`() {
            // 수치를 추가하고 적용을 빠뜨리면 값이 말없이 무시된다. 그 경우를 수치마다 확인한다
            val same = EngineTuning.apply(base, defaults, 1)
            for (p in TunableParam.entries) {
                val d = defaults.getValue(p.key)
                val other = generateSequence(1) { it + 1 }.take(p.maxSteps)
                    .flatMap { k -> sequenceOf(d + p.step * k, d - p.step * k) }
                    .firstOrNull { p.isOnGrid(it) && EngineTuning.problems(with(p.key to it)).isEmpty() }
                assertNotNull(other, "${p.key}: 바꿔 볼 값이 없다")
                val changed = EngineTuning.apply(base, with(p.key to other!!), 1)
                assertTrue(changed != same, "${p.key} = $other 를 보냈는데 설정이 그대로다")
            }
        }

        @Test
        fun `구간 값을 어떤 순서로 보내도 같은 구간에 들어간다`() {
            val bands = listOf(
                "laneBand1Kmh" to 65.0, "laneBand2Kmh" to 80.0, "laneBand3Kmh" to 120.0,
                "laneTheta1Band1Deg" to 20.0, "laneTheta1Band2Deg" to 12.0, "laneTheta1Band3Deg" to 8.0,
            )
            val expected = listOf(120 to 8f, 80 to 12f, 65 to 20f)
            assertEquals(expected, EngineTuning.apply(base, bands.toMap(), 1).laneChange.theta1ByKmh)
            assertEquals(expected, EngineTuning.apply(base, LinkedHashMap(bands.reversed().toMap()), 1).laneChange.theta1ByKmh)
        }

        @Test
        fun `없는 값은 base 를 쓴다`() {
            val cfg = EngineTuning.apply(base, mapOf("capBrake" to 40.0), 3)
            assertEquals(base.copy(penalty = base.penalty.copy(brakeCap = 40f), paramsVersion = 3), cfg)
        }

        @Test
        fun `규칙을 어긴 값은 적용하지 않고 예외를 던진다`() {
            assertThrows<IllegalArgumentException> { EngineTuning.apply(base, mapOf("capBrake" to 101.0), 3) }
        }

        @Test
        fun `조향비는 θ1 과 θ0 을 입력 조향각 단위로 나눈다`() {
            val lc = LaneChangeConfig(steeringRatio = 2f)
            assertEquals(7.5f, lc.theta1For(80 / 3.6f))
            assertEquals(2.5f, lc.theta0Input)
        }
    }

    @Nested
    inner class 엔진 {
        private fun lane(e: SafetyScoreEngine, startMs: Long, amp: Float, signal: TurnSignalState, back: Float = 0f): Long {
            // 80 km/h 직진 → (startMs+8s) 깜빡이 1.5초 → (startMs+10s) 좌로 amp, (11s) 우로 amp, (12s) back 으로 복귀 → 16s
            val v = 80 / 3.6f
            e.onSignal(VehicleSignal.Gear(startMs, GearState.D))
            e.onSignal(VehicleSignal.Speed(startMs, v))
            e.onSignal(VehicleSignal.SteeringAngle(startMs, 0f))
            e.onSignal(VehicleSignal.TurnSignal(startMs, TurnSignalState.OFF))
            var t = startMs
            val plan = mapOf(
                8_000L to { e.onSignal(VehicleSignal.TurnSignal(t, signal)) },
                9_500L to { e.onSignal(VehicleSignal.TurnSignal(t, TurnSignalState.OFF)) },
                10_000L to { e.onSignal(VehicleSignal.SteeringAngle(t, -amp)) },
                11_000L to { e.onSignal(VehicleSignal.SteeringAngle(t, amp)) },
                12_000L to { e.onSignal(VehicleSignal.SteeringAngle(t, back)) },
            )
            while (t <= startMs + 16_000) {
                plan[t - startMs]?.invoke()
                e.onSignal(VehicleSignal.Speed(t, v))
                e.onTick(t)
                t += 100
            }
            return t
        }

        private fun laneTypes(r: SessionResult?) = r?.events?.map { it.type }?.filter { it.name.startsWith("LANE") }

        @Test
        fun `세션 기록에 수치 버전이 남는다`() {
            val e = DefaultSafetyScoreEngine(base.copy(paramsVersion = 7))
            val end = lane(e, 0, 20f, TurnSignalState.LEFT)
            val r = e.endSessionNow(end)
            assertNotNull(r)
            assertEquals(7, r!!.paramsVersion)
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(r))
        }

        @Test
        fun `내장 기본값만 쓰면 버전은 0 이다`() {
            val e = DefaultSafetyScoreEngine(base)
            assertEquals(EngineConfig.BUILT_IN_PARAMS_VERSION, e.endSessionNow(lane(e, 0, 20f, TurnSignalState.LEFT))!!.paramsVersion)
        }

        @Test
        fun `세션 중에 받은 설정은 그 세션에 쓰지 않고 다음 세션부터 쓴다`() {
            val e = DefaultSafetyScoreEngine(base.copy(paramsVersion = 1))
            e.onSignal(VehicleSignal.Gear(0, GearState.D)); e.onSignal(VehicleSignal.Speed(0, 20f)); e.onTick(1_000)
            // 세션 진행 중에 부호 반전 설정이 온다
            e.updateConfig(base.copy(paramsVersion = 2, laneChange = base.laneChange.copy(steeringInverted = true)))
            val end = lane(e, 1_000, 20f, TurnSignalState.LEFT)
            val first = e.endSessionNow(end)
            assertEquals(1, first!!.paramsVersion)
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(first))

            // 종료 시각에 정차했다가 다시 출발한다 (속도가 남아 있으면 종료 직후 바로 새 세션이 시작된다)
            e.onSignal(VehicleSignal.Speed(end, 0f)); e.onTick(25_000)
            val second = e.endSessionNow(lane(e, 30_000, 20f, TurnSignalState.LEFT))
            assertEquals(30_000L, second!!.startMs)
            assertEquals(2, second!!.paramsVersion)
            assertEquals(listOf(EventType.LANE_CHANGE_WRONG_SIGNAL), laneTypes(second))
        }

        @Test
        fun `세션 시작 전에 여러 번 받으면 마지막 것만 쓴다`() {
            val e = DefaultSafetyScoreEngine(base)
            e.updateConfig(base.copy(paramsVersion = 3))
            e.updateConfig(base.copy(paramsVersion = 4))
            assertEquals(4, e.endSessionNow(lane(e, 0, 20f, TurnSignalState.LEFT))!!.paramsVersion)
        }

        @Test
        fun `신호 처리 구조 수치가 다른 설정은 거절한다`() {
            val e = DefaultSafetyScoreEngine(base)
            assertThrows<IllegalArgumentException> { e.updateConfig(base.copy(resampleMs = 50)) }
            assertThrows<IllegalArgumentException> { e.updateConfig(base.copy(ringBufferMs = 10_000)) }
        }

        @Test
        fun `조향비를 올리면 작은 조향각도 차선변경으로 잡힌다`() {
            // 80 km/h 구간 θ1 = 15° 인데 입력은 ±10°
            val plain = DefaultSafetyScoreEngine(base)
            assertEquals(emptyList<EventType>(), laneTypes(plain.endSessionNow(lane(plain, 0, 10f, TurnSignalState.LEFT))))
            val scaled = DefaultSafetyScoreEngine(base.copy(laneChange = base.laneChange.copy(steeringRatio = 2f)))
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(scaled.endSessionNow(lane(scaled, 0, 10f, TurnSignalState.LEFT))))
        }

        @Test
        fun `조향비는 복귀 기준 θ0 에도 적용된다`() {
            // 조향비 2 → θ0 = 2.5°. 3° 로 돌아오면 아직 복귀가 아니고, 2° 면 복귀다. (θ0 을 나누지 않으면 5° 라 3° 도 복귀가 된다)
            val cfg = base.copy(laneChange = base.laneChange.copy(steeringRatio = 2f))
            val notBack = DefaultSafetyScoreEngine(cfg)
            assertEquals(emptyList<EventType>(), laneTypes(notBack.endSessionNow(lane(notBack, 0, 10f, TurnSignalState.LEFT, back = 3f))))
            val back = DefaultSafetyScoreEngine(cfg)
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(back.endSessionNow(lane(back, 0, 10f, TurnSignalState.LEFT, back = 2f))))
        }

        @Test
        fun `원격 값으로 만든 설정이 엔진 판정을 바꾼다`() {
            // 80 km/h 는 구간 2. 원격으로 θ1 을 15° → 9.5° 로 낮추면 ±10° 도 차선변경이다
            val remote = mapOf(
                "laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
                "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 9.5, "laneTheta1Band3Deg" to 9.0,
            )
            val e = DefaultSafetyScoreEngine(EngineTuning.apply(base, remote, 11))
            val r = e.endSessionNow(lane(e, 0, 10f, TurnSignalState.LEFT))
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(r))
            assertEquals(11, r!!.paramsVersion)
        }

        @Test
        fun `깜빡이 최소 유지 시간을 줄이면 짧은 깜빡이도 매칭된다`() {
            // 깜빡이는 1.5초 켜진다. 최소 유지가 1.6초면 NO_SIGNAL, 0.4초면 SIGNALED
            val strict = DefaultSafetyScoreEngine(base.copy(laneChange = base.laneChange.copy(signalMinOnMs = 1_600)))
            assertEquals(listOf(EventType.LANE_CHANGE_NO_SIGNAL), laneTypes(strict.endSessionNow(lane(strict, 0, 20f, TurnSignalState.LEFT))))
            val loose = DefaultSafetyScoreEngine(base.copy(laneChange = base.laneChange.copy(signalMinOnMs = 400)))
            assertEquals(listOf(EventType.LANE_CHANGE_SIGNALED), laneTypes(loose.endSessionNow(lane(loose, 0, 20f, TurnSignalState.LEFT))))
        }
    }
}

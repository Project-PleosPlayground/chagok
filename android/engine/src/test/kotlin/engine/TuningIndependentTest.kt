package engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 스펙 6-1 "원격 조정 수치"를 기준(oracle)으로 쓴 독립 테스트. 구현 본문은 보지 않고 스펙 표에서 기대값을 뽑았다.
 * 스펙이 말하지 않는 부분은 각 테스트 주석에 "가정:"으로 적었다.
 */
class TuningIndependentTest {

    // ───────────────────────── 스펙 표 (6-1) ─────────────────────────

    data class Row(val key: String, val default: Double?, val min: Double, val max: Double, val step: Double, val group: TuningGroup?)

    companion object {
        private val TH = TuningGroup.THRESHOLDS
        private val AP = TuningGroup.ACCEL_PENALTY
        private val BP = TuningGroup.BRAKE_PENALTY
        private val FI = TuningGroup.FEEDBACK_INTERVALS
        private val LB = TuningGroup.LANE_BANDS

        /** 6-1 의 다섯 표를 그대로 옮긴 것. default=null 은 "프리셋" */
        val ROWS = listOf(
            Row("accel1Kmhs", null, 5.0, 36.75, 0.25, TH),
            Row("accel2Kmhs", null, 5.0, 36.75, 0.25, TH),
            Row("brake1Kmhs", null, 5.0, 36.75, 0.25, TH),
            Row("brake2Kmhs", null, 5.0, 36.75, 0.25, TH),
            Row("penaltyAccel", 4.0, 0.0, 31.5, 0.5, AP),
            Row("penaltyAccelSevere", 6.0, 0.0, 31.5, 0.5, AP),
            Row("penaltyBrake", 5.0, 0.0, 31.5, 0.5, BP),
            Row("penaltyBrakeSevere", 7.5, 0.0, 31.5, 0.5, BP),
            Row("penaltyLaneNoSignal", 6.0, 0.0, 31.5, 0.5, null),
            Row("penaltyLaneWrongSignal", 6.0, 0.0, 31.5, 0.5, null),
            Row("penaltyWeaving", 8.0, 0.0, 31.5, 0.5, null),
            Row("penaltySpeedingPer60s", 5.0, 0.0, 31.5, 0.5, null),
            Row("capAccel", 30.0, 0.0, 100.0, 1.0, null),
            Row("capBrake", 35.0, 0.0, 100.0, 1.0, null),
            Row("capLane", 30.0, 0.0, 100.0, 1.0, null),
            Row("capWeaving", 16.0, 0.0, 100.0, 1.0, null),
            Row("capSpeeding", 25.0, 0.0, 100.0, 1.0, null),
            Row("feedbackSameTypeMinSec", 90.0, 10.0, 640.0, 10.0, FI),  // 스펙 6-1 수정: 0~630 → 10~640 (F_all ≥ 5 와의 모순 해소)
            Row("feedbackAnyTypeMinSec", 20.0, 5.0, 320.0, 5.0, FI),
            Row("feedbackMaxPerType", 3.0, 0.0, 15.0, 1.0, null),
            Row("brakeStableSec", 1.5, 0.0, 6.3, 0.1, null),
            Row("brakeStableAccel", -1.0, -3.2, -0.1, 0.1, TH),
            Row("accelDelaySec", 1.0, 0.0, 6.3, 0.1, null),
            Row("sessionStartDelaySec", 3.0, 0.0, 31.5, 0.5, null),
            Row("praiseFirstSignaled", 1.0, 0.0, 1.0, 1.0, null),
            Row("laneBand1Kmh", 30.0, 30.0, 185.0, 5.0, LB),
            Row("laneBand2Kmh", 60.0, 30.0, 185.0, 5.0, LB),
            Row("laneBand3Kmh", 90.0, 30.0, 185.0, 5.0, LB),
            Row("laneTheta1Band1Deg", 25.0, 5.5, 69.0, 0.5, LB),
            Row("laneTheta1Band2Deg", 15.0, 5.5, 69.0, 0.5, LB),
            Row("laneTheta1Band3Deg", 10.0, 5.5, 69.0, 0.5, LB),
            Row("steeringRatio", 1.0, 1.0, 32.75, 0.25, null),
            Row("steeringInverted", 0.0, 0.0, 1.0, 1.0, null),
            Row("signalMinOnSec", 1.0, 0.1, 1.6, 0.1, null),
            Row("sessionEndIdleSec", 180.0, 120.0, 2010.0, 30.0, null),
        )

        fun row(key: String) = ROWS.first { it.key == key }

        /** 프리셋 값 (6-1): 블루링크 10/15/13/19.5, 공단 11/16.5/7.5/11.25. 안정 판정 가속도는 같은 묶음이라 함께 */
        val BL = mapOf("accel1Kmhs" to 10.0, "accel2Kmhs" to 15.0, "brake1Kmhs" to 13.0, "brake2Kmhs" to 19.5, "brakeStableAccel" to -1.0)
        val KO = mapOf("accel1Kmhs" to 11.0, "accel2Kmhs" to 16.5, "brake1Kmhs" to 7.5, "brake2Kmhs" to 11.25, "brakeStableAccel" to -1.0)

        fun allDefaults(preset: Map<String, Double>): Map<String, Double> =
            ROWS.filter { it.default != null }.associate { it.key to it.default!! } + preset

        val BASE = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)

        /** JSON 에 "6.3" 처럼 10진 표기로 적었을 때 파싱되는 값 */
        fun gridValue(r: Row, k: Long): Double =
            BigDecimal(r.min.toString()).add(BigDecimal(r.step.toString()).multiply(BigDecimal(k))).toDouble()

        fun stepCount(r: Row): Long =
            BigDecimal(r.max.toString()).subtract(BigDecimal(r.min.toString())).divide(BigDecimal(r.step.toString())).toLong()

        /**
         * key=x 를 보낼 때, 같은 묶음의 나머지를 관계 규칙이 깨지지 않게 채운 맵.
         * 관계 규칙상 원래 불가능한 x(구간1=185 등)는 [feasible] 로 따로 거른다.
         */
        fun ctx(key: String, x: Double): Map<String, Double> {
            val m = LinkedHashMap<String, Double>()
            when (row(key).group) {
                null -> {}
                TH -> {
                    m += mapOf("accel1Kmhs" to 5.0, "accel2Kmhs" to 36.75, "brake1Kmhs" to 5.0, "brake2Kmhs" to 36.75, "brakeStableAccel" to -0.1)
                    if (key == "brakeStableAccel") m["brake1Kmhs"] = 36.75
                }
                AP -> m += mapOf("penaltyAccel" to 0.0, "penaltyAccelSevere" to 31.5)
                BP -> m += mapOf("penaltyBrake" to 0.0, "penaltyBrakeSevere" to 31.5)
                FI -> m += mapOf("feedbackSameTypeMinSec" to 630.0, "feedbackAnyTypeMinSec" to 5.0)
                LB -> {
                    m += mapOf(
                        "laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
                        "laneTheta1Band1Deg" to 69.0, "laneTheta1Band2Deg" to 37.0, "laneTheta1Band3Deg" to 5.5,
                    )
                    when (key) {
                        "laneBand1Kmh" -> { m["laneBand2Kmh"] = x + 5; m["laneBand3Kmh"] = x + 10 }
                        "laneBand2Kmh" -> { m["laneBand1Kmh"] = x - 5; m["laneBand3Kmh"] = x + 5 }
                        "laneBand3Kmh" -> { m["laneBand1Kmh"] = x - 10; m["laneBand2Kmh"] = x - 5 }
                        "laneTheta1Band1Deg" -> { m["laneTheta1Band2Deg"] = 5.5; m["laneTheta1Band3Deg"] = 5.5 }
                        "laneTheta1Band2Deg" -> { m["laneTheta1Band1Deg"] = 69.0; m["laneTheta1Band3Deg"] = 5.5 }
                        "laneTheta1Band3Deg" -> { m["laneTheta1Band1Deg"] = 69.0; m["laneTheta1Band2Deg"] = 69.0 }
                    }
                }
                else -> {}
            }
            m[key] = x
            return m
        }

        /**
         * 범위 안이지만 관계 규칙 때문에 어떤 묶음으로도 보낼 수 없는 값.
         * 스펙 모순 후보: F_same 범위는 0부터인데 F_all ≥ 5 이고 F_all ≤ F_same 이라 F_same < 5 는 항상 거절된다.
         * 구간 1 의 상한 185, 구간 3 의 하한 30 도 "구간1<구간2<구간3" 때문에 보낼 수 없다.
         */
        fun feasible(key: String, x: Double): Boolean = when (key) {
            "feedbackSameTypeMinSec" -> x >= 5.0
            "laneBand1Kmh" -> x <= 175.0
            "laneBand2Kmh" -> x in 35.0..180.0
            "laneBand3Kmh" -> x >= 40.0
            else -> true
        }

        fun ok(m: Map<String, Double>) = assertEquals(emptyList<String>(), EngineTuning.problems(m), "거절되면 안 됨: $m")
        fun bad(m: Map<String, Double>) {
            assertTrue(EngineTuning.problems(m).isNotEmpty(), "거절돼야 함: $m")
            assertThrows<IllegalArgumentException>("apply 도 IllegalArgumentException 이어야 함: $m") { EngineTuning.apply(BASE, m, 1) }
        }

        /** 비교용: thresholds 이름(REMOTE 등)과 θ1 목록 순서는 동작과 무관하므로 정규화 */
        fun norm(c: EngineConfig) = c.copy(
            thresholds = Thresholds("X", c.thresholds.accel1, c.thresholds.accel2, c.thresholds.brake1, c.thresholds.brake2),
            laneChange = c.laneChange.copy(theta1ByKmh = c.laneChange.theta1ByKmh.sortedBy { it.first }),
        )
    }

    // ───────────────────────── 1. 표의 모든 행 ─────────────────────────

    @Test
    fun `코드의 수치 목록이 스펙 표와 정확히 같다 (36개)`() {
        assertEquals(ROWS.map { it.key }.toSet(), TunableParam.entries.map { it.key }.toSet())
        assertEquals(ROWS.size, TunableParam.entries.size)
    }

    @TestFactory
    fun `행마다 이름·범위·간격·묶음이 스펙과 같다`(): List<DynamicTest> = ROWS.map { r ->
        DynamicTest.dynamicTest(r.key) {
            val p0 = TunableParam.ofKey(r.key); assertNotNull(p0, "TunableParam 에 ${r.key} 없음"); val p = p0!!
            assertEquals(r.min, p.min, 1e-9, "min")
            assertEquals(r.step, p.step, 1e-9, "step")
            assertEquals(r.max, p.max, 1e-9, "max")
            assertEquals(stepCount(r), p.maxSteps.toLong(), "maxSteps")
            assertEquals(r.group, p.group, "group")
        }
    }

    @TestFactory
    fun `상한을 뺀 수치의 칸 수는 2의 거듭제곱`(): List<DynamicTest> = ROWS.filter { !it.key.startsWith("cap") }.map { r ->
        DynamicTest.dynamicTest(r.key) {
            val slots = stepCount(r) + 1
            assertTrue(slots > 0 && (slots and (slots - 1)) == 0L, "${r.key}: 칸 수 $slots")
        }
    }

    @TestFactory
    fun `기본값과 프리셋 값이 모두 간격 위에 있다`(): List<DynamicTest> {
        val values = ROWS.filter { it.default != null }.map { it.key to it.default!! } +
            BL.entries.map { it.key to it.value } + KO.entries.map { it.key to it.value }
        return values.map { (k, v) ->
            DynamicTest.dynamicTest("$k=$v") {
                val p = TunableParam.ofKey(k)!!
                assertTrue(p.isOnGrid(v), "isOnGrid")
                assertTrue(v >= p.min - 1e-9 && v <= p.max + 1e-9, "범위")
            }
        }
    }

    @Test
    fun `스펙 기본값이 코드의 내장 기본값과 같다`() {
        val c = BASE
        val lanesAsc = c.laneChange.theta1ByKmh.sortedBy { it.first }
        val code = mapOf(
            "penaltyAccel" to c.penalty.accel, "penaltyAccelSevere" to c.penalty.accelSevere,
            "penaltyBrake" to c.penalty.brake, "penaltyBrakeSevere" to c.penalty.brakeSevere,
            "penaltyLaneNoSignal" to c.penalty.laneNoSignal, "penaltyLaneWrongSignal" to c.penalty.laneWrongSignal,
            "penaltyWeaving" to c.penalty.weaving, "penaltySpeedingPer60s" to c.penalty.speedingPer60s,
            "capAccel" to c.penalty.accelCap, "capBrake" to c.penalty.brakeCap, "capLane" to c.penalty.laneCap,
            "capWeaving" to c.penalty.weavingCap, "capSpeeding" to c.penalty.speedingCap,
            "feedbackSameTypeMinSec" to c.feedback.sameTypeMinMs / 1000f, "feedbackAnyTypeMinSec" to c.feedback.anyTypeMinMs / 1000f,
            "feedbackMaxPerType" to c.feedback.maxPerTypePerSession.toFloat(),
            "brakeStableSec" to c.feedback.brakeStableMs / 1000f, "brakeStableAccel" to c.feedback.brakeStableAccel,
            "accelDelaySec" to c.feedback.accelDelayMs / 1000f, "sessionStartDelaySec" to c.feedback.sessionStartDelayMs / 1000f,
            "praiseFirstSignaled" to if (c.feedback.praiseFirstSignaled) 1f else 0f,
            "laneBand1Kmh" to lanesAsc[0].first.toFloat(), "laneBand2Kmh" to lanesAsc[1].first.toFloat(), "laneBand3Kmh" to lanesAsc[2].first.toFloat(),
            "laneTheta1Band1Deg" to lanesAsc[0].second, "laneTheta1Band2Deg" to lanesAsc[1].second, "laneTheta1Band3Deg" to lanesAsc[2].second,
            "steeringRatio" to c.laneChange.steeringRatio, "steeringInverted" to if (c.laneChange.steeringInverted) 1f else 0f,
            "signalMinOnSec" to c.laneChange.signalMinOnMs / 1000f, "sessionEndIdleSec" to c.sessionEndIdleMs / 1000f,
        )
        for (r in ROWS.filter { it.default != null }) {
            assertEquals(r.default!!, code.getValue(r.key).toDouble(), 1e-6, r.key)
        }
        assertEquals(3, lanesAsc.size)
    }

    @TestFactory
    fun `범위 안의 모든 간격 값을 받는다 (10진 표기 그대로)`(): List<DynamicTest> = ROWS.map { r ->
        DynamicTest.dynamicTest(r.key) {
            val p = TunableParam.ofKey(r.key)!!
            for (k in 0..stepCount(r)) {
                val x = gridValue(r, k)
                assertTrue(p.isOnGrid(x), "${r.key}=$x isOnGrid")
                assertEquals(k, p.stepIndex(x), "${r.key}=$x stepIndex")
                if (feasible(r.key, x)) ok(ctx(r.key, x))
            }
        }
    }

    @TestFactory
    fun `경계 밖 한 칸과 간격 밖 값은 거절`(): List<DynamicTest> = ROWS.flatMap { r ->
        val below = BigDecimal(r.min.toString()).subtract(BigDecimal(r.step.toString())).toDouble()
        val above = BigDecimal(r.max.toString()).add(BigDecimal(r.step.toString())).toDouble()
        val half = BigDecimal(r.min.toString()).add(BigDecimal(r.step.toString()).divide(BigDecimal(2))).toDouble()
        val nearMax = BigDecimal(r.max.toString()).subtract(BigDecimal(r.step.toString()).divide(BigDecimal(2))).toDouble()
        listOf(
            DynamicTest.dynamicTest("${r.key} min-step=$below") { bad(ctx(r.key, below)) },
            DynamicTest.dynamicTest("${r.key} max+step=$above") { bad(ctx(r.key, above)) },
            DynamicTest.dynamicTest("${r.key} 간격 밖 $half") {
                assertFalse(TunableParam.ofKey(r.key)!!.isOnGrid(half)); bad(ctx(r.key, half))
            },
            DynamicTest.dynamicTest("${r.key} 간격 밖 $nearMax") { bad(ctx(r.key, nearMax)) },
        )
    }

    @TestFactory
    fun `NaN·무한대는 예외 없이 거절`(): List<DynamicTest> = ROWS.flatMap { r ->
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).map { x ->
            DynamicTest.dynamicTest("${r.key}=$x") {
                val p = TunableParam.ofKey(r.key)!!
                // stepIndex 가 NaN 에서 roundToLong 예외를 던지면 안 된다 (함수 계약: 간격 밖이면 null)
                assertNull(p.stepIndex(x)); assertFalse(p.isOnGrid(x))
                // ctx 가 NaN 을 다른 값에 퍼뜨리지 않도록 그룹 나머지는 유효값 + 이 키만 x
                val m = ctx(r.key, r.min).toMutableMap().apply { put(r.key, x) }
                bad(m)
            }
        }
    }

    @Test
    fun `아주 큰 값·작은 값도 예외 없이 거절`() {
        for (x in listOf(1e300, -1e300, Double.MAX_VALUE, -Double.MAX_VALUE, Long.MAX_VALUE.toDouble(), 9.3e18)) {
            val p = TunableParam.PENALTY_WEAVING
            assertNull(p.stepIndex(x), "stepIndex($x)")
            bad(mapOf("penaltyWeaving" to x))
            bad(mapOf("sessionEndIdleSec" to x))
        }
    }

    @Test
    fun `간격에 아주 조금 어긋난 값 (0_01) 은 거절`() {
        bad(BL + ("brake1Kmhs" to 13.01))
        bad(mapOf("signalMinOnSec" to 0.71))
        bad(mapOf("steeringRatio" to 14.1))
        bad(mapOf("capAccel" to 30.5))
    }

    @Test
    fun `음의 0 은 0 으로 받는다`() {
        ok(mapOf("penaltyLaneNoSignal" to -0.0, "steeringInverted" to -0.0, "accelDelaySec" to -0.0))
        val c = EngineTuning.apply(BASE.copy(laneChange = BASE.laneChange.copy(steeringInverted = true)), mapOf("steeringInverted" to -0.0, "penaltyLaneNoSignal" to -0.0), 2)
        assertFalse(c.laneChange.steeringInverted)
        assertTrue(c.penalty.laneNoSignal == 0f)
    }

    @Test
    fun `스펙 기본값 전체와 두 프리셋 묶음을 받는다`() {
        ok(allDefaults(BL)); ok(allDefaults(KO))
        val a = EngineTuning.apply(BASE, allDefaults(BL), 1)
        assertEquals(norm(BASE.copy(paramsVersion = 1)), norm(a), "기본값을 그대로 보내면 내장 기본값과 같은 설정이어야")
        val k = EngineTuning.apply(BASE, allDefaults(KO), 1)
        assertEquals(norm(BASE.copy(thresholds = Presets.KOTSA, paramsVersion = 1)), norm(k))
    }

    // ───────────────────────── 2. 단위 ─────────────────────────

    @Test
    fun `km per h per s 를 m per s2 로 - 프리셋과 Float 비트까지 같다`() {
        val b = EngineTuning.apply(BASE, BL, 1).thresholds
        assertEquals(Presets.BLUELINK.brake1, b.brake1); assertEquals(Presets.BLUELINK.brake2, b.brake2)
        assertEquals(Presets.BLUELINK.accel1, b.accel1); assertEquals(Presets.BLUELINK.accel2, b.accel2)
        assertEquals(Presets.BLUELINK.brake1.toRawBits(), b.brake1.toRawBits())
        val k = EngineTuning.apply(BASE, KO, 1).thresholds
        assertEquals(Presets.KOTSA.accel1, k.accel1); assertEquals(Presets.KOTSA.accel2, k.accel2)
        assertEquals(Presets.KOTSA.brake1, k.brake1); assertEquals(Presets.KOTSA.brake2, k.brake2)
        // 일반 값: 9.0 km/h/s = 2.5 m/s²
        val n = EngineTuning.apply(BASE, BL + ("brake1Kmhs" to 9.0), 1).thresholds
        assertEquals(2.5f, n.brake1, 1e-5f)
        // 임계값은 양수로 저장 (EngineConfig.Thresholds 주석)
        assertTrue(n.brake1 > 0 && n.brake2 > 0)
    }

    @Test
    fun `brakeStableAccel 은 m per s2 그대로`() {
        val c = EngineTuning.apply(BASE, BL + ("brakeStableAccel" to -1.5), 1)
        assertEquals(-1.5f, c.feedback.brakeStableAccel, 1e-6f)
        assertEquals(-3.2f, EngineTuning.apply(BASE, BL + ("brakeStableAccel" to -3.2), 1).feedback.brakeStableAccel, 1e-6f)
    }

    @Test
    fun `초는 밀리초로 - 부동소수 오차 없이 반올림`() {
        val c = EngineTuning.apply(
            BASE,
            mapOf(
                "brakeStableSec" to 0.3, "accelDelaySec" to 6.3, "sessionStartDelaySec" to 2.5,
                "signalMinOnSec" to 0.7, "sessionEndIdleSec" to 150.0,
                "feedbackSameTypeMinSec" to 60.0, "feedbackAnyTypeMinSec" to 15.0,
            ),
            1,
        )
        assertEquals(300L, c.feedback.brakeStableMs)
        assertEquals(6300L, c.feedback.accelDelayMs)
        assertEquals(2500L, c.feedback.sessionStartDelayMs)
        assertEquals(700L, c.laneChange.signalMinOnMs)
        assertEquals(150_000L, c.sessionEndIdleMs)
        assertEquals(60_000L, c.feedback.sameTypeMinMs)
        assertEquals(15_000L, c.feedback.anyTypeMinMs)
        // 0.1 간격 전 구간을 훑는다: 0.1*k 의 10진 표기 → k*100 ms
        for (k in 0..63) {
            val s = BigDecimal("0.1").multiply(BigDecimal(k)).toDouble()
            assertEquals(k * 100L, EngineTuning.apply(BASE, mapOf("brakeStableSec" to s), 1).feedback.brakeStableMs, "brakeStableSec=$s")
        }
        for (k in 0..15) {
            val s = BigDecimal("0.1").add(BigDecimal("0.1").multiply(BigDecimal(k))).toDouble()
            assertEquals(100L + k * 100L, EngineTuning.apply(BASE, mapOf("signalMinOnSec" to s), 1).laneChange.signalMinOnMs, "signalMinOnSec=$s")
        }
    }

    @Test
    fun `켬·끔과 횟수`() {
        val on = EngineTuning.apply(BASE, mapOf("steeringInverted" to 1.0, "praiseFirstSignaled" to 0.0, "feedbackMaxPerType" to 15.0), 1)
        assertTrue(on.laneChange.steeringInverted)
        assertFalse(on.feedback.praiseFirstSignaled)
        assertEquals(15, on.feedback.maxPerTypePerSession)
        val off = EngineTuning.apply(BASE.copy(laneChange = BASE.laneChange.copy(steeringInverted = true)), mapOf("steeringInverted" to 0.0, "praiseFirstSignaled" to 1.0), 1)
        assertFalse(off.laneChange.steeringInverted)
        assertTrue(off.feedback.praiseFirstSignaled)
        bad(mapOf("steeringInverted" to 0.5)); bad(mapOf("steeringInverted" to 2.0)); bad(mapOf("praiseFirstSignaled" to -1.0))
    }

    @Test
    fun `감점표 값이 각 자리로 들어간다`() {
        val c = EngineTuning.apply(
            BASE,
            mapOf(
                "penaltyAccel" to 1.5, "penaltyAccelSevere" to 2.5, "penaltyBrake" to 3.5, "penaltyBrakeSevere" to 4.5,
                "penaltyLaneNoSignal" to 5.5, "penaltyLaneWrongSignal" to 6.5, "penaltyWeaving" to 7.5, "penaltySpeedingPer60s" to 8.5,
                "capAccel" to 11.0, "capBrake" to 12.0, "capLane" to 13.0, "capWeaving" to 14.0, "capSpeeding" to 15.0,
            ),
            1,
        )
        val p = c.penalty
        assertEquals(listOf(1.5f, 2.5f, 3.5f, 4.5f, 5.5f, 6.5f, 7.5f, 8.5f), listOf(p.accel, p.accelSevere, p.brake, p.brakeSevere, p.laneNoSignal, p.laneWrongSignal, p.weaving, p.speedingPer60s))
        assertEquals(listOf(11f, 12f, 13f, 14f, 15f), listOf(p.accelCap, p.brakeCap, p.laneCap, p.weavingCap, p.speedingCap))
        assertEquals(BASE.penalty.normalizeKm, p.normalizeKm, "정규화 거리는 열지 않는 수치")
    }

    @Test
    fun `차선 구간 - 구간 1 이 가장 낮은 속도`() {
        val m = mapOf(
            "laneBand1Kmh" to 40.0, "laneBand2Kmh" to 80.0, "laneBand3Kmh" to 120.0,
            "laneTheta1Band1Deg" to 30.0, "laneTheta1Band2Deg" to 20.0, "laneTheta1Band3Deg" to 8.0,
        )
        val lc = EngineTuning.apply(BASE, m, 1).laneChange
        assertEquals(setOf(40 to 30f, 80 to 20f, 120 to 8f), lc.theta1ByKmh.toSet())
        fun at(kmh: Float) = lc.theta1For(kmh / 3.6f)
        assertNull(at(35f), "구간 1 아래는 θ1 없음")
        assertEquals(30f, at(45f)!!, 1e-4f)
        assertEquals(30f, at(79f)!!, 1e-4f)
        assertEquals(20f, at(85f)!!, 1e-4f)
        assertEquals(8f, at(125f)!!, 1e-4f)
        assertEquals(8f, at(200f)!!, 1e-4f)
    }

    @Test
    fun `조향비는 θ1 과 θ0 모두에 적용`() {
        val lc = EngineTuning.apply(BASE, mapOf("steeringRatio" to 14.0), 1).laneChange
        assertEquals(14f, lc.steeringRatio)
        assertEquals(15f / 14f, lc.theta1For(20f)!!, 1e-5f)   // 72 km/h → 15°
        assertEquals(5f / 14f, lc.theta0Input, 1e-5f)
        assertEquals(BASE.laneChange.theta0, lc.theta0, "θ0 자체는 열지 않는 수치")
        // 구간과 조향비를 함께 보낸 경우
        val both = EngineTuning.apply(
            BASE,
            mapOf("steeringRatio" to 2.5, "laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
                "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 10.0),
            1,
        ).laneChange
        assertEquals(10f / 2.5f, both.theta1For(30f)!!, 1e-5f)   // 108 km/h
        assertEquals(5f / 2.5f, both.theta0Input, 1e-5f)
    }

    // ───────────────────────── 3. 묶음과 관계 규칙 ─────────────────────────

    private val fullGroups: Map<TuningGroup, Map<String, Double>> = mapOf(
        TH to BL,
        AP to mapOf("penaltyAccel" to 4.0, "penaltyAccelSevere" to 6.0),
        BP to mapOf("penaltyBrake" to 5.0, "penaltyBrakeSevere" to 7.5),
        FI to mapOf("feedbackSameTypeMinSec" to 90.0, "feedbackAnyTypeMinSec" to 20.0),
        LB to mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0, "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 10.0),
    )

    @TestFactory
    fun `묶음 일부만 보내면 거절`(): List<DynamicTest> = fullGroups.flatMap { (g, full) ->
        listOf(DynamicTest.dynamicTest("$g 전체는 통과") { ok(full) }) +
            full.keys.map { missing -> DynamicTest.dynamicTest("$g 에서 $missing 빠짐") { bad(full - missing) } } +
            full.keys.map { only -> DynamicTest.dynamicTest("$g 중 $only 하나만") { bad(mapOf(only to full.getValue(only))) } }
    }

    @TestFactory
    fun `묶음 밖 수치는 혼자 보낼 수 있다`(): List<DynamicTest> = ROWS.filter { it.group == null }.map { r ->
        DynamicTest.dynamicTest(r.key) { ok(mapOf(r.key to r.default!!)) }
    }

    @Test
    fun `A1 ≤ A2, B1 ≤ B2 - 같으면 통과, 넘으면 거절`() {
        ok(BL + mapOf("accel1Kmhs" to 15.0, "accel2Kmhs" to 15.0))
        bad(BL + mapOf("accel1Kmhs" to 15.25, "accel2Kmhs" to 15.0))
        ok(BL + mapOf("brake1Kmhs" to 19.5, "brake2Kmhs" to 19.5))
        bad(BL + mapOf("brake1Kmhs" to 19.75, "brake2Kmhs" to 19.5))
    }

    @Test
    fun `단위감점 1단계 ≤ 2단계`() {
        ok(mapOf("penaltyAccel" to 6.0, "penaltyAccelSevere" to 6.0))
        bad(mapOf("penaltyAccel" to 6.5, "penaltyAccelSevere" to 6.0))
        ok(mapOf("penaltyBrake" to 0.0, "penaltyBrakeSevere" to 0.0))
        bad(mapOf("penaltyBrake" to 7.5, "penaltyBrakeSevere" to 7.0))
    }

    @Test
    fun `F_all ≤ F_same`() {
        ok(mapOf("feedbackSameTypeMinSec" to 20.0, "feedbackAnyTypeMinSec" to 20.0))
        bad(mapOf("feedbackSameTypeMinSec" to 20.0, "feedbackAnyTypeMinSec" to 25.0))
        // F_same=0 은 범위 밖 (스펙 6-1: 10~640초)
        bad(mapOf("feedbackSameTypeMinSec" to 0.0, "feedbackAnyTypeMinSec" to 5.0))
    }

    @Test
    fun `안정 판정 가속도는 −B1 보다 커야 (m per s2) - 경계`() {
        // B1=11 km/h/s = 3.056 m/s² (안정 가속도 범위 −3.2~−0.1 안에서 경계가 생기는 B1)
        ok(BL + mapOf("brake1Kmhs" to 11.0, "brakeStableAccel" to -3.0))
        bad(BL + mapOf("brake1Kmhs" to 11.0, "brakeStableAccel" to -3.1))
        // B1=7.5 km/h/s = 2.083
        ok(KO + ("brakeStableAccel" to -2.0))
        bad(KO + ("brakeStableAccel" to -2.1))
        // 정확히 같은 경우: B1=9.0 km/h/s = 2.5 m/s². 규칙이 ">" 이므로 −2.5 는 거절 (스펙: "아니면 급제동 수준의 감속도 안정으로 본다")
        ok(BL + mapOf("brake1Kmhs" to 9.0, "brakeStableAccel" to -2.4))
        bad(BL + mapOf("brake1Kmhs" to 9.0, "brakeStableAccel" to -2.5))
        // B1=18 km/h/s = 5.0 m/s² 은 안정 가속도 하한(−3.2)보다 아래라 항상 통과
        ok(BL + mapOf("brake1Kmhs" to 18.0, "brakeStableAccel" to -3.2))
    }

    @Test
    fun `구간 속도 하한은 1·2·3 순으로 커야 (같으면 거절)`() {
        val th = mapOf("laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 10.0)
        ok(th + mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 35.0, "laneBand3Kmh" to 40.0))
        bad(th + mapOf("laneBand1Kmh" to 60.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0))
        bad(th + mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 90.0, "laneBand3Kmh" to 90.0))
        bad(th + mapOf("laneBand1Kmh" to 90.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 30.0))
        ok(th + mapOf("laneBand1Kmh" to 175.0, "laneBand2Kmh" to 180.0, "laneBand3Kmh" to 185.0))
    }

    @Test
    fun `θ1 은 빠른 구간일수록 같거나 작다`() {
        val b = mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0)
        ok(b + mapOf("laneTheta1Band1Deg" to 20.0, "laneTheta1Band2Deg" to 20.0, "laneTheta1Band3Deg" to 20.0))
        ok(b + mapOf("laneTheta1Band1Deg" to 69.0, "laneTheta1Band2Deg" to 5.5, "laneTheta1Band3Deg" to 5.5))
        bad(b + mapOf("laneTheta1Band1Deg" to 15.0, "laneTheta1Band2Deg" to 15.5, "laneTheta1Band3Deg" to 10.0))
        bad(b + mapOf("laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 15.5))
    }

    // ───────────────────────── 4. 전부 아니면 전무 ─────────────────────────

    @Test
    fun `값 하나만 틀려도 묶음 전체 거절`() {
        val good = allDefaults(BL)
        ok(good)
        bad(good + ("capLane" to 101.0))
        bad(good + ("signalMinOnSec" to 0.05))
        bad(good + ("brake1Kmhs" to 3.61))   // m/s² 값을 잘못 넣은 경우: 하한 5 아래
    }

    @Test
    fun `모르는 이름은 거절 (대소문자·오타 포함)`() {
        bad(mapOf("brake1Kmh" to 13.0))
        bad(mapOf("PenaltyWeaving" to 8.0))
        bad(mapOf("persistMs" to 300.0))           // 열지 않는 수치
        bad(mapOf("resampleMs" to 100.0))
        bad(mapOf("minValidKm" to 1.0))
        bad(allDefaults(BL) + ("unknownFutureParam" to 1.0))
    }

    @Test
    fun `빈 맵은 통과하고 base 를 그대로`() {
        ok(emptyMap())
        assertEquals(BASE, EngineTuning.apply(BASE, emptyMap(), 0))
    }

    @Test
    fun `빠진 값은 base 값 유지 (프리셋·시연 설정 포함)`() {
        val demo = BASE.copy(
            thresholds = Presets.KOTSA, sessionEndIdleMs = 10_000,
            laneChange = BASE.laneChange.copy(steeringRatio = 14f, steeringInverted = true),
            feedback = BASE.feedback.copy(variantsPerType = 5),
        )
        val c = EngineTuning.apply(demo, mapOf("penaltyWeaving" to 10.0), 3)
        assertEquals(10f, c.penalty.weaving)
        assertEquals(3, c.paramsVersion)
        assertEquals(demo, c.copy(penalty = demo.penalty, paramsVersion = demo.paramsVersion))
        assertEquals(Presets.KOTSA, c.thresholds, "임계값을 안 보내면 고른 프리셋 그대로")
    }

    // ───────────────────────── 5. 엔진 동작 (end-to-end) ─────────────────────────

    class Rec {
        val events = mutableListOf<DrivingEvent>()
        val life = mutableListOf<SessionLifecycle>()
        val cues = mutableListOf<FeedbackCue>()
        val sessions = mutableListOf<SessionResult>()
    }

    /** 100 ms 마다 모든 신호를 보내고 onTick 을 부르는 운전자 */
    class Drv(val e: SafetyScoreEngine) {
        var t = 0L; var v = 0f; var dist = 0f
        var pedal = false; var steer = 0f; var turn = TurnSignalState.OFF
        /** onTick 간격. Android 는 1초(스펙 6절). 기본은 100ms */
        var tickEvery = 100L
        fun begin() { e.onSignal(VehicleSignal.Gear(t, GearState.D)); push() }
        fun push() {
            e.onSignal(VehicleSignal.Speed(t, v))
            e.onSignal(VehicleSignal.BrakePedal(t, pedal))
            e.onSignal(VehicleSignal.SteeringAngle(t, steer))
            e.onSignal(VehicleSignal.TurnSignal(t, turn))
            e.onSignal(VehicleSignal.TripDistance(t, dist))
            if (t % tickEvery == 0L) e.onTick(t)
        }
        fun step(a: Float) { t += 100; v = (v + a * 0.1f).coerceAtLeast(0f); dist += v * 0.1f; push() }
        fun hold(ms: Long, a: Float = 0f) = repeat((ms / 100).toInt()) { step(a) }
        /** 2 m/s² 로 천천히 (급가속 A1 2.78 아래, 페달 없이 감속) */
        fun rampTo(target: Float, rate: Float = 2f) {
            while (v < target - 1e-3f) step(minOf(rate, (target - v) / 0.1f))
            while (v > target + 1e-3f) step(-minOf(rate, (v - target) / 0.1f))
        }
        fun startCruise(speed: Float = 20f, ms: Long = 5_000) { begin(); rampTo(speed); hold(ms) }
        fun stopAndEnd(): SessionResult? { rampTo(0f); hold(1_000); return e.endSessionNow(t) }
        /** 20 m/s 에서 2.5 m/s² 제동 2초 (블루링크 B1 3.61 미만, 공단 B1 2.08 초과) */
        fun moderateBrake() { pedal = true; hold(2_000, -2.5f); pedal = false; hold(6_000) }
        /** 5 m/s² 제동 2초 (블루링크도 잡힘, 2단계 5.42 미만) */
        fun hardBrake() { pedal = true; hold(2_000, -5f); pedal = false; hold(6_000) }
        fun harshAccel() { hold(1_500, 4f); hold(6_000, -1f) }
        /** 기준선 0° → d 방향 → 반대 → 복귀. turnFrom/turnMs 는 t0 기준 깜빡이 ON 구간 */
        fun laneChange(deg: Float, turnState: TurnSignalState = TurnSignalState.OFF, turnFromMs: Long = -2_000, turnMs: Long = 5_000, returnTo: Float = 0f) {
            steer = 0f
            val pre = 5_000L
            val onAt = pre + turnFromMs   // 시작부터 몇 ms 뒤 ON
            var elapsed = 0L
            fun tick() {
                if (turnState != TurnSignalState.OFF) turn = if (elapsed >= onAt && elapsed < onAt + turnMs) turnState else TurnSignalState.OFF
                step(0f); elapsed += 100
            }
            while (elapsed < pre) tick()
            steer = deg; repeat(10) { tick() }
            steer = -deg; repeat(10) { tick() }
            steer = returnTo; repeat(50) { tick() }
            turn = TurnSignalState.OFF; steer = 0f; hold(1_000)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun withEngine(cfg: EngineConfig = BASE, body: (DefaultSafetyScoreEngine, Rec, Drv) -> Unit) = runTest {
        val e = DefaultSafetyScoreEngine(cfg)
        val rec = Rec()
        val jobs = listOf(
            launch(UnconfinedTestDispatcher(testScheduler)) { e.events.collect { rec.events += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { e.lifecycle.collect { rec.life += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { e.feedback.collect { rec.cues += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { e.sessions.collect { rec.sessions += it } },
        )
        body(e, rec, Drv(e))
        jobs.forEach { it.cancel() }
    }

    private fun remote(values: Map<String, Double>, version: Int, base: EngineConfig = BASE) = EngineTuning.apply(base, values, version)
    private fun SessionResult.count(t: EventType) = events.count { it.type == t }
    private fun Rec.cueCount(t: CueType) = cues.count { it.type == t }

    @Test
    fun `내장 기본값 세션의 paramsVersion 은 0`() = withEngine { _, _, d ->
        d.startCruise(); val r = d.stopAndEnd()!!
        assertEquals(0, r.paramsVersion)
    }

    @Test
    fun `낮춘 B1 이 다음 세션부터 보통 제동을 잡는다 - 진행 중 세션은 그대로`() = withEngine { e, rec, d ->
        d.startCruise()
        assertTrue(rec.life.any { it is SessionLifecycle.Started }, "세션이 시작돼 있어야")
        e.updateConfig(remote(KO, 11))
        d.moderateBrake()
        val r1 = d.stopAndEnd()!!
        assertEquals(0, r1.paramsVersion, "진행 중 세션은 이전 설정")
        assertEquals(0, r1.count(EventType.HARSH_BRAKE), "블루링크 B1(3.61)로는 2.5 m/s² 제동이 안 잡혀야")

        d.startCruise(); d.moderateBrake()
        val r2 = d.stopAndEnd()!!
        assertEquals(11, r2.paramsVersion)
        assertEquals(1, r2.count(EventType.HARSH_BRAKE), "공단 B1(2.08)로는 잡혀야")
    }

    @Test
    fun `세션 시작 전 여러 번 받으면 마지막 것`() = withEngine { e, _, d ->
        e.updateConfig(remote(KO, 4))
        e.updateConfig(remote(BL, 5))
        d.startCruise(); d.moderateBrake()
        val r = d.stopAndEnd()!!
        assertEquals(5, r.paramsVersion)
        assertEquals(0, r.count(EventType.HARSH_BRAKE))
    }

    @Test
    fun `세션 시작 직후(Started 관측 뒤) 받은 설정은 이번 세션에 안 쓴다`() = withEngine { e, rec, d ->
        d.begin()
        while (rec.life.none { it is SessionLifecycle.Started }) d.step(2f)
        e.updateConfig(remote(KO, 8))
        d.rampTo(20f); d.hold(3_000); d.moderateBrake()
        val r = d.stopAndEnd()!!
        assertEquals(0, r.paramsVersion)
        assertEquals(0, r.count(EventType.HARSH_BRAKE))
    }

    @Test
    fun `정차 종료 직전·직후에 받은 설정은 다음 세션에 적용`() = withEngine { e, rec, d ->
        // 세션 1: 정차 종료 바로 앞 틱에 업데이트 (아직 세션 진행 중)
        d.startCruise(10f); d.rampTo(0f)
        while (rec.sessions.isEmpty()) {
            if (d.t % 1000 == 0L) e.updateConfig(remote(KO, 21))
            d.hold(100)
            assertTrue(d.t < 400_000, "180초 정차로 끝나야")
        }
        assertEquals(0, rec.sessions[0].paramsVersion, "종료 전에 받은 건 진행 중 세션에 적용 안 됨")
        // 종료 직후 같은 시각에 다시 업데이트
        e.updateConfig(remote(KO, 22))
        d.startCruise(); d.moderateBrake()
        val r2 = d.stopAndEnd()!!
        assertEquals(22, r2.paramsVersion)
        assertEquals(1, r2.count(EventType.HARSH_BRAKE))
    }

    @Test
    fun `endSessionNow 직후 받은 설정은 바로 다음 세션에 적용`() = withEngine { e, _, d ->
        d.startCruise()
        d.stopAndEnd()!!
        e.updateConfig(remote(KO, 31))
        d.startCruise(); d.moderateBrake()
        assertEquals(31, d.stopAndEnd()!!.paramsVersion)
    }

    @TestFactory
    fun `신호 처리 구조 수치가 다르면 updateConfig 거절`(): List<DynamicTest> = listOf(
        "resampleMs" to BASE.copy(resampleMs = 200),
        "ringBufferMs" to BASE.copy(ringBufferMs = 60_000),
        "smoothingSamples" to BASE.copy(smoothingSamples = 5),
        "accelWindowMs" to BASE.copy(accelWindowMs = 2_000),
    ).map { (name, cfg) ->
        DynamicTest.dynamicTest(name) {
            val e = DefaultSafetyScoreEngine(BASE)
            assertThrows<IllegalArgumentException> { e.updateConfig(cfg) }
            // 세션 중에도 같은 결과
            val d = Drv(e); d.startCruise()
            assertThrows<IllegalArgumentException> { e.updateConfig(cfg) }
        }
    }

    @Test
    fun `거절된 updateConfig 는 이전에 받아 둔 설정을 지우지 않는다`() = withEngine { e, _, d ->
        // 가정: 스펙 "전부 아니면 전무 … 이전 묶음을 유지" 를 엔진에도 적용
        e.updateConfig(remote(KO, 5))
        assertThrows<IllegalArgumentException> { e.updateConfig(remote(BL, 6).copy(resampleMs = 200)) }
        d.startCruise(); d.moderateBrake()
        assertEquals(5, d.stopAndEnd()!!.paramsVersion)
    }

    @Test
    fun `steeringInverted 가 SIGNALED 와 WRONG_SIGNAL 을 뒤집는다`() {
        // 가정: 입력 조향각은 AAOS 정의(왼쪽 음수). 양수 이탈 = 오른쪽 → RIGHT 깜빡이가 같은 방향
        fun run(inverted: Boolean): SessionResult? {
            var out: SessionResult? = null
            withEngine { e, _, d ->
                if (inverted) e.updateConfig(remote(mapOf("steeringInverted" to 1.0), 3))
                d.startCruise(20f, 3_000)
                d.laneChange(20f, TurnSignalState.RIGHT)
                out = d.stopAndEnd()
            }
            return out
        }
        val normal = run(false)!!
        val inv = run(true)!!
        assertEquals(1, normal.count(EventType.LANE_CHANGE_SIGNALED), "반전 끔: ${normal.events}")
        assertEquals(0, normal.count(EventType.LANE_CHANGE_WRONG_SIGNAL))
        assertEquals(1, inv.count(EventType.LANE_CHANGE_WRONG_SIGNAL), "반전 켬: ${inv.events}")
        assertEquals(0, inv.count(EventType.LANE_CHANGE_SIGNALED))
    }

    @Test
    fun `signalMinOnSec 이 매칭 기준을 바꾼다`() {
        fun run(cfg: Map<String, Double>?): SessionResult {
            var out: SessionResult? = null
            withEngine { e, _, d ->
                cfg?.let { e.updateConfig(remote(it, 2)) }
                d.startCruise(20f, 3_000)
                d.laneChange(20f, TurnSignalState.RIGHT, turnFromMs = -1_500, turnMs = 500)   // 0.5초만 ON
                out = d.stopAndEnd()
            }
            return out!!
        }
        val def = run(null)
        assertEquals(1, def.count(EventType.LANE_CHANGE_NO_SIGNAL), "기본 1.0초: 0.5초 ON 은 NO_SIGNAL. ${def.events}")
        val short = run(mapOf("signalMinOnSec" to 0.3))
        assertEquals(1, short.count(EventType.LANE_CHANGE_SIGNALED), "0.3초: 0.5초 ON 은 SIGNALED. ${short.events}")
    }

    @Test
    fun `steeringRatio 는 θ1·θ0 모두 나눈다 (작은 입력 조향각으로 검출)`() {
        fun run(ratio: Double?, returnTo: Float): SessionResult {
            var out: SessionResult? = null
            withEngine { e, _, d ->
                ratio?.let { e.updateConfig(remote(mapOf("steeringRatio" to it), 6)) }
                d.startCruise(20f, 3_000)
                d.laneChange(2f, returnTo = returnTo)
                out = d.stopAndEnd()
            }
            return out!!
        }
        val lane = setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_SIGNALED, EventType.LANE_CHANGE_WRONG_SIGNAL)
        assertEquals(0, run(null, 0f).events.count { it.type in lane }, "조향비 1: ±2° 는 θ1(15°) 미만")
        assertEquals(1, run(14.0, 0f).count(EventType.LANE_CHANGE_NO_SIGNAL), "조향비 14: θ1=1.07°")
        // θ0/14 = 0.357°. 0.5° 로 돌아오면 복귀가 아니다 → 취소 (θ0 를 안 나누면 5° 기준으로 복귀 판정돼 버림)
        assertEquals(0, run(14.0, 0.5f).events.count { it.type in lane }, "θ0 도 조향비로 나눠야")
    }

    @Test
    fun `θ1 구간을 바꾸면 같은 조향이 다르게 판정된다`() {
        fun run(values: Map<String, Double>?): Int {
            var n = 0
            withEngine { e, _, d ->
                values?.let { e.updateConfig(remote(it, 3)) }
                d.startCruise(20f, 3_000)            // 72 km/h
                d.laneChange(12f)
                n = d.stopAndEnd()!!.events.count { it.type == EventType.LANE_CHANGE_NO_SIGNAL }
            }
            return n
        }
        assertEquals(0, run(null), "기본: 72 km/h θ1=15° > 12°")
        val m = mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
            "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 10.0, "laneTheta1Band3Deg" to 10.0)
        assertEquals(1, run(m), "구간 2 θ1=10° < 12°")
        val m2 = m + ("laneBand2Kmh" to 75.0) + ("laneBand3Kmh" to 90.0)
        assertEquals(0, run(m2), "구간 2 하한을 75로: 72 km/h 는 구간 1(25°)")
    }

    @Test
    fun `F_same·F_all·N_max 가 발화 횟수를 바꾼다`() {
        fun run(values: Map<String, Double>?): Rec {
            var rec: Rec? = null
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(10f, 30_000)
                repeat(10) { d.harshAccel(); d.hold(27_500) }
                d.hold(5_000)
                d.stopAndEnd()
                rec = r
            }
            return rec!!
        }
        val def = run(null)
        assertEquals(10, def.events.count { it.type == EventType.HARSH_ACCEL }, "급가속 10회가 검출돼야 시나리오가 성립")
        assertEquals(3, def.cueCount(CueType.HARSH_ACCEL), "기본 F_same 90초 + N_max 3")
        assertEquals(10, run(mapOf("feedbackSameTypeMinSec" to 10.0, "feedbackAnyTypeMinSec" to 5.0, "feedbackMaxPerType" to 15.0)).cueCount(CueType.HARSH_ACCEL))
        assertEquals(5, run(mapOf("feedbackSameTypeMinSec" to 60.0, "feedbackAnyTypeMinSec" to 5.0, "feedbackMaxPerType" to 15.0)).cueCount(CueType.HARSH_ACCEL))
        assertEquals(2, run(mapOf("feedbackSameTypeMinSec" to 10.0, "feedbackAnyTypeMinSec" to 5.0, "feedbackMaxPerType" to 2.0)).cueCount(CueType.HARSH_ACCEL))
        val zero = run(mapOf("feedbackMaxPerType" to 0.0))
        assertEquals(0, zero.cueCount(CueType.HARSH_ACCEL), "N_max 0: 이벤트 발화 꺼짐")
        assertEquals(1, zero.cueCount(CueType.SESSION_START), "N_max 0 이어도 세션 시작 인사는 남는다")
    }

    @Test
    fun `F_all 이 다른 타입 연속 발화를 막는다`() {
        fun run(values: Map<String, Double>?): Rec {
            var rec: Rec? = null
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(25f, 30_000)
                d.pedal = true; d.hold(2_000, -5f); d.pedal = false   // 25 → 15 m/s
                d.hold(8_000)
                d.hold(1_500, 4f)                                       // 급가속
                d.hold(15_000)
                d.stopAndEnd()
                rec = r
            }
            return rec!!
        }
        val def = run(null)
        assertEquals(1, def.events.count { it.type == EventType.HARSH_BRAKE }); assertEquals(1, def.events.count { it.type == EventType.HARSH_ACCEL })
        assertEquals(1, def.cueCount(CueType.HARSH_BRAKE) + def.cueCount(CueType.HARSH_ACCEL), "기본 F_all 20초: 하나만. ${def.cues}")
        assertEquals(1, def.cueCount(CueType.HARSH_BRAKE), "우선순위·시간 모두 급제동")
        val fast = run(mapOf("feedbackSameTypeMinSec" to 90.0, "feedbackAnyTypeMinSec" to 5.0))
        assertEquals(1, fast.cueCount(CueType.HARSH_BRAKE)); assertEquals(1, fast.cueCount(CueType.HARSH_ACCEL), "F_all 5초: 둘 다. ${fast.cues}")
    }

    private fun brakeCue(values: Map<String, Double>?): FeedbackCue {
        var cue: FeedbackCue? = null
        withEngine { e, r, d ->
            values?.let { e.updateConfig(remote(it, 4)) }
            d.startCruise(25f, 30_000); d.hardBrake(); d.hold(10_000); d.stopAndEnd()
            cue = r.cues.single { it.type == CueType.HARSH_BRAKE }
        }
        return cue!!
    }

    @Test
    fun `brakeStableSec 이 급제동 발화 시각을 늦춘다`() {
        val def = brakeCue(null); val slow = brakeCue(mapOf("brakeStableSec" to 3.0))
        val dDef = def.atMs - def.sourceEvent!!.endMs; val dSlow = slow.atMs - slow.sourceEvent!!.endMs
        assertTrue(abs((dSlow - dDef) - 1_500) <= 100, "지연 차이 1.5초: def=$dDef slow=$dSlow")
    }

    @Test
    fun `brakeStableAccel 을 낮추면 더 빨리 안정으로 본다`() {
        val def = brakeCue(null); val early = brakeCue(BL + ("brakeStableAccel" to -3.2))
        assertTrue(early.atMs < def.atMs, "def=${def.atMs} early=${early.atMs}")
    }

    @Test
    fun `accelDelaySec 이 급가속 발화 지연을 바꾼다`() {
        fun cue(values: Map<String, Double>?): FeedbackCue {
            var c: FeedbackCue? = null
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(10f, 30_000); d.harshAccel(); d.hold(10_000); d.stopAndEnd()
                c = r.cues.single { it.type == CueType.HARSH_ACCEL }
            }
            return c!!
        }
        val def = cue(null); val slow = cue(mapOf("accelDelaySec" to 2.5))
        val dDef = def.atMs - def.sourceEvent!!.endMs; val dSlow = slow.atMs - slow.sourceEvent!!.endMs
        assertTrue(abs((dSlow - dDef) - 1_500) <= 100, "def=$dDef slow=$dSlow")
    }

    @Test
    fun `sessionStartDelaySec 이 인사 시각을 바꾼다`() {
        fun delay(values: Map<String, Double>?): Long {
            var x = 0L
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(10f, 15_000); d.stopAndEnd()
                val start = r.life.filterIsInstance<SessionLifecycle.Started>().first().startMs
                x = r.cues.single { it.type == CueType.SESSION_START }.atMs - start
            }
            return x
        }
        assertEquals(3_000L, delay(null))
        assertEquals(10_000L, delay(mapOf("sessionStartDelaySec" to 10.0)))
        assertEquals(0L, delay(mapOf("sessionStartDelaySec" to 0.0)))
    }

    @Test
    fun `praiseFirstSignaled 0 이면 첫 깜빡이 칭찬이 없다`() {
        fun run(values: Map<String, Double>?): Rec {
            var rec: Rec? = null
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(20f, 30_000)
                d.laneChange(20f, TurnSignalState.RIGHT)
                d.stopAndEnd(); rec = r
            }
            return rec!!
        }
        val on = run(null)
        assertEquals(1, on.events.count { it.type == EventType.LANE_CHANGE_SIGNALED }, "시나리오 성립: ${on.events}")
        assertEquals(1, on.cueCount(CueType.LANE_SIGNALED_FIRST))
        val off = run(mapOf("praiseFirstSignaled" to 0.0))
        assertEquals(1, off.events.count { it.type == EventType.LANE_CHANGE_SIGNALED }, "이벤트는 그대로 기록")
        assertEquals(0, off.cueCount(CueType.LANE_SIGNALED_FIRST))
        val nmax0 = run(mapOf("feedbackMaxPerType" to 0.0))
        assertEquals(0, nmax0.cueCount(CueType.LANE_SIGNALED_FIRST), "N_max 0 이면 첫 깜빡이 칭찬도 꺼진다")
    }

    @Test
    fun `sessionEndIdleSec 이 정차 종료 시간을 바꾼다`() {
        fun endAfterIdle(values: Map<String, Double>?, idleMs: Long): Pair<Int, Long?> {
            var res = 0 to (null as Long?)
            withEngine { e, r, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(10f, 10_000); d.rampTo(0f)
                val idleStart = d.t
                d.hold(idleMs)
                res = r.sessions.size to r.sessions.firstOrNull()?.let { it.endMs - idleStart }
            }
            return res
        }
        assertEquals(0, endAfterIdle(null, 150_000).first, "기본 180초: 150초 정차로는 안 끝남")
        val (n, endOffset) = endAfterIdle(mapOf("sessionEndIdleSec" to 120.0), 150_000)
        assertEquals(1, n, "120초로 바꾸면 끝남")
        assertTrue(abs(endOffset!!) <= 1_000, "종료 시각 = 정차 시작 시각 (차이 $endOffset ms)")
        assertEquals(1, endAfterIdle(null, 185_000).first)
    }

    @Test
    fun `진행 중 세션에 받은 정차 종료 시간은 그 세션에 안 쓴다`() = withEngine { e, r, d ->
        d.startCruise(10f, 10_000)
        e.updateConfig(remote(mapOf("sessionEndIdleSec" to 120.0), 9))
        d.rampTo(0f); d.hold(150_000)
        assertEquals(0, r.sessions.size, "진행 중 세션은 180초")
        d.hold(35_000)
        assertEquals(1, r.sessions.size)
        assertEquals(0, r.sessions[0].paramsVersion)
        // 다음 세션은 120초
        d.startCruise(10f, 10_000); d.rampTo(0f); d.hold(150_000)
        assertEquals(2, r.sessions.size)
        assertEquals(9, r.sessions[1].paramsVersion)
    }

    @Test
    fun `원격 감점표가 점수에 반영된다`() {
        fun score(values: Map<String, Double>?): SessionResult {
            var out: SessionResult? = null
            withEngine { e, _, d ->
                values?.let { e.updateConfig(remote(it, 4)) }
                d.startCruise(20f, 100_000)
                d.hardBrake()
                d.rampTo(20f); d.hold(100_000)
                out = d.stopAndEnd()
            }
            return out!!
        }
        val def = score(null)
        assertTrue(def.valid, "1km·3분 충족"); assertEquals(1, def.count(EventType.HARSH_BRAKE))
        assertEquals(95, def.score); assertEquals(95, def.brakeScore)
        val heavy = score(mapOf("penaltyBrake" to 10.0, "penaltyBrakeSevere" to 10.0))
        assertEquals(90, heavy.score); assertEquals(90, heavy.brakeScore)
        assertEquals(97, score(mapOf("capBrake" to 3.0)).score)
        assertEquals(100, score(mapOf("penaltyBrake" to 0.0, "penaltyBrakeSevere" to 0.0)).score, "단위감점 0 은 감점을 끈다")
    }

    // ───────────────────────── 7. 동시성 ─────────────────────────

    @Test
    fun `다른 스레드의 updateConfig - 예외 없음, 세션 고정, 마지막 것 적용`() {
        val e = DefaultSafetyScoreEngine(BASE)
        val errors = CopyOnWriteArrayList<Throwable>()
        val results = CopyOnWriteArrayList<SessionResult>()
        val done = AtomicBoolean(false)
        val lastSent = AtomicInteger(0)
        val updater = Thread {
            try {
                var i = 0
                while (!done.get()) {
                    i++
                    val cfg = if (i % 2 == 0) remote(KO, i) else remote(BL + ("brake1Kmhs" to 13.0), i)
                    e.updateConfig(cfg); lastSent.set(i)
                }
            } catch (t: Throwable) { errors += t }
        }
        val driver = Thread {
            try {
                val d = Drv(e)
                repeat(40) {
                    d.startCruise(20f, 2_000); d.moderateBrake()
                    d.stopAndEnd()?.let { results += it }
                }
            } catch (t: Throwable) { errors += t } finally { done.set(true) }
        }
        updater.start(); driver.start()
        driver.join(120_000); updater.join(10_000)
        assertTrue(errors.isEmpty(), "예외: $errors")
        assertEquals(40, results.size)
        val versions = results.map { it.paramsVersion }
        assertEquals(versions.sorted(), versions, "버전은 줄지 않아야 (나중 업데이트가 이긴다): $versions")
        // 세션 고정: 한 세션 안에서 설정이 섞이지 않았다면, 짝수 버전(공단)은 보통 제동 1회, 홀수(블루링크)는 0회
        for (r in results.filter { it.paramsVersion > 0 }) {
            val expected = if (r.paramsVersion % 2 == 0) 1 else 0
            assertEquals(expected, r.count(EventType.HARSH_BRAKE), "v${r.paramsVersion}: ${r.events}")
        }
        // 업데이트가 멈춘 뒤 다음 세션은 마지막으로 보낸 버전
        val d = Drv(e).apply { t = 100_000_000L }
        d.startCruise(20f, 2_000)
        assertEquals(lastSent.get(), d.stopAndEnd()!!.paramsVersion)
    }

    // ═════════════════════════ 2단계: 경계값·사이드 이펙트·회귀 ═════════════════════════

    @Nested
    inner class Stage2 {

        private val lane55 = mapOf(
            "laneBand1Kmh" to 30.0, "laneBand2Kmh" to 60.0, "laneBand3Kmh" to 90.0,
            "laneTheta1Band1Deg" to 5.5, "laneTheta1Band2Deg" to 5.5, "laneTheta1Band3Deg" to 5.5,
        )
        private val fastFeedback = mapOf("feedbackSameTypeMinSec" to 10.0, "feedbackAnyTypeMinSec" to 5.0)
        private val laneTypes = setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_SIGNALED, EventType.LANE_CHANGE_WRONG_SIGNAL)

        // ── 원격 값 처리 (주의사항 8·10·11·12·13) ──

        @Test
        fun `구간 하한 속도 정확히에서는 그 구간의 θ1 (스펙 4-3 하한 포함)`() {
            // 속도 신호는 m/s Float 로 온다. k km/h 에 가장 가까운 Float 은 k/3.6f
            val fails = mutableListOf<String>()
            for (k in 35..180 step 5) {
                val m = mapOf("laneBand1Kmh" to k - 5.0, "laneBand2Kmh" to k.toDouble(), "laneBand3Kmh" to k + 5.0,
                    "laneTheta1Band1Deg" to 30.0, "laneTheta1Band2Deg" to 20.0, "laneTheta1Band3Deg" to 10.0)
                val got = EngineTuning.apply(BASE, m, 1).laneChange.theta1For(k / 3.6f)
                if (got != 20f) fails += "$k km/h → θ1=$got (구간 2 는 20)"
            }
            assertEquals(emptyList<String>(), fails)
        }

        @Test
        fun `기본 구간 경계와 차선변경 판정 속도 (코드 경계 8_3 m per s)`() {
            val lc = BASE.laneChange
            assertEquals(25f, lc.theta1For(30f / 3.6f)); assertEquals(15f, lc.theta1For(60f / 3.6f)); assertEquals(10f, lc.theta1For(90f / 3.6f))
            // 8.3 m/s = 29.88 km/h: 판정 속도는 넘지만 구간 1(30 km/h) 아래라 θ1 없음 (스펙 4-3 표 문면과 일치)
            assertNull(lc.theta1For(8.3f))
        }

        @Test
        fun `원격 구간 목록 순서 - 기본값을 보내면 내장 목록과 순서까지 같다`() {
            val lc = EngineTuning.apply(BASE, allDefaults(BL), 1).laneChange
            assertEquals(BASE.laneChange, lc)
            val asc = lc.copy(theta1ByKmh = lc.theta1ByKmh.sortedBy { it.first })
            for (kmh in listOf(31f, 59f, 61f, 89f, 91f, 150f)) assertEquals(lc.theta1For(kmh / 3.6f), asc.theta1For(kmh / 3.6f), "$kmh")
        }

        @Test
        fun `간격 허용 오차 안의 값은 받아서 정확한 간격 값으로 바꾼다`() {
            val c = EngineTuning.apply(BASE, BL + ("brake1Kmhs" to 13.0000001), 1)   // 허용 0.25e-6 안
            assertEquals(Presets.BLUELINK.brake1.toRawBits(), c.thresholds.brake1.toRawBits())
            bad(BL + ("brake1Kmhs" to 13.000001))
            assertEquals(2_010_000L, EngineTuning.apply(BASE, mapOf("sessionEndIdleSec" to 2010.00001), 1).sessionEndIdleMs)
            assertEquals(120_000L, EngineTuning.apply(BASE, mapOf("sessionEndIdleSec" to 119.99999), 1).sessionEndIdleMs)
            assertTrue(EngineTuning.apply(BASE, mapOf("penaltyWeaving" to -1e-9), 1).penalty.weaving == 0f)
            bad(mapOf("sessionEndIdleSec" to 2010.001)); bad(mapOf("sessionEndIdleSec" to 119.999))
        }

        @Test
        fun `초 → ms 최댓값들`() {
            val c = EngineTuning.apply(
                BASE,
                mapOf("brakeStableSec" to 6.3, "accelDelaySec" to 6.3, "sessionStartDelaySec" to 31.5, "signalMinOnSec" to 1.6,
                    "sessionEndIdleSec" to 2010.0, "feedbackSameTypeMinSec" to 640.0, "feedbackAnyTypeMinSec" to 320.0),
                1,
            )
            assertEquals(listOf(6300L, 6300L, 31_500L, 1600L, 2_010_000L, 640_000L, 320_000L),
                listOf(c.feedback.brakeStableMs, c.feedback.accelDelayMs, c.feedback.sessionStartDelayMs, c.laneChange.signalMinOnMs,
                    c.sessionEndIdleMs, c.feedback.sameTypeMinMs, c.feedback.anyTypeMinMs))
        }

        @Test
        fun `관계 규칙은 받은 원시값이 아니라 간격 값으로 비교`() {
            ok(BL + mapOf("accel1Kmhs" to 15.0000002, "accel2Kmhs" to 15.0))
            ok(mapOf("feedbackSameTypeMinSec" to 20.0, "feedbackAnyTypeMinSec" to 20.000002))
            bad(mapOf("laneBand1Kmh" to 30.0, "laneBand2Kmh" to 30.000001, "laneBand3Kmh" to 90.0,
                "laneTheta1Band1Deg" to 25.0, "laneTheta1Band2Deg" to 15.0, "laneTheta1Band3Deg" to 10.0))
        }

        @Test
        fun `안정 가속도 대 B1 - 모든 간격 조합을 정확한 10진 계산과 대조`() {
            // 스펙: 안정 가속도 > −B1/3.6 (m/s²) ⇔ 3.6·s + B1 > 0 (BigDecimal)
            val b1Row = row("brake1Kmhs"); val sRow = row("brakeStableAccel")
            val mismatch = mutableListOf<String>()
            for (i in 0..stepCount(b1Row)) for (j in 0..stepCount(sRow)) {
                val b1 = gridValue(b1Row, i); val s = gridValue(sRow, j)
                val expected = BigDecimal(s.toString()).multiply(BigDecimal("3.6")).add(BigDecimal(b1.toString())).signum() > 0
                val m = mapOf("accel1Kmhs" to 10.0, "accel2Kmhs" to 15.0, "brake1Kmhs" to b1, "brake2Kmhs" to 36.75, "brakeStableAccel" to s)
                val got = EngineTuning.problems(m).isEmpty()
                if (got != expected) mismatch += "B1=$b1 s=$s 기대=$expected 실제=$got"
                if (got) {
                    // 받은 설정은 엔진 단위(Float)에서도 관계를 지켜야: 급제동 수준(a ≤ −brake1)이 안정(a > s)일 수 없음
                    val c = EngineTuning.apply(BASE, m, 1)
                    if (!(c.feedback.brakeStableAccel > -c.thresholds.brake1)) mismatch += "Float 관계 깨짐 B1=$b1 s=$s"
                }
            }
            assertEquals(emptyList<String>(), mismatch)
        }

        @Test
        fun `묶음 검사는 간격 오류와 별개 - 둘 다 보고`() {
            val p = EngineTuning.problems(mapOf("accel1Kmhs" to 10.1))
            assertTrue(p.size >= 2, "간격 오류와 묶음 누락 둘 다: $p")
            assertTrue(EngineTuning.problems(BL + ("accel1Kmhs" to 10.1)).isNotEmpty())
        }

        @Test
        fun `임계값 이름 - 원격이면 REMOTE, 안 보내면 프리셋 그대로`() {
            assertEquals(EngineTuning.REMOTE_THRESHOLDS_NAME, EngineTuning.apply(BASE, BL, 1).thresholds.name)
            val k = BASE.copy(thresholds = Presets.KOTSA)
            assertEquals(Presets.KOTSA, EngineTuning.apply(k, mapOf("capLane" to 20.0), 1).thresholds)
            assertEquals(Presets.KOTSA, EngineTuning.apply(k, mapOf("feedbackSameTypeMinSec" to 90.0, "feedbackAnyTypeMinSec" to 20.0), 1).thresholds)
        }

        @Test
        fun `variantsPerType 는 원격으로 열려 있지 않다`() {
            bad(mapOf("variantsPerType" to 3.0))
        }

        // ── 차선변경 (주의사항 6·7·9) ──

        @Test
        fun `θ0 복귀는 열린 부등호 - 정확히 θ0 이면 복귀 아님 (θ1 최소 5_5)`() {
            fun run(returnTo: Float): Int {
                var n = -1
                withEngine { e, _, d ->
                    e.updateConfig(remote(lane55, 2))
                    d.startCruise(20f, 3_000)
                    d.laneChange(6f, returnTo = returnTo)
                    n = d.stopAndEnd()!!.events.count { it.type in laneTypes }
                }
                return n
            }
            assertEquals(1, run(4.9f), "|θ−base| = 4.9 < θ0 5 → 복귀")
            assertEquals(0, run(5.0f), "스펙 4-3 복귀 조건 '< θ0': 5.0 은 복귀 아님")
        }

        @Test
        fun `기준선은 원시 조향값 - 조향비와 오프셋이 함께 있어도 검출`() {
            fun run(ratio: Double?): Int {
                var n = -1
                withEngine { e, _, d ->
                    ratio?.let { e.updateConfig(remote(mapOf("steeringRatio" to it), 2)) }
                    d.startCruise(20f, 3_000)
                    d.steer = 3f; d.hold(5_000)       // 기준선 3°
                    d.steer = 5f; d.hold(1_000)       // +2°
                    d.steer = 1f; d.hold(1_000)       // −2°
                    d.steer = 3f; d.hold(5_000)       // 복귀
                    n = d.stopAndEnd()!!.events.count { it.type in laneTypes }
                }
                return n
            }
            assertEquals(0, run(null))
            assertEquals(1, run(14.0), "θ1/14 = 1.07°, θ0/14 = 0.36° 를 원시 기준선 3° 에서 잰다")
        }

        @Test
        fun `steeringInverted - 왼쪽 조향에서도 대칭, 검출 자체는 바꾸지 않는다`() {
            fun run(inv: Boolean, deg: Float, turn: TurnSignalState): SessionResult {
                var out: SessionResult? = null
                withEngine { e, _, d ->
                    if (inv) e.updateConfig(remote(mapOf("steeringInverted" to 1.0), 3))
                    d.startCruise(20f, 3_000)
                    d.laneChange(deg, turn)
                    out = d.stopAndEnd()
                }
                return out!!
            }
            assertEquals(1, run(false, -20f, TurnSignalState.LEFT).count(EventType.LANE_CHANGE_SIGNALED))
            assertEquals(1, run(true, -20f, TurnSignalState.LEFT).count(EventType.LANE_CHANGE_WRONG_SIGNAL))
            val a = run(false, 20f, TurnSignalState.OFF).events.filter { it.type in laneTypes }
            val b = run(true, 20f, TurnSignalState.OFF).events.filter { it.type in laneTypes }
            assertEquals(1, a.size); assertEquals(1, b.size)
            assertEquals(EventType.LANE_CHANGE_NO_SIGNAL, b[0].type)
            assertEquals(a[0].startMs to a[0].endMs, b[0].startMs to b[0].endMs, "반전은 매칭에만: t0·복귀 시각이 같아야")
        }

        // ── 세션 교체 (주의사항 1·2·4) ──

        @Test
        fun `endSessionNow 직후 달리는 중이면 곧바로 새 세션 - 받아 둔 설정이 그 세션에 적용`() = withEngine { e, rec, d ->
            // 가정: 스펙 3-1 은 강제 종료 뒤 재시작을 말하지 않는다. 주의사항 4 의 동작을 회귀로 고정하고, 6-1 세션 고정만 판단한다.
            d.startCruise(20f, 3_000)
            val r1 = e.endSessionNow(d.t)!!
            e.updateConfig(remote(KO, 12))
            d.hold(500)
            val starts = rec.life.filterIsInstance<SessionLifecycle.Started>()
            assertEquals(2, starts.size)
            assertTrue(starts[1].startMs >= r1.endMs)
            d.moderateBrake()
            val r2 = d.stopAndEnd()!!
            assertEquals(12, r2.paramsVersion)
            assertEquals(1, r2.count(EventType.HARSH_BRAKE))
        }

        @Test
        fun `받은 설정은 다음 업데이트 전까지 계속 쓴다`() = withEngine { e, _, d ->
            e.updateConfig(remote(KO, 5))
            d.startCruise(); d.stopAndEnd()
            d.startCruise(); d.moderateBrake()
            val r = d.stopAndEnd()!!
            assertEquals(5, r.paramsVersion); assertEquals(1, r.count(EventType.HARSH_BRAKE))
        }

        @Test
        fun `N_max 횟수는 세션마다 새로, 세션 중 받은 N_max 는 다음 세션부터`() = withEngine { e, rec, d ->
            fun session(midUpdate: EngineConfig?): Int {
                val before = rec.cues.size
                val startedBefore = rec.life.count { it is SessionLifecycle.Started }
                d.begin()
                while (rec.life.count { it is SessionLifecycle.Started } == startedBefore) d.step(2f)
                midUpdate?.let { e.updateConfig(it) }
                d.rampTo(10f); d.hold(30_000)
                d.harshAccel(); d.hold(27_500); d.harshAccel(); d.hold(5_000)
                d.stopAndEnd()
                return rec.cues.drop(before).count { it.type == CueType.HARSH_ACCEL }
            }
            e.updateConfig(remote(fastFeedback + ("feedbackMaxPerType" to 1.0), 1))
            assertEquals(1, session(null))
            assertEquals(1, session(remote(fastFeedback + ("feedbackMaxPerType" to 0.0), 2)), "세션 중 받은 N_max 0 은 이번 세션에 안 쓴다")
            assertEquals(0, session(null))
            assertEquals(3, rec.cues.count { it.type == CueType.SESSION_START })
        }

        @Test
        fun `세션 중 받은 인사 지연은 이번 세션 인사에 안 쓴다`() = withEngine { e, rec, d ->
            d.begin()
            while (rec.life.none { it is SessionLifecycle.Started }) d.step(2f)
            e.updateConfig(remote(mapOf("sessionStartDelaySec" to 20.0), 2))
            d.rampTo(10f); d.hold(30_000)
            val start = rec.life.filterIsInstance<SessionLifecycle.Started>().first().startMs
            assertEquals(3_000L, rec.cues.single { it.type == CueType.SESSION_START }.atMs - start)
        }

        @Test
        fun `새 설정으로 시작한 세션의 거리는 그 세션 주행분만`() = withEngine { e, rec, d ->
            d.startCruise(20f, 60_000); d.stopAndEnd()
            e.updateConfig(remote(KO, 3))
            d.hold(10_000)
            d.begin()
            while (rec.life.count { it is SessionLifecycle.Started } < 2) d.step(2f)
            val distAtStart = d.dist
            d.rampTo(20f); d.hold(60_000)
            val r = d.stopAndEnd()!!
            assertEquals(3, r.paramsVersion)
            assertEquals((d.dist - distAtStart).toDouble(), r.distanceKm * 1000.0, 30.0)
        }

        // ── 발화 (주의사항 16, 4-5) ──

        @Test
        fun `N_max 는 타입마다 따로 센다, 문구는 돌려 쓴다`() {
            var rec: Rec? = null
            withEngine { e, r, d ->
                e.updateConfig(remote(fastFeedback + ("feedbackMaxPerType" to 1.0), 2))
                d.startCruise(20f, 30_000)
                repeat(2) { d.harshAccel(); d.hold(25_000); d.hardBrake(); d.rampTo(20f); d.hold(25_000) }
                d.stopAndEnd(); rec = r
            }
            val r1 = rec!!
            assertEquals(2, r1.events.count { it.type == EventType.HARSH_ACCEL }); assertEquals(2, r1.events.count { it.type == EventType.HARSH_BRAKE })
            assertEquals(1, r1.cueCount(CueType.HARSH_ACCEL)); assertEquals(1, r1.cueCount(CueType.HARSH_BRAKE))

            var rec2: Rec? = null
            withEngine { e, r, d ->
                e.updateConfig(remote(fastFeedback + ("feedbackMaxPerType" to 15.0), 2))
                d.startCruise(10f, 30_000)
                repeat(7) { d.harshAccel(); d.hold(27_500) }
                d.stopAndEnd(); rec2 = r
            }
            assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), rec2!!.cues.filter { it.type == CueType.HARSH_ACCEL }.map { it.variant })
        }

        @Test
        fun `brakeStableSec 0 과 최댓값 6_3`() {
            fun delay(v: Double?): Long {
                var x = 0L
                withEngine { e, r, d ->
                    v?.let { e.updateConfig(remote(mapOf("brakeStableSec" to it), 4)) }
                    d.startCruise(25f, 30_000); d.hardBrake(); d.hold(10_000); d.stopAndEnd()
                    val c = r.cues.single { it.type == CueType.HARSH_BRAKE }
                    x = c.atMs - c.sourceEvent!!.endMs
                }
                return x
            }
            val def = delay(null); val zero = delay(0.0); val max = delay(6.3)
            assertTrue(abs((def - zero) - 1_500) <= 100, "def=$def zero=$zero")
            assertTrue(abs((max - def) - 4_800) <= 100, "def=$def max=$max")
        }

        @Test
        fun `급가속 발화 지연이 길면 그 사이 급제동이 우선순위로 말해져야`() {
            // 스펙 4-5: F_all 안에서는 하나만, 우선순위 HARSH_BRAKE > HARSH_ACCEL.
            // accelDelaySec 6.3: 급가속 발화 시각(종료+6.3초)보다 급제동 발화 시각(안정 후)이 먼저 온다.
            var rec: Rec? = null
            withEngine { e, r, d ->
                e.updateConfig(remote(mapOf("accelDelaySec" to 6.3), 4))
                d.startCruise(20f, 30_000)
                d.hold(1_500, 4f)                                         // 급가속 20 → 26
                d.pedal = true; d.hold(2_000, -5f); d.pedal = false       // 바로 급제동 26 → 16
                d.hold(20_000); d.stopAndEnd(); rec = r
            }
            val r = rec!!
            assertEquals(1, r.events.count { it.type == EventType.HARSH_ACCEL }); assertEquals(1, r.events.count { it.type == EventType.HARSH_BRAKE })
            val spoken = r.cues.filter { it.type == CueType.HARSH_ACCEL || it.type == CueType.HARSH_BRAKE }
            assertEquals(1, spoken.size, "F_all 안에서는 하나만: $spoken")
            assertEquals(CueType.HARSH_BRAKE, spoken.single().type, "먼저 말할 차례가 오는 것이 급제동이고 우선순위도 높다: $spoken")
        }

        @Test
        fun `인사 시각 - 틱이 1초 간격이어도 시작 + 지연에서 1초 안 (스펙 7-1 S14 의 ±1초)`() {
            for (delaySec in listOf(2.5, 3.0, 0.5)) {
                withEngine { e, r, d ->
                    d.tickEvery = 1_000
                    e.updateConfig(remote(mapOf("sessionStartDelaySec" to delaySec), 2))
                    d.startCruise(10f, 15_000)
                    val start = r.life.filterIsInstance<SessionLifecycle.Started>().first().startMs
                    val at = r.cues.single { it.type == CueType.SESSION_START }.atMs
                    val want = start + (delaySec * 1000).toLong()
                    assertTrue(at >= want && at < want + 1_000, "delay=$delaySec start=$start at=$at")
                }
            }
        }

        // ── 동시성 (주의사항 3) ──

        @Test
        fun `다른 스레드에서 구조가 다른 설정은 항상 거절, 맞는 설정은 항상 통과`() {
            val e = DefaultSafetyScoreEngine(BASE)
            val errors = CopyOnWriteArrayList<Throwable>()
            val done = AtomicBoolean(false)
            val wrongAccepted = AtomicInteger(0); val rightRejected = AtomicInteger(0)
            val lastGood = AtomicInteger(0)
            val bads = listOf(BASE.copy(resampleMs = 200), BASE.copy(smoothingSamples = 1), BASE.copy(accelWindowMs = 500), BASE.copy(ringBufferMs = 10_000))
            val updater = Thread {
                try {
                    var i = 0
                    while (!done.get()) {
                        i++
                        if (i % 2 == 0) {
                            try { e.updateConfig(bads[(i / 2) % bads.size].copy(paramsVersion = -i)); wrongAccepted.incrementAndGet() } catch (_: IllegalArgumentException) {}
                        } else {
                            try { e.updateConfig(remote(KO, i)); lastGood.set(i) } catch (_: IllegalArgumentException) { rightRejected.incrementAndGet() }
                        }
                    }
                } catch (t: Throwable) { errors += t }
            }
            val results = CopyOnWriteArrayList<SessionResult>()
            val driver = Thread {
                try { val d = Drv(e); repeat(20) { d.startCruise(20f, 1_000); d.stopAndEnd()?.let { results += it } } }
                catch (t: Throwable) { errors += t } finally { done.set(true) }
            }
            updater.start(); driver.start(); driver.join(120_000); updater.join(10_000)
            assertTrue(errors.isEmpty(), "$errors")
            assertEquals(0, wrongAccepted.get()); assertEquals(0, rightRejected.get())
            assertTrue(results.all { it.paramsVersion >= 0 }, "구조가 다른 설정(음수 버전)이 적용되면 안 됨: ${results.map { it.paramsVersion }}")
            val d = Drv(e).apply { t = 100_000_000L }
            d.startCruise(20f, 1_000)
            assertEquals(lastGood.get(), d.stopAndEnd()!!.paramsVersion)
        }
    }
}

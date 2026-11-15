package engine

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 원격으로 조정할 수 있는 엔진 수치 (스펙 6-1). 여기 없는 수치는 원격으로 바꾸지 않는다.
 * 목록·범위·간격은 여기서만 정하고, 바꾸려면 스토어 업데이트가 필요하다.
 *
 * 값은 [key] 이름으로 JSON 에 실제 값으로 들어온다. 단위는 [unit] (엔진 내부 단위로의 변환은 [EngineTuning.apply]).
 * 허용 값 = min + step × k (k = 0..maxSteps). 그래서 max = min + step × maxSteps.
 * 상한(cap*)을 뺀 수치는 maxSteps 를 2^n − 1 로 잡아 두어, 필요하면 n 비트 비트필드로 보낼 수 있다(스펙 6-1 전송 형식).
 *
 * group 이 같은 수치는 서로 비교하는 관계 규칙이 있어 함께 보내야 한다(일부만 오면 거절).
 */
enum class TunableParam(
    val key: String,
    val unit: String,
    val min: Double,
    val step: Double,
    val maxSteps: Int,
    val group: TuningGroup? = null,
) {
    // 4-0-1 임계값. km/h/s 로 받는다 (공단·블루링크 기준 단위). 5 km/h/s ≈ 일상 가감속, 36.75 ≈ 1.04 g
    ACCEL1("accel1Kmhs", "km/h/s", 5.0, 0.25, 127, TuningGroup.THRESHOLDS),
    ACCEL2("accel2Kmhs", "km/h/s", 5.0, 0.25, 127, TuningGroup.THRESHOLDS),
    BRAKE1("brake1Kmhs", "km/h/s", 5.0, 0.25, 127, TuningGroup.THRESHOLDS),
    BRAKE2("brake2Kmhs", "km/h/s", 5.0, 0.25, 127, TuningGroup.THRESHOLDS),

    // 5-1 단위감점 (10km 당 1회). 0 이면 그 항목 감점 끔
    PENALTY_ACCEL("penaltyAccel", "점", 0.0, 0.5, 63, TuningGroup.ACCEL_PENALTY),
    PENALTY_ACCEL_SEVERE("penaltyAccelSevere", "점", 0.0, 0.5, 63, TuningGroup.ACCEL_PENALTY),
    PENALTY_BRAKE("penaltyBrake", "점", 0.0, 0.5, 63, TuningGroup.BRAKE_PENALTY),
    PENALTY_BRAKE_SEVERE("penaltyBrakeSevere", "점", 0.0, 0.5, 63, TuningGroup.BRAKE_PENALTY),
    PENALTY_LANE_NO_SIGNAL("penaltyLaneNoSignal", "점", 0.0, 0.5, 63),
    PENALTY_LANE_WRONG_SIGNAL("penaltyLaneWrongSignal", "점", 0.0, 0.5, 63),
    PENALTY_WEAVING("penaltyWeaving", "점", 0.0, 0.5, 63),
    PENALTY_SPEEDING("penaltySpeedingPer60s", "점", 0.0, 0.5, 63),

    // 5-1 상한. 세션 점수가 100점 만점이라 100 까지 (7비트 중 0~100)
    CAP_ACCEL("capAccel", "점", 0.0, 1.0, 100),
    CAP_BRAKE("capBrake", "점", 0.0, 1.0, 100),
    CAP_LANE("capLane", "점", 0.0, 1.0, 100),
    CAP_WEAVING("capWeaving", "점", 0.0, 1.0, 100),
    CAP_SPEEDING("capSpeeding", "점", 0.0, 1.0, 100),

    // 4-5 음성 발화
    /** F_all(5초 이상) ≤ F_same 이라 10초부터 */
    FEEDBACK_SAME_TYPE_MIN("feedbackSameTypeMinSec", "초", 10.0, 10.0, 63, TuningGroup.FEEDBACK_INTERVALS),
    /** 0 이면 클립이 겹치므로 5초부터 (문구는 2초 이내) */
    FEEDBACK_ANY_TYPE_MIN("feedbackAnyTypeMinSec", "초", 5.0, 5.0, 63, TuningGroup.FEEDBACK_INTERVALS),
    /** 모든 이벤트 타입에 같은 값. 0 이면 이벤트 발화 전부 끔(인사는 남는다) */
    FEEDBACK_MAX_PER_TYPE("feedbackMaxPerType", "회", 0.0, 1.0, 15),
    BRAKE_STABLE_TIME("brakeStableSec", "초", 0.0, 0.1, 63),
    /** 음수. 판정이 a > 이 값 이라 0 이면 정차 중(a = 0) 발화하지 않는다. 급제동 1단계보다 약해야 해서 임계값과 함께 보낸다 */
    BRAKE_STABLE_ACCEL("brakeStableAccel", "m/s²", -3.2, 0.1, 31, TuningGroup.THRESHOLDS),
    ACCEL_DELAY("accelDelaySec", "초", 0.0, 0.1, 63),
    SESSION_START_DELAY("sessionStartDelaySec", "초", 0.0, 0.5, 63),
    /** 0 = 끔, 1 = 켬 */
    PRAISE_FIRST_SIGNALED("praiseFirstSignaled", "0·1", 0.0, 1.0, 1),

    // 4-3 차선변경. 구간 1 이 가장 낮은 속도. 30 km/h 미만은 minSpeedMps(8.3 m/s) 에 막혀 뜻이 없다
    LANE_BAND1_KMH("laneBand1Kmh", "km/h", 30.0, 5.0, 31, TuningGroup.LANE_BANDS),
    LANE_BAND2_KMH("laneBand2Kmh", "km/h", 30.0, 5.0, 31, TuningGroup.LANE_BANDS),
    LANE_BAND3_KMH("laneBand3Kmh", "km/h", 30.0, 5.0, 31, TuningGroup.LANE_BANDS),
    /** 핸들 각 기준. θ0(5°) 이하이면 이탈 직후 복귀로 판정돼 차선변경 검출이 무너지므로 5.5° 부터 */
    LANE_THETA1_BAND1("laneTheta1Band1Deg", "°", 5.5, 0.5, 127, TuningGroup.LANE_BANDS),
    LANE_THETA1_BAND2("laneTheta1Band2Deg", "°", 5.5, 0.5, 127, TuningGroup.LANE_BANDS),
    LANE_THETA1_BAND3("laneTheta1Band3Deg", "°", 5.5, 0.5, 127, TuningGroup.LANE_BANDS),
    /** 핸들 각 ÷ 입력 조향각. 에뮬레이터 1.0, 실차 13~16 추정 */
    STEERING_RATIO("steeringRatio", "배", 1.0, 0.25, 127),
    /** 0 = 그대로, 1 = 조향각 부호 반대로 읽기 */
    STEERING_INVERTED("steeringInverted", "0·1", 0.0, 1.0, 1),
    /** 깜빡이 최소 유지 시간. 실차가 점멸 위상으로 보내면 1초보다 짧게 올 수 있다 */
    SIGNAL_MIN_ON("signalMinOnSec", "초", 0.1, 0.1, 15),

    // 3-1 세션. 2분 미만이면 신호 대기마다 세션이 끊긴다
    SESSION_END_IDLE("sessionEndIdleSec", "초", 120.0, 30.0, 63),
    ;

    val max: Double get() = min + step * maxSteps

    /** 간격 위의 값인지. 부동소수 오차는 간격의 백만분의 1 까지 허용한다 */
    fun isOnGrid(value: Double): Boolean = stepIndex(value) != null

    /** 값의 간격 번호 k. 간격 위가 아니거나 범위 밖이면 null */
    fun stepIndex(value: Double): Long? {
        if (value.isNaN() || value.isInfinite()) return null
        val k = ((value - min) / step).roundToLong()
        return if (k in 0..maxSteps && abs(value - (min + step * k)) <= step * GRID_TOLERANCE) k else null
    }

    /** 간격 번호로 다시 만든 값. 받은 값의 부동소수 오차를 없앤다 */
    fun snapped(value: Double): Double = min + step * (stepIndex(value) ?: error("$key: 간격 밖 값 $value"))

    companion object {
        private const val GRID_TOLERANCE = 1e-6
        private val byKey = entries.associateBy { it.key }
        fun ofKey(key: String): TunableParam? = byKey[key]
    }
}

/** 함께 보내야 하는 수치 묶음. 묶음 안에 관계 규칙이 있다 */
enum class TuningGroup { THRESHOLDS, ACCEL_PENALTY, BRAKE_PENALTY, FEEDBACK_INTERVALS, LANE_BANDS }

/**
 * 원격 수치 검사와 적용. 순수 함수.
 * 엔진에는 [SafetyScoreEngine.updateConfig] 로 넘긴다. 엔진은 다음 세션 시작 때 적용한다(세션 고정, 스펙 6-1).
 */
object EngineTuning {
    private const val MS_PER_S = 1000.0
    private const val ON = 1.0

    /** 어긋나는 항목 목록. 빈 목록이면 통과. 하나라도 있으면 전부 적용하지 않는다 */
    fun problems(values: Map<String, Double>): List<String> = buildList {
        val known = HashMap<TunableParam, Double>()
        for ((key, v) in values) {
            val p = TunableParam.ofKey(key)
            if (p == null) { add("엔진 수치 $key: 이 앱이 모르는 이름이다"); continue }
            if (!p.isOnGrid(v)) { add("엔진 수치 $key: ${p.min}~${p.max} ${p.unit}, 간격 ${p.step} 에 맞지 않는다: $v"); continue }
            known[p] = p.snapped(v)
        }
        for (g in TuningGroup.entries) {
            val members = TunableParam.entries.filter { it.group == g }
            val present = members.filter { it.key in values }
            if (present.isNotEmpty() && present.size != members.size)
                add("엔진 수치 묶음 $g: 함께 보내야 한다. 빠진 것: ${(members - present.toSet()).joinToString { it.key }}")
        }
        addAll(relationProblems(known))
    }

    private fun relationProblems(v: Map<TunableParam, Double>): List<String> = buildList {
        fun check(a: TunableParam, b: TunableParam, ok: (Double, Double) -> Boolean, rule: String) {
            val x = v[a] ?: return; val y = v[b] ?: return
            if (!ok(x, y)) add("엔진 수치: ${a.key}($x) 와 ${b.key}($y) 는 $rule")
        }
        val le: (Double, Double) -> Boolean = { x, y -> x <= y }
        val lt: (Double, Double) -> Boolean = { x, y -> x < y }
        check(TunableParam.ACCEL1, TunableParam.ACCEL2, le, "1단계 ≤ 2단계")
        check(TunableParam.BRAKE1, TunableParam.BRAKE2, le, "1단계 ≤ 2단계")
        check(TunableParam.PENALTY_ACCEL, TunableParam.PENALTY_ACCEL_SEVERE, le, "1단계 ≤ 2단계")
        check(TunableParam.PENALTY_BRAKE, TunableParam.PENALTY_BRAKE_SEVERE, le, "1단계 ≤ 2단계")
        check(TunableParam.FEEDBACK_ANY_TYPE_MIN, TunableParam.FEEDBACK_SAME_TYPE_MIN, le, "전체 간격 ≤ 같은 타입 간격")
        // 안정 판정 가속도는 급제동 1단계(음수로 본 값)보다 약해야 한다. 아니면 급제동 수준의 감속도 "안정"이 된다
        // 엔진과 같은 Float 계산으로 비교한다. Double 로 섞어 계산하면 경계(예: B1 9 km/h/s = 2.5 m/s², 안정 −2.5)가 통과해 버린다
        check(TunableParam.BRAKE_STABLE_ACCEL, TunableParam.BRAKE1, { s, b1 -> s.toFloat() > -(b1.toFloat() * KMH_PER_S_TO_MPS2) }, "안정 가속도 > −급제동 1단계(m/s²)")
        check(TunableParam.LANE_BAND1_KMH, TunableParam.LANE_BAND2_KMH, lt, "구간 속도가 커져야 한다")
        check(TunableParam.LANE_BAND2_KMH, TunableParam.LANE_BAND3_KMH, lt, "구간 속도가 커져야 한다")
        check(TunableParam.LANE_THETA1_BAND2, TunableParam.LANE_THETA1_BAND1, le, "빠른 구간의 θ1 ≤ 느린 구간의 θ1")
        check(TunableParam.LANE_THETA1_BAND3, TunableParam.LANE_THETA1_BAND2, le, "빠른 구간의 θ1 ≤ 느린 구간의 θ1")
    }

    /**
     * [base] 위에 원격 값을 덮어쓴 설정. 없는 값은 base 를 쓴다. [paramsVersion] 은 세션 기록에 남는다.
     * [problems] 가 비어 있지 않으면 IllegalArgumentException.
     */
    fun apply(base: EngineConfig, values: Map<String, Double>, paramsVersion: Int): EngineConfig {
        val bad = problems(values)
        require(bad.isEmpty()) { bad.joinToString("; ") }
        // θ1 구간은 낮은 속도부터(구간 1 = 0번) 자리를 고정해 두고 고친 뒤 마지막에 엔진 순서(내림차순)로 바꾼다.
        // 고치는 중에 정렬하면 구간 자리가 바뀌어 다음 값이 엉뚱한 구간에 들어간다
        val bands = base.laneChange.theta1ByKmh.sortedBy { it.first }.toMutableList()
        require(bands.size == LANE_BAND_COUNT) { "θ1 구간은 ${LANE_BAND_COUNT}개여야 한다: ${bands.size}" }
        var c = base
        for ((key, raw) in values) {
            val p = TunableParam.ofKey(key)!!
            c = set(c, p, p.snapped(raw), bands)
        }
        val touchedBands = values.keys.any { TunableParam.ofKey(it)?.group == TuningGroup.LANE_BANDS }
        val laneChange = if (touchedBands) c.laneChange.copy(theta1ByKmh = bands.sortedByDescending { it.first }) else c.laneChange
        return c.copy(laneChange = laneChange, paramsVersion = paramsVersion)
    }

    /**
     * 수치 하나를 설정에 넣는다. 모든 [TunableParam] 을 빠짐없이 다루는 when 이라,
     * 수치를 추가하고 여기를 빠뜨리면 컴파일 오류가 난다(값이 말없이 무시되지 않는다).
     * θ1 구간은 [bands](낮은 속도부터)에 넣고 마지막에 한꺼번에 바꾼다.
     */
    private fun set(c: EngineConfig, p: TunableParam, v: Double, bands: MutableList<Pair<Int, Float>>): EngineConfig {
        val f = v.toFloat()
        val ms = (v * MS_PER_S).roundToLong()
        val mps2 = f * KMH_PER_S_TO_MPS2
        val on = v >= ON
        fun th(t: Thresholds) = c.copy(thresholds = t.copy(name = REMOTE_THRESHOLDS_NAME))
        fun pen(t: PenaltyTable) = c.copy(penalty = t)
        fun fb(t: FeedbackConfig) = c.copy(feedback = t)
        fun lane(t: LaneChangeConfig) = c.copy(laneChange = t)
        fun bandKmh(i: Int): EngineConfig { bands[i] = v.roundToLong().toInt() to bands[i].second; return c }
        fun bandTheta(i: Int): EngineConfig { bands[i] = bands[i].first to f; return c }
        return when (p) {
            TunableParam.ACCEL1 -> th(c.thresholds.copy(accel1 = mps2))
            TunableParam.ACCEL2 -> th(c.thresholds.copy(accel2 = mps2))
            TunableParam.BRAKE1 -> th(c.thresholds.copy(brake1 = mps2))
            TunableParam.BRAKE2 -> th(c.thresholds.copy(brake2 = mps2))
            TunableParam.PENALTY_ACCEL -> pen(c.penalty.copy(accel = f))
            TunableParam.PENALTY_ACCEL_SEVERE -> pen(c.penalty.copy(accelSevere = f))
            TunableParam.PENALTY_BRAKE -> pen(c.penalty.copy(brake = f))
            TunableParam.PENALTY_BRAKE_SEVERE -> pen(c.penalty.copy(brakeSevere = f))
            TunableParam.PENALTY_LANE_NO_SIGNAL -> pen(c.penalty.copy(laneNoSignal = f))
            TunableParam.PENALTY_LANE_WRONG_SIGNAL -> pen(c.penalty.copy(laneWrongSignal = f))
            TunableParam.PENALTY_WEAVING -> pen(c.penalty.copy(weaving = f))
            TunableParam.PENALTY_SPEEDING -> pen(c.penalty.copy(speedingPer60s = f))
            TunableParam.CAP_ACCEL -> pen(c.penalty.copy(accelCap = f))
            TunableParam.CAP_BRAKE -> pen(c.penalty.copy(brakeCap = f))
            TunableParam.CAP_LANE -> pen(c.penalty.copy(laneCap = f))
            TunableParam.CAP_WEAVING -> pen(c.penalty.copy(weavingCap = f))
            TunableParam.CAP_SPEEDING -> pen(c.penalty.copy(speedingCap = f))
            TunableParam.FEEDBACK_SAME_TYPE_MIN -> fb(c.feedback.copy(sameTypeMinMs = ms))
            TunableParam.FEEDBACK_ANY_TYPE_MIN -> fb(c.feedback.copy(anyTypeMinMs = ms))
            TunableParam.FEEDBACK_MAX_PER_TYPE -> fb(c.feedback.copy(maxPerTypePerSession = v.roundToLong().toInt()))
            TunableParam.BRAKE_STABLE_TIME -> fb(c.feedback.copy(brakeStableMs = ms))
            TunableParam.BRAKE_STABLE_ACCEL -> fb(c.feedback.copy(brakeStableAccel = f))
            TunableParam.ACCEL_DELAY -> fb(c.feedback.copy(accelDelayMs = ms))
            TunableParam.SESSION_START_DELAY -> fb(c.feedback.copy(sessionStartDelayMs = ms))
            TunableParam.PRAISE_FIRST_SIGNALED -> fb(c.feedback.copy(praiseFirstSignaled = on))
            TunableParam.LANE_BAND1_KMH -> bandKmh(0)
            TunableParam.LANE_BAND2_KMH -> bandKmh(1)
            TunableParam.LANE_BAND3_KMH -> bandKmh(2)
            TunableParam.LANE_THETA1_BAND1 -> bandTheta(0)
            TunableParam.LANE_THETA1_BAND2 -> bandTheta(1)
            TunableParam.LANE_THETA1_BAND3 -> bandTheta(2)
            TunableParam.STEERING_RATIO -> lane(c.laneChange.copy(steeringRatio = f))
            TunableParam.STEERING_INVERTED -> lane(c.laneChange.copy(steeringInverted = on))
            TunableParam.SIGNAL_MIN_ON -> lane(c.laneChange.copy(signalMinOnMs = ms))
            TunableParam.SESSION_END_IDLE -> c.copy(sessionEndIdleMs = ms)
        }
    }

    /** θ1 구간 수. 스펙 6-1: 구간은 3개로 고정 */
    private const val LANE_BAND_COUNT = 3

    /** 원격 임계값을 쓸 때 Thresholds.name */
    const val REMOTE_THRESHOLDS_NAME = "REMOTE"
}

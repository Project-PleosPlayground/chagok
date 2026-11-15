package engine

/** 심각도 처리 방식 (스펙 4-0-1 후보 a/b/c). */
enum class SeverityMode {
    /** (a) 단계 없음. severity 는 항상 1. */
    NONE,
    /** (b) 2단계 + 감점 가중. */
    WEIGHTED,
    /** (c) 2단계 기록만. 감점은 단일. 음성 톤·리포트에만 쓴다. 11/2 팀 추천. */
    RECORD_ONLY,
}

/** 급가속·급제동 1·2단계 임계값 (m/s²). 양수로 적는다. */
data class Thresholds(
    val name: String,
    val accel1: Float, val accel2: Float,
    val brake1: Float, val brake2: Float,
)

/** km/h/s → m/s². 프리셋과 원격 값(스펙 6-1)이 같은 계산을 써야 같은 값이 Float 비트까지 같다 */
const val KMH_PER_S_TO_MPS2 = 1f / 3.6f

/** 팀 결정 전까지 두 후보를 코드에 둔다 (스펙 8절 모순 해소). 2단계 경계 = 1단계 × 1.5 (후보 b 가안). */
object Presets {
    private const val KMH_PER_S = KMH_PER_S_TO_MPS2
    /** 한국교통안전공단 DTG 위험운전행동: 급가속 11 km/h/s, 급감속 7.5 km/h/s. */
    val KOTSA = Thresholds("KOTSA", 11f * KMH_PER_S, 11f * 1.5f * KMH_PER_S, 7.5f * KMH_PER_S, 7.5f * 1.5f * KMH_PER_S)
    /** 현대 블루링크 안전운전 점수: 급가속 10 km/h/s, 급감속 13 km/h/s. */
    val BLUELINK = Thresholds("BLUELINK", 10f * KMH_PER_S, 10f * 1.5f * KMH_PER_S, 13f * KMH_PER_S, 13f * 1.5f * KMH_PER_S)
}

/** 차선변경 추정 (4-3a). 각도 단위는 SteeringAngle 과 같아야 한다 (핸들 각/앞바퀴 각 미확정 → 전부 config). */
data class LaneChangeConfig(
    val minSpeedMps: Float = 8.3f,
    /** 속도(km/h) 하한 → θ1(도). 내림차순 탐색. 스펙 가안: 30→25°, 60→15°, 90→10° (핸들 각 기준 수치). */
    val theta1ByKmh: List<Pair<Int, Float>> = listOf(90 to 10f, 60 to 15f, 30 to 25f),
    val theta0: Float = 5f,
    val baselineWindowMs: Long = 3_000,
    val curveHoldMs: Long = 3_000,
    val stage2WithinMs: Long = 5_000,
    val returnWithinMs: Long = 3_000,
    val cooldownMs: Long = 2_000,
    val signalWindowBeforeMs: Long = 3_000,
    val signalWindowAfterMs: Long = 500,
    val signalMinOnMs: Long = 1_000,
    /**
     * 조향비 (핸들 각 ÷ 입력 조향각). θ1·θ0 은 핸들 각 기준으로 적고, 비교할 때 이 값으로 나눈다.
     * 에뮬레이터는 핸들 각을 그대로 주므로 1. 실차는 앞바퀴 각이면 13~16 추정 [확인필요] (스펙 4-3).
     */
    val steeringRatio: Float = 1f,
    /**
     * 조향각 부호를 뒤집어 읽는지. AAOS 정의는 왼쪽 음수. 에뮬레이터·실차 부호가 반대로 오면 true [확인필요] (스펙 9-2).
     * 깜빡이 방향 매칭(4-3b)에만 영향을 준다.
     */
    val steeringInverted: Boolean = false,
) {
    /** 이 속도의 θ1 을 입력 조향각 단위로. 해당 구간이 없으면 null */
    fun theta1For(speedMps: Float): Float? {
        val kmh = speedMps * 3.6f
        // m/s ↔ km/h 왕복의 Float 오차(예: 125 km/h → 124.99999)로 구간 하한에서 아래 구간이 고르지 않게 한다
        return theta1ByKmh.sortedByDescending { it.first }.firstOrNull { kmh + KMH_ROUNDING_TOLERANCE >= it.first }?.second?.div(steeringRatio)
    }

    private companion object {
        /** km/h 비교의 부동소수 허용 오차. 속도 판정에 영향이 없는 크기 */
        const val KMH_ROUNDING_TOLERANCE = 1e-3f
    }

    /** θ0 을 입력 조향각 단위로 */
    val theta0Input: Float get() = theta0 / steeringRatio
}

/** 4-5 발화 규칙. 값은 기획이 정한다. */
data class FeedbackConfig(
    val sameTypeMinMs: Long = 90_000,     // F_same
    val anyTypeMinMs: Long = 20_000,      // F_all
    val maxPerTypePerSession: Int = 3,    // N_max
    val brakeStableMs: Long = 1_500,
    val brakeStableAccel: Float = -1.0f,  // a > -1.0 m/s² 가 brakeStableMs 유지되면 발화
    val accelDelayMs: Long = 1_000,
    val sessionStartDelayMs: Long = 3_000,
    val variantsPerType: Int = 3,
    val praiseFirstSignaled: Boolean = true,
)

/** 5-1 감점표. 10km 당 1회 기준. */
data class PenaltyTable(
    val accel: Float = 4f, val accelSevere: Float = 6f, val accelCap: Float = 30f,
    val brake: Float = 5f, val brakeSevere: Float = 7.5f, val brakeCap: Float = 35f,
    val laneNoSignal: Float = 6f, val laneWrongSignal: Float = 6f, val laneCap: Float = 30f,
    val weaving: Float = 8f, val weavingCap: Float = 16f,
    val speedingPer60s: Float = 5f, val speedingCap: Float = 25f,
    val normalizeKm: Float = 10f,
)

/**
 * 4·5절 수치. 임계값(thresholds)과 severityMode 는 기본값이 없다. 팀 결정 전이라는 사실을 코드에서도 드러내기 위함.
 */
data class EngineConfig(
    val thresholds: Thresholds,
    val severityMode: SeverityMode,
    val resampleMs: Long = 100,
    val smoothingSamples: Int = 3,
    val accelWindowMs: Long = 1_000,
    val persistMs: Long = 300,
    val cooldownMs: Long = 3_000,
    val brakeMinEndSpeedMps: Float = 2.0f,
    val staleAfterMs: Long = 5_000,
    val staleMinSpeedMps: Float = 0.5f,
    val resumeExcludeMs: Long = 1_000,
    val reverseExitHoldMs: Long = 2_000,
    val ringBufferMs: Long = 30_000,
    // 세션 (3-1)
    val sessionStartSpeedMps: Float = 1.0f,
    val sessionStartHoldNoGearMs: Long = 5_000,
    val sessionIdleSpeedMps: Float = 0.3f,
    val sessionEndIdleMs: Long = 3 * 60_000,
    val minValidKm: Float = 1f,
    val minValidMs: Long = 3 * 60_000,
    val laneChange: LaneChangeConfig = LaneChangeConfig(),
    val feedback: FeedbackConfig = FeedbackConfig(),
    val penalty: PenaltyTable = PenaltyTable(),
    /** 원격 조정 수치의 버전 (스펙 6-1). 그 값이 들어 있던 콘텐츠 묶음의 contentVersion. 내장 기본값이면 0. SessionResult 에 그대로 남는다 */
    val paramsVersion: Int = EngineConfig.BUILT_IN_PARAMS_VERSION,
) {
    companion object {
        /** 원격 수치 없이 앱 내장 기본값만 쓸 때의 paramsVersion */
        const val BUILT_IN_PARAMS_VERSION = 0
    }

    val persistSamples: Int get() = ((persistMs + resampleMs - 1) / resampleMs).toInt().coerceAtLeast(1)
    val accelWindowSamples: Int get() = (accelWindowMs / resampleMs).toInt().coerceAtLeast(1)
}

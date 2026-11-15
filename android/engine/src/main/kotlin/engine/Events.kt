package engine

enum class EventType {
    HARSH_ACCEL, HARSH_BRAKE,
    LANE_CHANGE_SIGNALED, LANE_CHANGE_NO_SIGNAL, LANE_CHANGE_WRONG_SIGNAL,
    WEAVING_EST, SPEEDING            // POC 제외. 타입만 둔다.
}

data class DrivingEvent(
    val type: EventType,
    /** 1 또는 2. 차선변경 계열은 항상 1. SeverityMode.NONE 이면 항상 1. */
    val severity: Int,
    val startMs: Long,
    val endMs: Long,
    /** 이벤트 시작 시 속도 (m/s). 급가속·급제동은 판정 구간의 시작 속도 v(t-1s). */
    val speedMps: Float,
    /** HARSH_ACCEL 전용. 시작 속도 < 1.0 m/s. 감점에는 영향 없음. */
    val fromStop: Boolean = false,
    /** 디버그용. "max a=-2.4m/s², pedal=true" */
    val detail: String = "",
)

data class SessionResult(
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Float,
    /** SIGNALED 포함 (미션 분모용). */
    val events: List<DrivingEvent>,
    /** 0~100, round_half_up. valid=false 면 0 (점수 미산출). */
    val score: Int,
    val accelScore: Int,
    val brakeScore: Int,
    val mannerScore: Int,
    /** 최소 유효 세션(1km·3분) 충족 여부. */
    val valid: Boolean,
    /** 이 세션에 쓴 원격 조정 수치의 버전 ([EngineConfig.paramsVersion]). 내장 기본값이면 0 */
    val paramsVersion: Int = EngineConfig.BUILT_IN_PARAMS_VERSION,
)

sealed interface SessionLifecycle {
    data class Started(val startMs: Long) : SessionLifecycle
    /** 최소 유효 세션 충족 순간. 그때까지의 이벤트 전부 포함. */
    data class Validated(val atMs: Long, val eventsSoFar: List<DrivingEvent>) : SessionLifecycle
    data class Ended(val result: SessionResult) : SessionLifecycle
}

/** [LOW_BATTERY] 는 엔진이 아니라 앱이 배터리 잔량을 보고 낸다(주행 중 잔량이 20%·10% 아래로, 11/15) */
enum class CueType { SESSION_START, HARSH_BRAKE, HARSH_ACCEL, LANE_NO_SIGNAL, LANE_SIGNALED_FIRST, MILESTONE, LOW_BATTERY }

data class FeedbackCue(
    val type: CueType,
    /** 발화해야 할 시각. 급제동은 안정 후, 급가속은 종료 1초 후 등 지연이 반영된 값. */
    val atMs: Long,
    /** 같은 타입 문구 중 몇 번째 (돌려쓰기용, 0부터). */
    val variant: Int,
    val sourceEvent: DrivingEvent?,
)

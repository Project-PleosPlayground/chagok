package engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** 5-1 세션 점수, 5-2 세부 점수. 순수 함수. */
object Scoring {
    data class Breakdown(
        val accelPenalty: Float, val brakePenalty: Float, val mannerPenalty: Float, val speedingPenalty: Float,
    ) {
        val total: Float get() = accelPenalty + brakePenalty + mannerPenalty + speedingPenalty
    }

    /** 소수 첫째 자리에서 반올림, .5 는 올림. */
    fun roundHalfUp(x: Float): Int = floor(x.toDouble() + 0.5).toInt()

    fun breakdown(events: List<DrivingEvent>, distanceKm: Float, cfg: EngineConfig): Breakdown {
        val p = cfg.penalty
        val d = max(distanceKm, p.normalizeKm)
        val k = p.normalizeKm / d                          // 정규화 계수 (10km 미만 세션은 10km 로 간주)
        val weighted = cfg.severityMode == SeverityMode.WEIGHTED

        fun unit(type: EventType, sev: Int): Float = when (type) {
            EventType.HARSH_ACCEL -> if (weighted && sev >= 2) p.accelSevere else p.accel
            EventType.HARSH_BRAKE -> if (weighted && sev >= 2) p.brakeSevere else p.brake
            EventType.LANE_CHANGE_NO_SIGNAL -> p.laneNoSignal
            EventType.LANE_CHANGE_WRONG_SIGNAL -> p.laneWrongSignal
            EventType.WEAVING_EST -> p.weaving
            EventType.LANE_CHANGE_SIGNALED, EventType.SPEEDING -> 0f
        }

        fun sum(vararg types: EventType) = events.filter { it.type in types }.sumOf { (unit(it.type, it.severity) * k).toDouble() }.toFloat()

        val accel = min(sum(EventType.HARSH_ACCEL), p.accelCap)
        val brake = min(sum(EventType.HARSH_BRAKE), p.brakeCap)
        val lane = min(sum(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL), p.laneCap)
        val weaving = min(sum(EventType.WEAVING_EST), p.weavingCap)
        return Breakdown(accel, brake, lane + weaving, 0f)
    }

    fun sessionScore(b: Breakdown): Int = roundHalfUp(max(0f, 100f - b.total))
    fun accelScore(b: Breakdown): Int = roundHalfUp(100f - b.accelPenalty)
    fun brakeScore(b: Breakdown): Int = roundHalfUp(100f - b.brakePenalty)
    fun mannerScore(b: Breakdown): Int = roundHalfUp(100f - b.mannerPenalty)
}

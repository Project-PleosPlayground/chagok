package core.report

import core.Time
import core.session.SessionRecord
import core.session.SessionStore
import engine.EventType
import engine.Scoring

/**
 * 통계 탭용 집계. 전부 SessionStore 에서 읽어 계산하는 순수 함수.
 * 비율 정의(가안): "10km 당 횟수" = 횟수 × 10 / 거리km. 깜빡이 매칭률 = SIGNALED / 전체 차선변경.
 */
class ReportAggregator(private val sessions: SessionStore, private val tzOffsetMs: Long = Time.KST_OFFSET_MS) {

    data class PeriodSummary(
        val fromMs: Long, val toMs: Long,
        val sessions: Int, val validSessions: Int, val distanceKm: Float, val durationMin: Int,
        val avgScore: Int?,                 // 유효 세션 평균 (round half up)
        val harshBrake: Int, val harshAccel: Int, val laneNoSignal: Int, val laneSignaled: Int,
        val harshBrakePer10Km: Float?, val harshAccelPer10Km: Float?, val laneNoSignalPer10Km: Float?,
        val signaledRate: Float?,
        val completedMissions: Int = 0,
    )

    data class DayCell(val dayStartMs: Long, val sessions: Int, val avgScore: Int?, val penaltyEvents: Int)

    data class Trend(val current: PeriodSummary, val previous: PeriodSummary) {
        val avgScoreDelta: Int? get() = if (current.avgScore != null && previous.avgScore != null) current.avgScore - previous.avgScore else null
        val harshBrakeDelta: Int get() = current.harshBrake - previous.harshBrake
        val signaledRateDelta: Float? get() = if (current.signaledRate != null && previous.signaledRate != null) current.signaledRate - previous.signaledRate else null
    }

    fun summarize(profileId: String, fromMs: Long, toMs: Long): PeriodSummary {
        val recs = sessions.list(profileId, fromMs, toMs - 1)
        val valid = recs.filter { it.valid }
        val km = valid.map { it.distanceKm }.sum()
        fun cnt(t: EventType) = valid.sumOf { it.count(t) }
        val hb = cnt(EventType.HARSH_BRAKE); val ha = cnt(EventType.HARSH_ACCEL)
        val ln = cnt(EventType.LANE_CHANGE_NO_SIGNAL) + cnt(EventType.LANE_CHANGE_WRONG_SIGNAL); val ls = cnt(EventType.LANE_CHANGE_SIGNALED)
        fun per10(n: Int) = if (km > 0f) n * 10f / km else null
        return PeriodSummary(
            fromMs, toMs, recs.size, valid.size, km, valid.sumOf { ((it.endWallMs - it.startWallMs) / 60_000L).toInt() },
            if (valid.isEmpty()) null else Scoring.roundHalfUp(valid.map { it.score }.average().toFloat()),
            hb, ha, ln, ls, per10(hb), per10(ha), per10(ln),
            if (ln + ls == 0) null else ls.toFloat() / (ln + ls),
        )
    }

    fun week(profileId: String, anyMsInWeek: Long): PeriodSummary { val w0 = Time.weekStart(anyMsInWeek, tzOffsetMs); return summarize(profileId, w0, w0 + 7 * Time.DAY_MS) }
    fun weekTrend(profileId: String, anyMsInWeek: Long): Trend { val w0 = Time.weekStart(anyMsInWeek, tzOffsetMs); return Trend(summarize(profileId, w0, w0 + 7 * Time.DAY_MS), summarize(profileId, w0 - 7 * Time.DAY_MS, w0)) }

    /** 달력 셀. fromMs~toMs 사이 날짜별 */
    fun calendar(profileId: String, fromMs: Long, toMs: Long): List<DayCell> {
        val recs = sessions.list(profileId, fromMs, toMs - 1)
        return recs.groupBy { Time.dayStart(it.startWallMs, tzOffsetMs) }.entries.sortedBy { it.key }.map { (d, rs) ->
            val v = rs.filter { it.valid }
            DayCell(d, rs.size, if (v.isEmpty()) null else Scoring.roundHalfUp(v.map { it.score }.average().toFloat()),
                v.sumOf { it.count(EventType.HARSH_BRAKE) + it.count(EventType.HARSH_ACCEL) + it.count(EventType.LANE_CHANGE_NO_SIGNAL) + it.count(EventType.LANE_CHANGE_WRONG_SIGNAL) })
        }
    }

    /** 세션 상세의 "출발지→목적지 구간 바": 이벤트를 거리 위치(0~1)로 */
    data class Marker(val type: EventType, val position: Float, val roadName: String?)
    fun markers(r: SessionRecord): List<Marker> {
        val totalM = r.distanceKm * 1000f
        return r.events.mapNotNull { e ->
            val pos = e.distanceFromStartM?.let { if (totalM > 0) (it / totalM).coerceIn(0f, 1f) else null } ?: return@mapNotNull null
            if (e.event.type == EventType.LANE_CHANGE_SIGNALED) null else Marker(e.event.type, pos, e.roadName)
        }
    }
}

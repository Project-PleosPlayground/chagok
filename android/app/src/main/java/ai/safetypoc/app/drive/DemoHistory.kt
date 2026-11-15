package ai.safetypoc.app.drive

import ai.safetypoc.app.location.DemoRoute
import core.session.EventRecord
import core.session.SessionRecord
import core.session.SessionStore
import engine.DrivingEvent
import engine.EngineConfig
import engine.EventType
import engine.Scoring
import engine.SessionResult
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random

/**
 * 시연 영상용 지난 주행 기록 (디버그 탭 "지난 기록 채우기"). 실제 사용자에게는 보이지 않는다.
 * 이번 달 오늘 전 날짜에 출근길·퇴근길 주행을 넣는다(일요일과 몇몇 날은 쉬고, 몇몇 평일은 두 번).
 * 지지난 주 → 지난주 → 이번 주로 갈수록 감점 행동이 줄어 "좋아지고 있다"가 보이게 한다.
 * 사건 위치는 시연 경로(강남역 → 선릉역, 테헤란로) 위라 자주 급하게 멈추는 곳도 생긴다.
 * 날짜마다 id 가 정해져 있어 다시 눌러도 같은 기록을 덮어쓴다.
 */
object DemoHistory {
    private const val MIN_MS = 60_000L
    private const val KPH_TO_MPS = 1f / 3.6f
    /** 일요일 말고도 이 중 하루꼴로 쉰다 */
    private const val SKIP_ONE_IN = 8

    /** 감점 행동이 얼마나 나는지: 이번 주 / 지난주 / 그 전 */
    private enum class Phase(val accel: IntRange, val brake: IntRange, val noSignal: IntRange) {
        THIS_WEEK(0..1, 0..1, 0..0), LAST_WEEK(1..2, 1..2, 0..1), EARLIER(2..3, 2..4, 1..2)
    }

    /** 넣은 기록 수 */
    fun seed(sessions: SessionStore, profileId: String, config: EngineConfig, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var n = 0
        for (day in 1 until today.dayOfMonth) {
            val date = today.withDayOfMonth(day)
            val rnd = Random(date.toEpochDay())
            if (date.dayOfWeek == DayOfWeek.SUNDAY || rnd.nextInt(SKIP_ONE_IN) == 0) continue
            val phase = when {
                !date.isBefore(thisMonday) -> Phase.THIS_WEEK
                !date.isBefore(thisMonday.minusWeeks(1)) -> Phase.LAST_WEEK
                else -> Phase.EARLIER
            }
            // 평일 몇몇 날은 출근길·퇴근길 두 번 달린다
            val commutes = if (date.dayOfWeek != DayOfWeek.SATURDAY && rnd.nextInt(3) == 0) listOf(true, false) else listOf(rnd.nextBoolean())
            for (morning in commutes) { sessions.save(record(profileId, date, morning, phase, rnd, config, zone)); n++ }
        }
        return n
    }

    private fun record(profileId: String, date: LocalDate, morning: Boolean, phase: Phase, rnd: Random, config: EngineConfig, zone: ZoneId): SessionRecord {
        val start = date.atTime(if (morning) 8 else 18, 10 + rnd.nextInt(35)).atZone(zone).toInstant().toEpochMilli()
        val minutes = 20 + rnd.nextInt(16)
        val end = start + minutes * MIN_MS
        val km = 8f + rnd.nextInt(80) / 10f
        fun at() = start + (1 + rnd.nextInt(minutes - 2)) * MIN_MS + rnd.nextInt(60) * 1000L
        fun ev(type: EventType, kph: Float) = at().let { t -> DrivingEvent(type, 1, t, t + 1_500, kph * KPH_TO_MPS) }
        val events = (
            List(phase.accel.random(rnd)) { ev(EventType.HARSH_ACCEL, 10f) } +
                List(phase.brake.random(rnd)) { ev(EventType.HARSH_BRAKE, 50f) } +
                List(phase.noSignal.random(rnd)) { ev(EventType.LANE_CHANGE_NO_SIGNAL, 45f) } +
                List(3 + rnd.nextInt(3)) { ev(EventType.LANE_CHANGE_SIGNALED, 45f) }
            ).sortedBy { it.startMs }
        val b = Scoring.breakdown(events, km, config)
        val result = SessionResult(
            startMs = start, endMs = end, distanceKm = km, events = events,
            score = Scoring.sessionScore(b), accelScore = Scoring.accelScore(b), brakeScore = Scoring.brakeScore(b),
            mannerScore = Scoring.mannerScore(b), valid = true, paramsVersion = config.paramsVersion,
        )
        val where = DemoRoute.locate(result, random = rnd)
        val (from, to) = if (morning) DemoRoute.ORIGIN to DemoRoute.DESTINATION else DemoRoute.DESTINATION to DemoRoute.ORIGIN
        return SessionRecord(
            id = "demo-$date-${if (morning) "am" else "pm"}", profileId = profileId, result = result,
            events = events.map { e -> where(e).let { g -> EventRecord(e, roadName = g?.road, lat = g?.lat, lng = g?.lng) } },
            startWallMs = start, endWallMs = end, origin = from, destination = to,
        )
    }
}

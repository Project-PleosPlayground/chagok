package core

import core.character.Profile
import core.session.EventRecord
import core.session.SessionRecord
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult

object Fx {
    const val DAY = Time.DAY_MS
    /** 2026-11-02(월) 00:00 KST */
    val MON: Long = run { val d = Time.dayIndex(1_793_000_000_000L); Time.weekStart(d * DAY) }   // 아무 날짜의 주 시작(월요일)

    fun ev(type: EventType, atMs: Long = 10_000, sev: Int = 1) = DrivingEvent(type, sev, atMs, atMs + 500, 15f)

    fun record(
        id: String, startWallMs: Long, km: Float = 10f, minutes: Int = 30, valid: Boolean = true,
        events: List<DrivingEvent> = emptyList(), profile: String = Profile.DEFAULT,
        distancePos: List<Float?> = events.map { null },
    ): SessionRecord {
        val penalty = events.count { it.type != EventType.LANE_CHANGE_SIGNALED }
        val score = if (!valid) 0 else (100 - penalty * 5).coerceAtLeast(0)   // 점수 자체는 엔진 몫. 여기선 단순화
        val res = SessionResult(0, minutes * 60_000L, km, events, score, 100, 100, 100, valid)
        return SessionRecord(id, profile, res, events.mapIndexed { i, e -> EventRecord(e, distancePos.getOrNull(i), "테헤란로") }, startWallMs, startWallMs + minutes * 60_000L)
    }
}

/** 미션 테스트 도우미 */
object Mx {
    /** 내장 조건 표·단계별 보상 표에 제공 개수만 바꾼 미션 데이터. 기본은 자동 제공 없음 */
    fun catalog(
        personal: Int = 0, weekly: Int = 0, slots: Int = 3, driveXp: Int = 10,
        failMultiplier: Int = core.content.BundledContent.pack.missions.rules.failPenaltyMultiplier,
        cancelMultiplier: Int = core.content.BundledContent.pack.missions.rules.cancelPenaltyMultiplier,
    ): core.mission.MissionCatalog =
        core.content.BundledContent.pack.missions.copy(
            rules = core.mission.MissionRules(
                personalPerDay = personal, weeklyPerWeek = weekly, slotCount = slots, validDriveXp = driveXp,
                cancelPenaltyMultiplier = cancelMultiplier, failPenaltyMultiplier = failMultiplier,
            ),
        )
}

/** 출발 전 안전벨트 1단계 개인 미션을 제공하고 도전한다. 벨트를 맨 유효 주행이 끝나면 성공한다 */
fun core.mission.MissionEngine.challengeSeatbelt(profileId: String, atMs: Long): core.mission.MissionInstance {
    val m = offer(profileId, core.mission.MissionKind.PERSONAL, core.mission.ConditionType.SEATBELT_BEFORE_START, 1, atMs)!!
    return (challenge(profileId, m.uid, atMs, driving = false) as core.mission.MissionEngine.SlotResult.Ok).instance
}

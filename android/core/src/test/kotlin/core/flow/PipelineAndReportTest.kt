package core.flow

import core.Fx
import core.Time
import core.character.CharacterId
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.content.BundledContent
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.mission.MissionKind
import core.report.ReportAggregator
import core.session.EndAnimation
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PipelineAndReportTest {
    private val sessions = InMemorySessionStore(); private val profiles = InMemoryProfileStore(); private val mstore = InMemoryMissionStore()
    private val missions = MissionEngine(mstore, sessions, missions = { BundledContent.pack.missions })
    private val pipeline = SessionEndPipeline(sessions, profiles, missions)
    private val day0 = Fx.MON + 9 * 3_600_000L
    private val p = Profile.DEFAULT

    private fun challenge(c: ConditionType, tier: Int) {
        val m = missions.offer(p, MissionKind.PERSONAL, c, tier, day0)!!
        missions.challenge(p, m.uid, day0, driving = false)
    }

    @Test fun `세션 종료 한 번에 저장·미션·코인·경험치·레벨업까지`() {
        profiles.upsert(Profile(p, "운전자", day0))
        profiles.saveState(ProfileState(p, selected = CharacterId(1)))   // 캐릭터를 고른 프로필 (#48)
        profiles.saveProgress(profiles.progress(p, CharacterId(1)).copy(xp = 60))
        challenge(ConditionType.HARSH_BRAKE_MAX, 4)          // 0회 이하: 80코인·20xp
        challenge(ConditionType.HARSH_ACCEL_MAX, 3)          // 1회 이하: 60코인·15xp
        challenge(ConditionType.SEATBELT_BEFORE_START, 1)    // 20코인·5xp
        val r = Fx.record("s1", day0 + 600_000L, km = 12f, events = listOf(Fx.ev(LANE_CHANGE_SIGNALED)))
        val s = pipeline.onSessionEnd(r, VehicleStatus(seatBeltBuckledAtStart = true), r.endWallMs)
        assertNotNull(sessions.get("s1"))
        assertEquals(3, s.completed.size)
        assertEquals(160, s.coinsGained); assertEquals(20 + 15 + 5 + 10, s.xpGained)   // 미션 + 유효 주행 경험치 10
        assertEquals(2, s.leveledUpTo)                    // 60 + 50 = 110xp → 2단계
        assertEquals(EndAnimation.DANCE, s.animation)     // 100점
        assertEquals(160, profiles.state(p).coins)
        assertEquals("오늘은 한 번도 안 놀랐어. 이런 날이 제일 좋아.", s.comment)
    }

    @Test fun `미션이 없어도 유효 주행이면 유효 주행 경험치를 준다`() {
        profiles.saveState(ProfileState(p, selected = CharacterId(1)))
        val r = Fx.record("s1", day0)
        val s = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
        assertEquals(0, s.coinsGained); assertEquals(BundledContent.pack.missions.rules.validDriveXp, s.xpGained)
    }

    @Test fun `미인정 세션은 저장만 하고 보상 없음`() {
        profiles.saveState(ProfileState(p, selected = CharacterId(1)))
        challenge(ConditionType.SEATBELT_BEFORE_START, 1)
        val r = Fx.record("s0", day0 + 600_000L, km = 0.4f, minutes = 2, valid = false)
        val s = pipeline.onSessionEnd(r, VehicleStatus(seatBeltBuckledAtStart = true), r.endWallMs)
        assertEquals(EndAnimation.NONE, s.animation); assertEquals(0, s.coinsGained); assertEquals(0, s.xpGained)
        assertTrue(s.completed.isEmpty()); assertNotNull(sessions.get("s0"))
    }

    @Test fun `점수대별 애니메이션`() {
        val bad = Fx.record("b", day0, events = List(7) { Fx.ev(HARSH_BRAKE, it * 10_000L) })   // 100-35 = 65
        assertEquals(EndAnimation.OTL, pipeline.onSessionEnd(bad, VehicleStatus(), bad.endWallMs).animation)
        val mid = Fx.record("m", day0 + 3_600_000L, events = List(3) { Fx.ev(HARSH_ACCEL, it * 10_000L) })   // 85
        assertEquals(EndAnimation.OK, pipeline.onSessionEnd(mid, VehicleStatus(), mid.endWallMs).animation)
    }

    @Test fun `주간 집계와 전주 대비`() {
        val agg = ReportAggregator(sessions)
        // 지난주: 급제동 4, 이번주: 급제동 2 + 깜빡이 3/4
        sessions.save(Fx.record("p1", Fx.MON - 5 * Time.DAY_MS, km = 20f, events = List(4) { Fx.ev(HARSH_BRAKE, it * 10_000L) }))
        sessions.save(Fx.record("c1", day0, km = 10f, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(LANE_CHANGE_SIGNALED, 20_000), Fx.ev(LANE_CHANGE_NO_SIGNAL, 30_000))))
        sessions.save(Fx.record("c2", day0 + Time.DAY_MS, km = 30f, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(LANE_CHANGE_SIGNALED, 20_000), Fx.ev(LANE_CHANGE_SIGNALED, 30_000))))
        sessions.save(Fx.record("c3", day0 + 2 * Time.DAY_MS, km = 0.3f, minutes = 1, valid = false))
        val t = agg.weekTrend(p, day0)
        assertEquals(3, t.current.sessions); assertEquals(2, t.current.validSessions)
        assertEquals(40f, t.current.distanceKm, 0.01f)
        assertEquals(2, t.current.harshBrake); assertEquals(-2, t.harshBrakeDelta)
        assertEquals(0.5f, t.current.harshBrakePer10Km!!, 0.01f)        // 2회 / 40km × 10
        assertEquals(0.75f, t.current.signaledRate!!, 0.01f)
        assertEquals(93, t.current.avgScore)                             // (90+95)/2 = 92.5 → 93
        val cal = agg.calendar(p, Fx.MON, Fx.MON + 7 * Time.DAY_MS)
        assertEquals(3, cal.size); assertNull(cal[2].avgScore)
    }

    @Test fun `세션 상세 구간 마커`() {
        val agg = ReportAggregator(sessions)
        val r = Fx.record("d", day0, km = 10f, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(LANE_CHANGE_SIGNALED, 20_000), Fx.ev(LANE_CHANGE_NO_SIGNAL, 30_000)), distancePos = listOf(2_500f, 5_000f, 9_000f))
        val m = agg.markers(r)
        assertEquals(2, m.size)                                          // SIGNALED 는 표시 안 함
        assertEquals(0.25f, m[0].position, 0.001f); assertEquals(0.9f, m[1].position, 0.001f)
    }
}

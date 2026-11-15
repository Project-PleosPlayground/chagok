package core.flow

import core.Fx
import core.Mx
import core.character.CharacterId
import core.character.GrowthModule
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.Wallet
import core.content.BundledContent
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.mission.MissionKind
import core.mission.MissionStatus
import core.mission.RangePolicy
import core.session.InMemorySessionStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MissionActionsTest {
    private val H = 3_600_000L
    private val p = Profile.DEFAULT; private val ch = CharacterId(1)
    private val sessions = InMemorySessionStore(); private val profiles = InMemoryProfileStore(); private val store = InMemoryMissionStore()
    private val engine = MissionEngine(store, sessions, missions = { Mx.catalog() }, range = RangePolicy.AT_MOST_WEEKLY_ACCUMULATES)
    private val growth = GrowthModule()
    private val actions = MissionActions(engine, Rewards(profiles, growth, Wallet(BundledContent.pack.items)))
    private val mon9 = Fx.MON + 9 * H

    private fun setUp(coins: Int, xp: Int) {
        profiles.saveState(ProfileState(p, coins = coins, selected = ch))
        profiles.saveProgress(profiles.progress(p, ch).copy(xp = xp, stage = growth.stageFor(xp)))
    }
    private fun take(kind: MissionKind, c: ConditionType, tier: Int): String {
        val m = engine.offer(p, kind, c, tier, mon9)!!
        actions.challenge(p, m.uid, mon9, driving = false); return m.uid
    }

    @Test fun `취소하면 그 미션의 보상만큼 코인과 경험치를 깎는다`() {
        setUp(coins = 100, xp = 120)
        val uid = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3)     // 60코인·15xp
        assertTrue(actions.cancel(p, uid, mon9 + H, driving = false) is MissionEngine.SlotResult.Ok)
        assertEquals(40, profiles.state(p).coins); assertEquals(105, profiles.progress(p, ch).xp)
    }

    @Test fun `패널티는 0 밑으로 깎지 않고, 경험치가 깎이면 레벨이 내려간다`() {
        setUp(coins = 10, xp = 110)                                                   // 2단계 (100 이상)
        val uid = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4)     // 80코인·20xp
        actions.cancel(p, uid, mon9 + H, driving = false)
        assertEquals(0, profiles.state(p).coins)
        assertEquals(90, profiles.progress(p, ch).xp); assertEquals(1, profiles.progress(p, ch).stage)
        val uid2 = take(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 4)
        actions.cancel(p, uid2, mon9 + H, driving = false)
        assertEquals(70, profiles.progress(p, ch).xp)
        setUp(coins = 0, xp = 5)
        actions.cancel(p, take(MissionKind.PERSONAL, ConditionType.LANE_NO_SIGNAL_MAX, 4), mon9 + H, driving = false)
        assertEquals(0, profiles.progress(p, ch).xp)
    }

    @Test fun `주행 중에는 취소할 수 없고 패널티도 없다`() {
        setUp(coins = 100, xp = 50)
        val uid = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3)
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING), actions.cancel(p, uid, mon9 + H, driving = true))
        assertEquals(100, profiles.state(p).coins); assertEquals(MissionStatus.IN_PROGRESS, store.list(p).single().status)
    }

    @Test fun `유효시간 끝에 성공한 미션의 보상은 틱 갱신에서 준다`() {
        setUp(coins = 0, xp = 0)
        take(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 3)
        val r = Fx.record("s1", mon9 + H); sessions.save(r)
        engine.onDriveEnd(r, core.session.VehicleStatus(), r.endWallMs)
        val done = actions.refresh(p, Fx.MON + 7 * Fx.DAY, null).completed
        assertEquals(1, done.size)
        assertEquals(60, profiles.state(p).coins); assertEquals(15, profiles.progress(p, ch).xp)
    }
}

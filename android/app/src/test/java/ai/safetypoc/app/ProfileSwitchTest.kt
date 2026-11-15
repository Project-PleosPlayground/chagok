package ai.safetypoc.app

import ai.safetypoc.app.ui.home.HomeModel
import ai.safetypoc.app.ui.mission.MissionTabModel
import ai.safetypoc.app.ui.report.ReportModel
import core.character.InMemoryProfileStore
import core.character.Profile
import core.mission.ConditionType
import core.mission.MissionKind
import core.session.SessionRecord
import engine.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 고른 프로필의 데이터로 화면이 바뀌는지 (#105) */
class ProfileSwitchTest {
    private val now = 1_793_000_000_000L
    private val store = InMemoryProfileStore().apply {
        upsert(Profile("a", "가", 1L))
        upsert(Profile("b", "나", 2L))
    }

    private fun graph(last: String? = null) = AppGraph(profiles = store, lastProfileId = last)

    @Test fun `프로필이 없으면 기본 프로필로 시작한다`() {
        assertEquals(Profile.DEFAULT, AppGraph().profileId)
    }

    @Test fun `지난번에 고른 프로필로 시작한다`() {
        assertEquals("b", graph(last = "b").profileId)
    }

    @Test fun `지난번 프로필이 없으면 먼저 만든 프로필로 시작한다`() {
        assertEquals("a", graph(last = "gone").profileId)
        assertEquals("a", graph(last = null).profileId)
    }

    @Test fun `저장된 프로필이 아니면 바꾸지 않는다`() {
        val g = graph(last = "a")
        assertFalse(g.selectProfile("gone"))
        assertEquals("a", g.profileId)
    }

    @Test fun `프로필을 바꾸면 홈 포인트가 그 프로필 것으로 바뀌고 돌아오면 다시 바뀐다`() {
        val g = graph(last = "a")
        store.saveState(store.state("a").copy(coins = 100))
        store.saveState(store.state("b").copy(coins = 5))
        val home = HomeModel({ g.profileId }, g.profiles, g.growth, content = { g.content.active })
        home.refresh(); assertEquals(100, home.state.value.coins)
        assertTrue(g.selectProfile("b"))
        home.refresh(); assertEquals(5, home.state.value.coins)
        g.selectProfile("a")
        home.refresh(); assertEquals(100, home.state.value.coins)
    }

    @Test fun `프로필을 바꾸면 미션 슬롯과 주행 기록도 그 프로필 것이다`() {
        val g = graph(last = "a")
        val m = g.missions.offer("a", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        g.missionActions.challenge("a", m.uid, now, driving = false)
        g.sessions.save(SessionRecord("s1", "a", SessionResult(0, 600_000, 5f, emptyList(), 90, 100, 90, 100, true), emptyList(), now - 1, now))
        val tab = MissionTabModel({ g.profileId }, g.missions, g.missionActions, slotCount = { 3 }, driving = { false }, now = { now })
        val report = ReportModel({ g.profileId }, g.sessions) { now }
        tab.refresh(); report.refresh()
        assertEquals(listOf(m.uid), tab.state.value.slot.map { it.uid })
        assertEquals(90, report.state.value.session?.score)
        g.selectProfile("b")
        tab.refresh(); report.refresh()
        assertTrue(tab.state.value.slot.isEmpty())
        assertEquals(null, report.state.value.session)
    }

    @Test fun `프로필을 바꾸면 그 프로필에 캐릭터가 없을 때 처음 캐릭터를 고른다`() {
        val g = graph(last = "a")
        assertEquals(null, store.state("b").selected)
        g.selectProfile("b")
        assertTrue(store.state("b").selected != null)
    }

    @Test fun `지금 프로필을 지우면 남은 프로필 중 먼저 만든 것으로 바꾼다`() {
        val g = graph(last = "a")
        g.deleteProfile("a")
        assertEquals("b", g.profileId)
        g.deleteProfile("b")
        assertEquals(Profile.DEFAULT, g.profileId)
    }

    @Test fun `다른 프로필을 지우면 지금 프로필은 그대로`() {
        val g = graph(last = "b")
        g.deleteProfile("a")
        assertEquals("b", g.profileId)
    }

    @Test fun `프로필 목록에 없는 기본 프로필 데이터는 먼저 만든 프로필로 옮긴다`() {
        val sessions = core.session.InMemorySessionStore()
        val missionStore = core.mission.InMemoryMissionStore()
        store.saveState(store.state(Profile.DEFAULT).copy(coins = 300))
        sessions.save(SessionRecord("s1", Profile.DEFAULT, SessionResult(0, 600_000, 5f, emptyList(), 90, 100, 90, 100, true), emptyList(), now - 1, now))
        val g = AppGraph(profiles = store, sessions = sessions, missionStore = missionStore)
        assertEquals(300, store.state("a").coins)
        assertEquals(listOf("s1"), sessions.list("a").map { it.id })
        assertTrue(sessions.list(Profile.DEFAULT).isEmpty())
        assertEquals(0, store.state(Profile.DEFAULT).coins)
        assertEquals("a", g.profileId)
        assertEquals("a", g.adoptedDefaultTo)
    }

    @Test fun `기본 프로필 id 의 프로필이 있으면 옮기지 않는다`() {
        store.upsert(Profile(Profile.DEFAULT, "첫", 0L))
        store.saveState(store.state(Profile.DEFAULT).copy(coins = 300))
        assertEquals(null, AppGraph(profiles = store).adoptedDefaultTo)
        assertEquals(300, store.state(Profile.DEFAULT).coins)
        assertEquals(0, store.state("a").coins)
    }

    @Test fun `옮길 프로필에 주행 기록이 있으면 옮기지 않는다`() {
        val sessions = core.session.InMemorySessionStore()
        sessions.save(SessionRecord("mine", "a", SessionResult(0, 600_000, 5f, emptyList(), 80, 100, 80, 100, true), emptyList(), now - 1, now))
        store.saveState(store.state(Profile.DEFAULT).copy(coins = 300))
        AppGraph(profiles = store, sessions = sessions)
        assertEquals(300, store.state(Profile.DEFAULT).coins)
        assertEquals(listOf("mine"), sessions.list("a").map { it.id })
    }

    @Test fun `프로필 초기화는 기본 프로필로 돌아간다`() {
        val g = graph(last = "b")
        g.clearProfiles()
        assertEquals(Profile.DEFAULT, g.profileId)
        assertTrue(store.profiles().isEmpty())
        assertTrue(store.state(Profile.DEFAULT).selected != null)
    }
}

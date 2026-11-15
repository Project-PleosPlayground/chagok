package ai.safetypoc.app

import ai.safetypoc.app.ai.AiCharacters
import ai.safetypoc.app.ai.AiConsent
import ai.safetypoc.app.drive.DriveRecorder
import ai.safetypoc.app.settings.AppSettings
import ai.safetypoc.app.settings.MemoryFlags
import ai.safetypoc.app.ui.decorate.ShopModel
import ai.safetypoc.app.ui.home.HomeModel
import ai.safetypoc.app.ui.mission.MissionTabModel
import ai.safetypoc.app.ui.profile.AddProfileModel
import ai.safetypoc.app.ui.report.ReportModel
import ai.safetypoc.app.ui.settings.SettingsModel
import ai.safetypoc.app.ui.stats.StatsModel
import core.ai.AiClient
import core.character.CharacterProgress
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.item.ItemId
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.LiveDrive
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.InMemorySessionStore
import core.session.SessionRecord
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #105 프로필별 데이터. 스펙만 보고 쓴 블랙박스 테스트 */
class ProfileSpecTest {
    private val now = 1_793_000_000_000L
    private val minute = 60_000L
    private val profiles = InMemoryProfileStore()
    private val sessions = InMemorySessionStore()
    private val missionStore = InMemoryMissionStore()
    private val D = Profile.DEFAULT

    private fun graph(last: String? = null, aiClient: AiClient? = null, aiConsent: (String) -> Boolean = { true }) =
        AppGraph(profiles = profiles, sessions = sessions, missionStore = missionStore, lastProfileId = last, aiClient = aiClient, aiConsent = aiConsent)

    /** 첫 실행 규칙으로 정해지는 시작 캐릭터 */
    private val starter = AppGraph(profiles = InMemoryProfileStore()).let { it.profiles.state(it.profileId).selected }
    private val other by lazy {
        val g = AppGraph(profiles = InMemoryProfileStore())
        g.content.active.characters.map { it.id }.first { it != starter }
    }

    private fun add(id: String, createdMs: Long) = profiles.upsert(Profile(id, "n-$id", createdMs))

    private fun result(startMs: Long, score: Int, valid: Boolean = true, minutes: Int = 15, events: List<EventType> = emptyList()) =
        SessionResult(startMs, startMs + minutes * minute, 8f, events.mapIndexed { i, t -> DrivingEvent(t, 1, startMs + i * 1000L, startMs + i * 1000L + 500, 15f) }, score, 100, 100, 100, valid)

    private fun session(id: String, pid: String, score: Int = 90, startMs: Long = now - 60 * minute) =
        SessionRecord(id, pid, result(startMs, score), emptyList(), startMs, startMs + 15 * minute)

    /** 미션 저장소에 미션 한 건을 넣는다. 프로필이 없는 별도 그래프로 넣어 이전이 일어나지 않게 한다 */
    private fun offerMission(pid: String, cond: ConditionType = ConditionType.HARSH_BRAKE_MAX): MissionInstance =
        AppGraph(profiles = InMemoryProfileStore(), sessions = InMemorySessionStore(), missionStore = missionStore)
            .missions.offer(pid, MissionKind.PERSONAL, cond, 1, now)!!

    // ---------- 시작 프로필 ----------

    @Test fun `저장된 프로필이 없으면 DEFAULT 로 시작하고 시작 캐릭터를 받는다`() {
        val g = graph()
        assertEquals(D, g.profileId)
        assertEquals(starter, profiles.state(D).selected)
    }

    @Test fun `마지막 프로필이 저장돼 있으면 그 프로필로 시작`() {
        add("p1", 100); add("p2", 200)
        assertEquals("p2", graph(last = "p2").profileId)
    }

    @Test fun `마지막 프로필이 저장된 프로필에 없으면 가장 먼저 만든 프로필 - 넣은 순서가 아니라 createdMs`() {
        add("late", 300); add("early", 100); add("mid", 200)
        assertEquals("early", graph(last = "gone").profileId)
    }

    @Test fun `마지막 프로필이 null 이면 가장 먼저 만든 프로필`() {
        add("b", 200); add("a", 100)
        assertEquals("a", graph(last = null).profileId)
    }

    @Test fun `마지막 프로필이 DEFAULT 여도 DEFAULT 가 저장된 프로필이 아니면 가장 먼저 만든 프로필`() {
        add("p1", 100)
        assertEquals("p1", graph(last = D).profileId)
    }

    @Test fun `마지막 프로필이 있는데 저장된 프로필이 없으면 DEFAULT`() {
        assertEquals(D, graph(last = "p1").profileId)
    }

    @Test fun `만든 시각이 같으면 그 중 하나로 시작한다`() {
        add("x", 100); add("y", 100); add("z", 500)
        assertTrue(graph().profileId in setOf("x", "y"))
    }

    @Test fun `profileId 는 밖에서 바꿀 수 없다`() {
        val setter = AppGraph::class.java.methods.firstOrNull { it.name == "setProfileId" }
        assertNull("공개 setter 가 없어야 한다", setter)
    }

    // ---------- selectProfile ----------

    @Test fun `저장되지 않은 프로필을 고르면 false 이고 아무것도 바뀌지 않는다`() {
        add("p1", 100)
        val g = graph()
        val before = profiles.state("p1")
        assertFalse(g.selectProfile("nope"))
        assertEquals("p1", g.profileId)
        assertEquals(before, profiles.state("p1"))
        assertEquals(ProfileState("nope"), profiles.state("nope"))
        assertEquals(listOf("p1"), profiles.profiles().map { it.id })
    }

    @Test fun `DEFAULT 가 저장된 프로필이 아니면 DEFAULT 를 골라도 false`() {
        val g = graph()
        assertFalse(g.selectProfile(D))
        assertEquals(D, g.profileId)
    }

    @Test fun `빈 문자열 id 는 false`() {
        add("p1", 100)
        val g = graph()
        assertFalse(g.selectProfile(""))
        assertEquals("p1", g.profileId)
    }

    @Test fun `고른 캐릭터가 없는 프로필로 바꾸면 시작 캐릭터를 받고 코인 등은 그대로`() {
        add("p1", 100); add("p2", 200)
        profiles.saveState(ProfileState("p2", selected = null, coins = 30, owned = setOf(ItemId(1))))
        val g = graph()
        assertTrue(g.selectProfile("p2"))
        assertEquals("p2", g.profileId)
        val s = profiles.state("p2")
        assertEquals(starter, s.selected)
        assertEquals(30, s.coins)
        assertEquals(setOf(ItemId(1)), s.owned)
    }

    @Test fun `이미 고른 캐릭터가 있으면 시작 캐릭터로 덮지 않는다`() {
        add("p1", 100); add("p2", 200)
        profiles.saveState(ProfileState("p2", selected = other, coins = 5))
        val g = graph()
        assertTrue(g.selectProfile("p2"))
        assertEquals(other, profiles.state("p2").selected)
        assertEquals(5, profiles.state("p2").coins)
    }

    @Test fun `지금 프로필을 다시 고르면 true 이고 데이터는 그대로`() {
        add("p1", 100)
        profiles.saveState(ProfileState("p1", selected = other, coins = 7))
        val g = graph()
        assertTrue(g.selectProfile("p1"))
        assertEquals("p1", g.profileId)
        assertEquals(ProfileState("p1", selected = other, coins = 7), profiles.state("p1"))
    }

    @Test fun `다른 프로필로 갔다가 돌아와도 각자 데이터가 섞이지 않는다`() {
        add("p1", 100); add("p2", 200)
        profiles.saveState(ProfileState("p1", selected = other, coins = 11))
        profiles.saveState(ProfileState("p2", selected = other, coins = 22))
        val g = graph()
        assertTrue(g.selectProfile("p2")); assertTrue(g.selectProfile("p1"))
        assertEquals(11, profiles.state("p1").coins); assertEquals(22, profiles.state("p2").coins)
    }

    // ---------- deleteProfile ----------

    private fun seedThree() {
        add("p1", 100); add("p2", 200); add("p3", 300)
        for (p in listOf("p1", "p2", "p3")) {
            profiles.saveState(ProfileState(p, selected = other, coins = 10))
            profiles.saveProgress(CharacterProgress(p, other, alias = "a-$p", xp = 5))
            sessions.save(session("s-$p", p))
            offerMission(p)
        }
    }

    @Test fun `다른 프로필을 지우면 그 프로필 데이터만 지워지고 지금 프로필은 그대로`() {
        seedThree()
        val g = graph(last = "p1")
        g.deleteProfile("p2")
        assertEquals("p1", g.profileId)
        assertEquals(listOf("p1", "p3"), profiles.profiles().map { it.id }.sorted())
        assertEquals(ProfileState("p2"), profiles.state("p2"))
        assertTrue(profiles.progresses("p2").isEmpty())
        assertTrue(sessions.list("p2").isEmpty())
        assertTrue(missionStore.list("p2").isEmpty())
        for (p in listOf("p1", "p3")) {
            assertEquals(10, profiles.state(p).coins)
            assertEquals(1, profiles.progresses(p).size)
            assertEquals(1, sessions.list(p).size)
            assertEquals(1, missionStore.list(p).size)
        }
    }

    @Test fun `지금 프로필을 지우면 남은 것 중 가장 먼저 만든 프로필로 바뀐다`() {
        seedThree()
        val g = graph(last = "p1")
        g.deleteProfile("p1")
        assertEquals("p2", g.profileId)
    }

    @Test fun `지금 프로필을 지울 때 넣은 순서가 아니라 createdMs 로 고른다`() {
        add("cur", 50); add("newer", 900); add("older", 100)
        val g = graph(last = "cur")
        assertEquals("cur", g.profileId)
        g.deleteProfile("cur")
        assertEquals("older", g.profileId)
    }

    @Test fun `마지막 프로필을 지우면 DEFAULT`() {
        add("p1", 100)
        sessions.save(session("s1", "p1"))
        val g = graph()
        g.deleteProfile("p1")
        assertEquals(D, g.profileId)
        assertTrue(profiles.profiles().isEmpty())
        assertTrue(sessions.list("p1").isEmpty())
    }

    @Test fun `없는 프로필을 지워도 지금 프로필과 다른 데이터는 그대로`() {
        seedThree()
        val g = graph(last = "p3")
        g.deleteProfile("nope")
        assertEquals("p3", g.profileId)
        assertEquals(3, profiles.profiles().size)
        assertEquals(3, listOf("p1", "p2", "p3").sumOf { sessions.list(it).size })
    }

    @Test fun `저장된 DEFAULT 프로필이 지금일 때 지우면 다른 프로필로 바뀌고 DEFAULT 데이터는 지워진다`() {
        add(D, 100); add("p2", 200)
        profiles.saveState(ProfileState(D, selected = other, coins = 9))
        sessions.save(session("sd", D))
        val g = graph(last = D)
        assertEquals(D, g.profileId)
        g.deleteProfile(D)
        assertEquals("p2", g.profileId)
        assertTrue(sessions.list(D).isEmpty())
        assertEquals(0, profiles.state(D).coins)
    }

    @Test fun `모두 지운 뒤 selectProfile 은 false`() {
        add("p1", 100)
        val g = graph()
        g.deleteProfile("p1")
        assertFalse(g.selectProfile("p1"))
        assertEquals(D, g.profileId)
    }

    // ---------- clearProfiles ----------

    @Test fun `clearProfiles - 프로필·상태·성장은 지우고 세션·미션은 남기며 DEFAULT 가 시작 캐릭터를 받는다`() {
        seedThree()
        sessions.save(session("sd", D))
        val g = graph(last = "p2")
        g.clearProfiles()
        assertEquals(D, g.profileId)
        assertTrue(profiles.profiles().isEmpty())
        for (p in listOf("p1", "p2", "p3")) {
            assertEquals(ProfileState(p), profiles.state(p))
            assertTrue(profiles.progresses(p).isEmpty())
            assertEquals(1, sessions.list(p).size)
            assertEquals(1, missionStore.list(p).size)
        }
        assertEquals(1, sessions.list(D).size)
        val d = profiles.state(D)
        assertEquals(starter, d.selected)
        assertEquals(0, d.coins)
        assertTrue(d.owned.isEmpty())
    }

    @Test fun `clearProfiles - 지금이 DEFAULT 여도 DEFAULT 의 코인·성장은 지워진다`() {
        val g = graph()
        profiles.saveState(profiles.state(D).copy(coins = 99))
        profiles.saveProgress(CharacterProgress(D, other, xp = 40))
        g.clearProfiles()
        assertEquals(0, profiles.state(D).coins)
        assertEquals(starter, profiles.state(D).selected)
        assertEquals(0, profiles.progress(D, other).xp)
    }

    // ---------- 이전(migration) ----------

    /** DEFAULT 에 데이터가 있고 p1(가장 먼저)·p2 가 저장된 상태 */
    private fun legacy(): MissionInstance {
        profiles.saveState(ProfileState(D, selected = other, coins = 70, owned = setOf(ItemId(6))))
        profiles.saveProgress(CharacterProgress(D, other, alias = "붕붕", xp = 33, stage = 2))
        sessions.save(session("sd1", D, startMs = now - 120 * minute)); sessions.save(session("sd2", D, startMs = now - 60 * minute))
        val m = offerMission(D)
        add("p2", 200); add("p1", 100)
        return m
    }

    @Test fun `이전 - 조건이 맞으면 DEFAULT 의 상태·성장·세션·미션이 가장 먼저 만든 프로필로 옮겨지고 DEFAULT 는 비워진다`() {
        val m = legacy()
        graph()
        val s = profiles.state("p1")
        assertEquals(70, s.coins); assertEquals(other, s.selected); assertEquals(setOf(ItemId(6)), s.owned)
        assertEquals(ProfileState(D), profiles.state(D))
        val prog = profiles.progresses("p1").single()
        assertEquals("붕붕", prog.alias); assertEquals(33, prog.xp); assertEquals(2, prog.stage)
        assertTrue(profiles.progresses(D).isEmpty())
        assertEquals(listOf("sd1", "sd2"), sessions.list("p1").map { it.id })
        assertTrue(sessions.list("p1").all { it.profileId == "p1" })
        assertTrue(sessions.list(D).isEmpty())
        assertEquals(listOf(m.uid), missionStore.list("p1").map { it.uid })
        assertTrue(missionStore.list("p1").all { it.profileId == "p1" })
        assertTrue(missionStore.list(D).isEmpty())
        assertTrue("두 번째 프로필로는 옮기지 않는다", sessions.list("p2").isEmpty())
    }

    @Test fun `이전 - 마지막 프로필이 다른 프로필이어도 대상은 가장 먼저 만든 프로필`() {
        legacy()
        val g = graph(last = "p2")
        assertEquals("p2", g.profileId)
        assertEquals(2, sessions.list("p1").size)
        assertTrue(sessions.list("p2").isEmpty())
    }

    @Test fun `이전 - 저장된 프로필이 없으면 옮기지 않는다`() {
        profiles.saveState(ProfileState(D, selected = other, coins = 70))
        sessions.save(session("sd", D))
        val g = graph()
        assertEquals(D, g.profileId)
        assertEquals(70, profiles.state(D).coins)
        assertEquals(1, sessions.list(D).size)
    }

    @Test fun `이전 - DEFAULT 가 저장된 프로필이면 옮기지 않는다`() {
        legacy()
        add(D, 50)
        graph()
        assertEquals(70, profiles.state(D).coins)
        assertEquals(2, sessions.list(D).size)
        assertEquals(1, missionStore.list(D).size)
        assertEquals(0, profiles.state("p1").coins)
        assertTrue(sessions.list("p1").isEmpty())
    }

    @Test fun `이전 - DEFAULT 가 가장 먼저가 아니어도 저장돼 있으면 옮기지 않는다`() {
        legacy()
        add(D, 999)
        graph()
        assertEquals(2, sessions.list(D).size)
        assertTrue(sessions.list("p1").isEmpty())
    }

    @Test fun `이전 - 가장 먼저 만든 프로필에 세션이 하나라도 있으면 옮기지 않는다`() {
        legacy()
        sessions.save(session("s1", "p1", startMs = now - 500 * minute))
        graph()
        assertEquals(70, profiles.state(D).coins)
        assertEquals(2, sessions.list(D).size)
        assertEquals(listOf("s1"), sessions.list("p1").map { it.id })
        assertEquals(0, profiles.state("p1").coins)
    }

    @Test fun `이전 - 가장 먼저 만든 프로필의 세션이 무효 주행이어도 세션이 있으므로 옮기지 않는다`() {
        legacy()
        sessions.save(SessionRecord("short", "p1", result(now - 500 * minute, 0, valid = false, minutes = 1), emptyList(), now - 500 * minute, now - 499 * minute))
        graph()
        assertEquals(2, sessions.list(D).size)
    }

    @Test fun `이전 - 두 번째 프로필에만 세션이 있으면 막지 않는다`() {
        legacy()
        sessions.save(session("s2", "p2"))
        graph()
        assertEquals(2, sessions.list("p1").size)
        assertEquals(listOf("s2"), sessions.list("p2").map { it.id })
    }

    @Test fun `이전 - 가장 먼저 만든 프로필에 코인·미션만 있고 세션이 없으면 옮긴다`() {
        legacy()
        offerMission("p1", ConditionType.SEATBELT_BEFORE_START)
        graph()
        assertEquals(2, sessions.list("p1").size)
        assertEquals(2, missionStore.list("p1").size)
        assertTrue(missionStore.list(D).isEmpty())
    }

    @Test fun `이전 - DEFAULT 에 받은 미션만 있어도 옮긴다`() {
        val m = offerMission(D)
        add("p1", 100)
        graph()
        assertEquals(listOf(m.uid), missionStore.list("p1").map { it.uid })
        assertTrue(missionStore.list(D).isEmpty())
    }

    @Test fun `이전 - DEFAULT 에 캐릭터 성장만 있어도 옮긴다`() {
        profiles.saveProgress(CharacterProgress(D, other, xp = 12))
        add("p1", 100)
        graph()
        assertEquals(12, profiles.progress("p1", other).xp)
        assertTrue(profiles.progresses(D).isEmpty())
    }

    @Test fun `이전 - DEFAULT 에 세션만 있어도 옮긴다`() {
        sessions.save(session("only", D))
        add("p1", 100)
        graph()
        assertEquals(listOf("only"), sessions.list("p1").map { it.id })
        assertTrue(sessions.list(D).isEmpty())
    }

    @Test fun `이전 - DEFAULT 에 기본값 아닌 상태(코인만)만 있어도 옮긴다`() {
        profiles.saveState(ProfileState(D, coins = 4))
        add("p1", 100)
        graph()
        assertEquals(4, profiles.state("p1").coins)
        assertEquals(ProfileState(D), profiles.state(D))
    }

    @Test fun `이전 - DEFAULT 에 데이터가 없으면 아무것도 옮기지 않는다`() {
        add("p1", 100)
        profiles.saveState(ProfileState("p1", selected = other, coins = 3))
        offerMission("p1")
        graph()
        assertEquals(ProfileState("p1", selected = other, coins = 3), profiles.state("p1"))
        assertEquals(1, missionStore.list("p1").size)
        assertTrue(sessions.list("p1").isEmpty())
    }

    @Test fun `이전 - 한 번 옮긴 뒤 다시 켜도 다시 옮기지 않는다`() {
        legacy()
        graph()
        profiles.saveState(profiles.state("p1").copy(coins = 1))
        graph()
        assertEquals(1, profiles.state("p1").coins)
        assertEquals(2, sessions.list("p1").size)
    }

    @Test fun `이전 - 세션이 없는 첫 프로필에 이미 상태가 있으면 DEFAULT 상태로 바뀐다`() {
        // 스펙: DEFAULT 의 상태가 옮겨진다. 첫 프로필의 기존 상태와 합치는 규칙은 없다
        legacy()
        profiles.saveState(ProfileState("p1", selected = starter, coins = 1))
        graph()
        assertEquals(70, profiles.state("p1").coins)
        assertEquals(other, profiles.state("p1").selected)
    }

    // ---------- 모델이 지금 프로필을 따른다 ----------

    private fun twoProfiles(): AppGraph {
        add("p1", 100); add("p2", 200)
        profiles.saveState(ProfileState("p1", selected = other, coins = 111))
        profiles.saveState(ProfileState("p2", selected = other, coins = 222))
        return graph(last = "p1")
    }

    @Test fun `홈 - 프로필을 바꾸고 refresh 하면 새 프로필의 코인`() {
        val g = twoProfiles()
        val home = HomeModel({ g.profileId }, g.profiles, g.growth, content = { g.content.active })
        home.refresh(); assertEquals(111, home.state.value.coins)
        assertTrue(g.selectProfile("p2"))
        home.refresh(); assertEquals(222, home.state.value.coins)
    }

    @Test fun `홈 - 별명을 지으면 지금 프로필 캐릭터에만 붙는다`() {
        val g = twoProfiles()
        val home = HomeModel({ g.profileId }, g.profiles, g.growth, content = { g.content.active })
        assertTrue(g.selectProfile("p2"))
        home.setAlias("둘째")
        assertEquals("둘째", profiles.progress("p2", other).alias)
        assertEquals("", profiles.progress("p1", other).alias)
    }

    @Test fun `미션 탭 - 슬롯은 프로필별`() {
        val g = twoProfiles()
        val tab = MissionTabModel({ g.profileId }, g.missions, g.missionActions, slotCount = { 3 }, driving = { false }, now = { now })
        val m = g.missions.offer("p1", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        tab.challenge(m.uid)
        assertEquals(listOf(m.uid), tab.state.value.slot.map { it.uid })
        assertTrue(g.selectProfile("p2"))
        tab.refresh()
        assertTrue(tab.state.value.slot.isEmpty())
        assertTrue(tab.state.value.personal.none { it.uid == m.uid })
        assertTrue(g.selectProfile("p1"))
        tab.refresh()
        assertEquals(listOf(m.uid), tab.state.value.slot.map { it.uid })
    }

    @Test fun `미션 탭 - 바꾼 뒤 도전하면 새 프로필의 미션에 도전한다`() {
        val g = twoProfiles()
        val tab = MissionTabModel({ g.profileId }, g.missions, g.missionActions, slotCount = { 3 }, driving = { false }, now = { now })
        assertTrue(g.selectProfile("p2"))
        val m2 = g.missions.offer("p2", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        tab.challenge(m2.uid)
        assertEquals(MissionStatus.IN_PROGRESS, missionStore.list("p2").single { it.uid == m2.uid }.status)
        assertTrue(g.missions.slot("p1").isEmpty())
    }

    @Test fun `리포트 - 프로필마다 자기 마지막 주행`() {
        val g = twoProfiles()
        sessions.save(session("a", "p1", score = 71)); sessions.save(session("b", "p2", score = 82))
        val report = ReportModel({ g.profileId }, g.sessions, now = { now })
        report.refresh(); assertEquals(71, report.state.value.session!!.score)
        assertTrue(g.selectProfile("p2"))
        report.refresh(); assertEquals(82, report.state.value.session!!.score)
    }

    @Test fun `리포트 - 주행이 없는 프로필로 바꾸면 비어 있다`() {
        add("p1", 100); add("p2", 200)
        sessions.save(session("a", "p1"))
        val g = graph(last = "p1")
        val report = ReportModel({ g.profileId }, g.sessions, now = { now })
        report.refresh(); assertNotNull(report.state.value.session)
        assertTrue(g.selectProfile("p2"))
        report.refresh(); assertNull(report.state.value.session)
    }

    @Test fun `상점 - 포인트와 가진 아이템이 프로필별`() {
        add("p1", 100); add("p2", 200)
        profiles.saveState(ProfileState("p1", selected = other, coins = 111, owned = setOf(ItemId(1))))
        profiles.saveState(ProfileState("p2", selected = other, coins = 222, owned = setOf(ItemId(6))))
        val g = graph(last = "p1")
        val shop = ShopModel({ g.profileId }, g.profiles, g.wallet, content = { g.content.active }, driving = { false })
        shop.enter()
        assertEquals(111, shop.state.value.points)
        assertEquals(setOf(ItemId(1)), shop.state.value.slots.filter { it.owned }.map { it.id }.toSet())
        assertTrue(g.selectProfile("p2"))
        shop.enter()
        assertEquals(222, shop.state.value.points)
        assertEquals(setOf(ItemId(6)), shop.state.value.slots.filter { it.owned }.map { it.id }.toSet())
    }

    @Test fun `통계 - 코인과 주행이 프로필별`() {
        val g = twoProfiles()
        val day = now - 60 * minute
        sessions.save(session("a", "p1", startMs = day))
        val stats = StatsModel({ g.profileId }, g.sessions, g.missionStore, g.profiles, { "한 줄" }, { now })
        stats.enter()
        assertEquals(111, stats.state.value.coins)
        val p1Drives = stats.state.value.drivesPerDay.values.sum()
        assertEquals(1, p1Drives)
        assertTrue(g.selectProfile("p2"))
        stats.refresh()
        assertEquals(222, stats.state.value.coins)
        assertEquals(0, stats.state.value.drivesPerDay.values.sum())
    }

    @Test fun `설정 - AI 동의는 프로필별로 보이고 켜면 지금 프로필에만 저장된다`() {
        val g = twoProfiles()
        val consent = AiConsent(MemoryFlags())
        consent.set("p1", true)
        val settings = SettingsModel({ g.profileId }, consent, AppSettings(MemoryFlags()), aiAvailable = true, driving = { false })
        settings.refresh(); assertTrue(settings.state.value.aiOn)
        assertTrue(g.selectProfile("p2"))
        settings.refresh(); assertFalse(settings.state.value.aiOn)
        assertTrue(settings.state.value.askAiOnDriveEnd)
        settings.setAi(true)
        assertTrue(consent.isOn("p2")); assertTrue(consent.isOn("p1"))
        settings.setAi(false)
        assertFalse(consent.isOn("p2")); assertTrue("다른 프로필 동의는 그대로", consent.isOn("p1"))
    }

    @Test fun `LiveDrive - record 의 프로필은 읽을 때의 지금 프로필`() {
        val g = twoProfiles()
        val live = LiveDrive { g.profileId }
        live.start(now); live.validate(emptyList())
        assertEquals("p1", live.record()!!.profileId)
        assertTrue(g.selectProfile("p2"))
        assertEquals("p2", live.record()!!.profileId)
    }

    @Test fun `LiveDrive - 문자열 생성자는 그 프로필 고정`() {
        val g = twoProfiles()
        val live = LiveDrive("p1")
        live.start(now); live.validate(emptyList())
        assertTrue(g.selectProfile("p2"))
        assertEquals("p1", live.record()!!.profileId)
    }

    @Test fun `주행 기록·미션 판정 - 바꾼 프로필로 저장·판정되고 다른 프로필 미션은 그대로`() {
        val g = twoProfiles()
        val m1 = g.missions.offer("p1", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, now - minute)!!
        g.missions.challenge("p1", m1.uid, now - minute, driving = false)
        val m2 = g.missions.offer("p2", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, now - minute)!!
        g.missions.challenge("p2", m2.uid, now - minute, driving = false)
        assertTrue(g.selectProfile("p2"))
        val recorder = DriveRecorder(g)
        val s = recorder.onSessionEnded(result(now, 90, valid = true, minutes = 15), VehicleStatus(), now + 16 * minute)
        assertEquals("p2", s.record.profileId)
        assertEquals(1, sessions.list("p2").size)
        assertTrue(sessions.list("p1").isEmpty())
        assertEquals(MissionStatus.SUCCEEDED, missionStore.list("p2").single { it.uid == m2.uid }.status)
        assertEquals(MissionStatus.IN_PROGRESS, missionStore.list("p1").single { it.uid == m1.uid }.status)
        assertTrue(profiles.state("p2").coins > 222)
        assertEquals(111, profiles.state("p1").coins)
    }

    @Test fun `AI 동의 - 지금 프로필의 동의를 따른다`() = runTest {
        add("p1", 100); add("p2", 200)
        var calls = 0
        val g = graph(last = "p1", aiClient = AiClient { calls++; """{"report":"좋아","missions":[]}""" }, aiConsent = { it == "p2" })
        val recorder = DriveRecorder(g)
        val s1 = recorder.onSessionEnded(result(now, 90, minutes = 15, events = listOf(EventType.HARSH_BRAKE)), VehicleStatus(), now + 16 * minute)
        val r1 = g.ai.run(s1, AiCharacters.of(g.profiles, g.profileId, g.content.active), VehicleStatus(), now + 16 * minute)
        assertEquals(0, calls); assertFalse(r1.aiGenerated)
        assertTrue(g.selectProfile("p2"))
        val t = now + 60 * minute
        val s2 = recorder.onSessionEnded(result(t, 90, minutes = 15, events = listOf(EventType.HARSH_BRAKE)), VehicleStatus(), t + 16 * minute)
        val r2 = g.ai.run(s2, AiCharacters.of(g.profiles, g.profileId, g.content.active), VehicleStatus(), t + 16 * minute)
        assertEquals(1, calls); assertTrue(r2.aiGenerated)
    }

    // ---------- AddProfileModel ----------

    private var ids = 0
    private fun addModel(g: AppGraph) = AddProfileModel(
        g.profiles, characters = { g.content.active.characters }, navigation = {},
        deleteProfile = g::deleteProfile, newId = { "new-${++ids}" }, nowMs = { now + ids },
    )
    private fun AddProfileModel.create(name: String) { enter(); changeName(name); save() }

    @Test fun `프로필 추가 - 처음 만드는 프로필의 id 는 DEFAULT, 다음부터 newId`() {
        val g = graph()
        val add = addModel(g)
        add.create("첫째")
        assertEquals(listOf(D), profiles.profiles().map { it.id })
        add.create("둘째")
        assertEquals(setOf(D, "new-1"), profiles.profiles().map { it.id }.toSet())
    }

    @Test fun `프로필 추가 - 프로필 전에 쌓인 DEFAULT 주행이 첫 프로필 것이 된다`() {
        sessions.save(session("before", D, score = 64))
        val g = graph()
        addModel(g).create("첫째")
        assertTrue(g.selectProfile(D))
        val report = ReportModel({ g.profileId }, g.sessions, now = { now })
        report.refresh()
        assertEquals(64, report.state.value.session!!.score)
    }

    @Test fun `프로필 추가 - 모두 지운 뒤 다시 만들면 다시 DEFAULT`() {
        val g = graph()
        val add = addModel(g)
        add.create("첫째"); add.create("둘째")
        g.deleteProfile(D); g.deleteProfile("new-1")
        assertTrue(profiles.profiles().isEmpty())
        add.create("셋째")
        assertEquals(listOf(D), profiles.profiles().map { it.id })
    }

    @Test fun `프로필 추가 - clearProfiles 뒤 처음 만드는 프로필도 DEFAULT`() {
        add("p1", 100)
        val g = graph()
        g.clearProfiles()
        addModel(g).create("새로")
        assertEquals(listOf(D), profiles.profiles().map { it.id })
    }

    @Test fun `프로필 추가 - DEFAULT 만 지우고 다른 프로필이 남아 있으면 newId`() {
        val g = graph()
        val add = addModel(g)
        add.create("첫째"); add.create("둘째")
        g.deleteProfile(D)
        add.create("셋째")
        assertEquals(setOf("new-1", "new-2"), profiles.profiles().map { it.id }.toSet())
    }

    @Test fun `프로필 추가 - 다른 id 의 프로필이 이미 있으면 첫 저장이어도 newId`() {
        add("p1", 100)
        val g = graph()
        addModel(g).create("둘째")
        assertEquals(setOf("p1", "new-1"), profiles.profiles().map { it.id }.toSet())
    }

    @Test fun `프로필 추가 - 3개면 더 만들지 않는다`() {
        val g = graph()
        val add = addModel(g)
        add.create("1"); add.create("2"); add.create("3")
        assertEquals(3, profiles.profiles().size)
        add.create("4")
        assertEquals(3, profiles.profiles().size)
        assertFalse(profiles.profiles().any { it.name == "4" })
    }

    @Test fun `프로필 추가 - DEFAULT 로 만든 프로필도 3개 제한에 들어간다`() {
        sessions.save(session("before", D))
        val g = graph()
        val add = addModel(g)
        add.create("1"); add.create("2"); add.create("3"); add.create("4")
        assertEquals(3, profiles.profiles().size)
        assertTrue(profiles.profiles().any { it.id == D })
    }
}

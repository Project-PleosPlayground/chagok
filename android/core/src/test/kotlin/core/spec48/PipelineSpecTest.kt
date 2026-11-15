package core.spec48

import core.challengeSeatbelt
import core.Fx
import core.character.CharacterId
import core.character.CharacterProgress
import core.character.InMemoryProfileStore
import core.character.Profile
import core.content.BundledContent
import core.flow.SessionEndPipeline
import core.flow.SessionEndSummary
import core.item.ItemId
import core.item.StageItem
import core.item.StagePoint
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 결정 2, 4, 5: 세션 종료 보상 */
class PipelineSpecTest {
    private val pid = Profile.DEFAULT
    private val sessions = InMemorySessionStore()
    private val profiles = InMemoryProfileStore()
    private val missions = MissionEngine(
        store = InMemoryMissionStore(), sessions = sessions,
        missions = { BundledContent.pack.missions },
    )
    private val pipeline = SessionEndPipeline(sessions, profiles, missions)
    private val start = Fx.MON + 9 * 3_600_000L
    private val ok = VehicleStatus(seatBeltBuckledAtStart = true)

    private fun end(id: String, startMs: Long = start, valid: Boolean = true): SessionEndSummary {
        missions.challengeSeatbelt(pid, startMs - 1)
        val rec = Fx.record(id, startMs, km = 12f, valid = valid)
        return pipeline.onSessionEnd(rec, ok, startMs + 30 * 60_000L)
    }

    private fun select(id: Int) = profiles.saveState(profiles.state(pid).copy(selected = CharacterId(id)))

    @Test fun `유효 세션 - 코인은 프로필, xp 는 선택한 캐릭터에만`() {
        profiles.saveState(profiles.state(pid).copy(coins = 5, selected = CharacterId(2)))
        val other = CharacterProgress(pid, CharacterId(1), "하나", 50, 1)
        profiles.saveProgress(other)

        val sum = end("s1")
        assertTrue(sum.coinsGained > 0, "깨끗한 유효 세션은 코인을 받아야 함")
        assertTrue(sum.xpGained > 0, "깨끗한 유효 세션은 xp 를 받아야 함")
        assertEquals(5 + sum.coinsGained, sum.profile.coins)
        assertEquals(5 + sum.coinsGained, profiles.state(pid).coins)
        assertEquals(CharacterId(2), sum.profile.selected)

        val mine = profiles.progress(pid, CharacterId(2))
        assertEquals(sum.xpGained, mine.xp)
        assertEquals(mine, sum.character)
        assertEquals(other, profiles.progress(pid, CharacterId(1)), "다른 캐릭터 성장은 그대로")
        assertNotNull(sessions.get("s1"))
    }

    @Test fun `받은 코인과 xp 는 프로필과 선택 캐릭터에 그대로 반영`() {
        select(1)
        val sum = end("s1")
        assertTrue(sum.completed.isNotEmpty())
        assertEquals(sum.coinsGained, sum.profile.coins)
        assertEquals(sum.xpGained, sum.character!!.xp)
        assertEquals(CharacterId(1), sum.character!!.characterId)
        assertEquals(pid, sum.character!!.profileId)
    }

    @Test fun `유효 세션 - 선택 캐릭터 없으면 코인만, xp 없음`() {
        val sum = end("s1")
        assertTrue(sum.coinsGained > 0)
        assertEquals(0, sum.xpGained)
        assertNull(sum.leveledUpTo)
        assertNull(sum.character)
        assertEquals(sum.coinsGained, profiles.state(pid).coins)
        assertTrue(profiles.progresses(pid).isEmpty(), "어느 캐릭터에도 xp 가 저장되면 안 됨")
        assertNotNull(sessions.get("s1"))
    }

    @Test fun `xp 로 단계 경계를 넘으면 leveledUpTo`() {
        select(3)
        profiles.saveProgress(CharacterProgress(pid, CharacterId(3), xp = 99, stage = 1))
        val sum = end("s1")
        assertTrue(sum.xpGained > 0)
        assertEquals(2, sum.leveledUpTo)
        assertEquals(99 + sum.xpGained, profiles.progress(pid, CharacterId(3)).xp)
        assertEquals(2, profiles.progress(pid, CharacterId(3)).stage)
    }

    @Test fun `무효 세션 - 보상 없음, 상태와 성장 그대로, 기록은 저장`() {
        val before = profiles.state(pid).copy(coins = 40, selected = CharacterId(1),
            owned = setOf(ItemId(1)), placed = listOf(StageItem(ItemId(1))))
        profiles.saveState(before)
        val prog = CharacterProgress(pid, CharacterId(1), "하나", 80, 1)
        profiles.saveProgress(prog)

        val sum = end("bad", valid = false)
        assertEquals(0, sum.coinsGained)
        assertEquals(0, sum.xpGained)
        assertNull(sum.leveledUpTo)
        assertEquals(before, profiles.state(pid))
        assertEquals(prog, profiles.progress(pid, CharacterId(1)))
        assertEquals(listOf(prog), profiles.progresses(pid))
        assertNotNull(sessions.get("bad"))
    }

    @Test fun `캐릭터를 바꾸면 새 캐릭터가 xp 를 받고 이전 캐릭터 성장은 남는다`() {
        select(1)
        val s1 = end("s1")
        val first = profiles.progress(pid, CharacterId(1))
        assertEquals(s1.xpGained, first.xp)

        select(2)
        val s2 = end("s2", startMs = start + Fx.DAY)
        assertEquals(first, profiles.progress(pid, CharacterId(1)), "이전 캐릭터 성장 유지")
        assertEquals(s2.xpGained, profiles.progress(pid, CharacterId(2)).xp)
        assertEquals(s1.coinsGained + s2.coinsGained, profiles.state(pid).coins, "코인은 프로필에 누적")
    }

    @Test fun `세션 종료 후에도 가진 아이템과 스테이지 배치는 그대로`() {
        val it0 = BundledContent.pack.items.first()
        profiles.saveState(profiles.state(pid).copy(selected = CharacterId(5),
            owned = setOf(it0.id), placed = listOf(StageItem(it0.id, StagePoint(10f, 20f)))))
        val sum = end("s1")
        assertEquals(setOf(it0.id), sum.profile.owned)
        assertEquals(listOf(StageItem(it0.id, StagePoint(10f, 20f))), sum.profile.placed)
        assertEquals(sum.profile, profiles.state(pid))
    }
}

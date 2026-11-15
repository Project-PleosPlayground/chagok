package core.store

import core.Fx
import core.character.CharacterId
import core.character.CharacterProgress
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileStore
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import core.mission.MissionStore
import core.session.InMemorySessionStore
import core.session.SessionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 프로필 하나 지우기 (#111). 메모리 구현과 문서 저장 구현이 같게 동작하고, 문서 저장은 다시 열어도 지운 채다 */
class ProfileDeleteTest {

    private fun mission(uid: String, profile: String) = MissionInstance(
        uid, profile, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, 0f, 10, 10, "t", "d", null,
        offeredMs = 1L, validUntilMs = 2L, status = MissionStatus.OFFERED,
    )

    private fun fill(p: ProfileStore, s: SessionStore, m: MissionStore) {
        for (id in listOf("a", "b")) {
            p.upsert(Profile(id, id, 1L))
            p.saveState(p.state(id).copy(selected = CharacterId(1), coins = 10))
            p.saveProgress(CharacterProgress(id, CharacterId(1), xp = 5))
            p.saveProgress(CharacterProgress(id, CharacterId(2), xp = 7))
            s.save(Fx.record("s-$id", 100L, profile = id))
            m.upsert(mission("m-$id", id))
        }
    }

    private fun assertOnlyB(p: ProfileStore, s: SessionStore, m: MissionStore) {
        assertEquals(listOf("b"), p.profiles().map { it.id })
        assertNull(p.state("a").selected); assertEquals(0, p.state("a").coins)
        assertTrue(p.progresses("a").isEmpty())
        assertTrue(s.list("a").isEmpty()); assertNull(s.get("s-a"))
        assertTrue(m.list("a").isEmpty())
        // 남은 프로필은 그대로
        assertEquals(10, p.state("b").coins); assertEquals(2, p.progresses("b").size)
        assertEquals(listOf("s-b"), s.list("b").map { it.id }); assertEquals(listOf("m-b"), m.list("b").map { it.uid })
    }

    private fun delete(p: ProfileStore, s: SessionStore, m: MissionStore, id: String) {
        p.delete(id); s.deleteProfile(id); m.deleteProfile(id)
    }

    @Test fun `메모리 구현은 그 프로필의 데이터만 지운다`() {
        val p = InMemoryProfileStore(); val s = InMemorySessionStore(); val m = InMemoryMissionStore()
        fill(p, s, m); delete(p, s, m, "a")
        assertOnlyB(p, s, m)
    }

    @Test fun `문서 저장 구현은 지운 문서가 남지 않아 다시 열어도 지운 채다`() {
        val docs = InMemoryDocumentStore()
        val p = DocumentProfileStore(docs); val s = DocumentSessionStore(docs); val m = DocumentMissionStore(docs)
        fill(p, s, m); delete(p, s, m, "a")
        assertOnlyB(p, s, m)
        assertOnlyB(DocumentProfileStore(docs), DocumentSessionStore(docs), DocumentMissionStore(docs))
        assertEquals(setOf("b"), docs.readAll(Collections.PROFILES).keys)
        assertEquals(setOf("b"), docs.readAll(Collections.PROFILE_STATES).keys)
        assertEquals(2, docs.readAll(Collections.CHARACTER_PROGRESS).size)
        assertEquals(setOf("s-b"), docs.readAll(Collections.SESSIONS).keys)
        assertEquals(setOf("m-b"), docs.readAll(Collections.MISSIONS).keys)
    }

    @Test fun `없는 프로필을 지워도 아무 일이 없다`() {
        val docs = InMemoryDocumentStore()
        val p = DocumentProfileStore(docs); val s = DocumentSessionStore(docs); val m = DocumentMissionStore(docs)
        fill(p, s, m); delete(p, s, m, "none")
        assertEquals(2, p.profiles().size); assertEquals(2, docs.readAll(Collections.SESSIONS).size)
    }
}

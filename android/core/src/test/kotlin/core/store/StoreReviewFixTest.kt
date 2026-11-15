package core.store

import core.character.CharacterId
import core.character.ProfileState
import core.mission.ConditionType
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** PR #110 리뷰 반영: 건너뛴 문서 보존, 새 schema 문서 읽지 않기, 같은 시각 미션 순서 */
class StoreReviewFixTest {

    @Test fun `건너뛴 문서는 같은 키로 다시 써도 원본이 옆 모음에 남는다`() {
        val docs = InMemoryDocumentStore()
        docs.write(Collections.PROFILE_STATES, "default", "깨진 문서")
        val skipped = mutableListOf<String>()
        val store = DocumentProfileStore(docs, onSkip = { skipped += it })
        // 앱 시작 때 처음 캐릭터를 고른 상태로 같은 키를 쓰는 흐름 (AppGraph.ensureStarterCharacter)
        store.saveState(ProfileState("default", selected = CharacterId(1)))
        assertEquals(listOf("${Collections.PROFILE_STATES}/default"), skipped)
        assertEquals("깨진 문서", docs.readAll(Collections.skipped(Collections.PROFILE_STATES))["default"])
        assertEquals(CharacterId(1), DocumentProfileStore(docs).state("default").selected)
    }

    @Test fun `앱보다 새 schema 문서는 읽지 않고 원본을 남긴다`() {
        val docs = InMemoryDocumentStore()
        val newer = """{"schema":${StoreCodec.SCHEMA + 1},"updatedMs":1,"data":{"profileId":"p1","coins":99,"future":true}}"""
        docs.write(Collections.PROFILE_STATES, "p1", newer)
        val store = DocumentProfileStore(docs)
        assertEquals(0, store.state("p1").coins)
        assertEquals(newer, docs.readAll(Collections.skipped(Collections.PROFILE_STATES))["p1"])
    }

    @Test fun `지금 schema 문서는 그대로 읽는다`() {
        val docs = InMemoryDocumentStore()
        docs.write(Collections.PROFILE_STATES, "p1", """{"schema":${StoreCodec.SCHEMA},"updatedMs":1,"data":{"profileId":"p1","coins":99}}""")
        assertEquals(99, DocumentProfileStore(docs).state("p1").coins)
        assertTrue(docs.readAll(Collections.skipped(Collections.PROFILE_STATES)).isEmpty())
    }

    @Test fun `schema 가 없는 문서는 읽지 않는다`() {
        assertNull(StoreCodec.decodeProfileState("""{"updatedMs":1,"data":{"profileId":"p1"}}"""))
    }

    private fun mission(uid: String) = MissionInstance(
        uid, "p1", MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1, 0f, 10, 10, uid, "d", null,
        offeredMs = 100L, validUntilMs = 200L, status = MissionStatus.OFFERED,
    )

    @Test fun `같은 시각에 제공된 미션은 다시 열어도 넣은 순서다`() {
        val docs = InMemoryDocumentStore()
        val store = DocumentMissionStore(docs)
        // uid 순서(a < m < z)와 다르게 넣는다
        val order = listOf("z", "a", "m")
        order.forEach { store.upsert(mission(it)) }
        // 고쳐 써도 순서는 처음 넣은 때를 따른다
        store.upsert(mission("a").copy(status = MissionStatus.IN_PROGRESS))
        assertEquals(order, store.list("p1").map { it.uid })
        val reopened = DocumentMissionStore(docs)
        assertEquals(order, reopened.list("p1").map { it.uid })
        // 다시 연 뒤 새로 넣은 미션은 맨 뒤
        reopened.upsert(mission("b"))
        assertEquals(order + "b", DocumentMissionStore(docs).list("p1").map { it.uid })
    }
}

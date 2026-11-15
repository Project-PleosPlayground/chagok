package ai.safetypoc.app.ui.transform

import ai.safetypoc.app.settings.MemoryFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransformModelTest {
    /** 그랜저만 2~5단계 영상이 있다 */
    private val files = (2..5).map { TransformModel.path("grandeur", it) }.toSet()
    private val model = TransformModel(exists = { it in files })

    @Test fun `레벨업 - 오른 단계 한 편만, 끝나면 닫힌다`() {
        model.onLeveledUp("grandeur", 3)
        assertEquals(listOf(3), model.state.value.clips.map { it.toStage })
        model.next()
        assertNull(model.state.value.current)
    }

    @Test fun `다시 보기 - 2단계부터 지금 단계까지 차례로`() {
        model.replay("grandeur", 4)
        assertEquals(listOf(2, 3, 4), model.state.value.clips.map { it.toStage })
        model.next()
        assertEquals(3, model.state.value.current?.toStage)
        model.close()
        assertNull(model.state.value.current)
    }

    @Test fun `말풍선 - 캐릭터를 눌러 변신을 보면 지우고, 저장해 둔다`() {
        val flags = MemoryFlags()
        val m = TransformModel(exists = { it in files }, flags = flags)
        assertFalse(m.hintSeen.value)
        m.replay("grandeur", 3)
        assertTrue(m.hintSeen.value)
        assertTrue(TransformModel(exists = { it in files }, flags = flags).hintSeen.value)
    }

    @Test fun `말풍선 - 볼 영상이 없으면 눌러도 남긴다`() {
        model.replay("grandeur", 1)
        assertFalse(model.hintSeen.value)
    }

    @Test fun `다시 보기 - 이어 붙인 영상이 있으면 1단계부터 한 편으로`() {
        val withSeq = TransformModel(exists = { it in files || it == TransformModel.seqPath("grandeur", 4) })
        withSeq.replay("grandeur", 4)
        val c = withSeq.state.value.clips.single()
        assertEquals(TransformModel.seqPath("grandeur", 4), c.path)
        assertEquals(1, c.fromStage); assertEquals(4, c.toStage); assertEquals(3, c.steps)
    }

    @Test fun `1단계는 볼 영상이 없다`() {
        assertTrue(model.clipsUpTo("grandeur", 1).isEmpty())
        model.replay("grandeur", 1)
        assertNull(model.state.value.current)
    }

    @Test fun `영상이 없는 차종은 그랜저 영상을 쓴다`() {
        model.onLeveledUp("avante", 2)
        assertEquals(TransformModel.path("grandeur", 2), model.state.value.current?.path)
        assertEquals(2, model.clipsUpTo(null, 3).size)
    }

    @Test fun `영상 파일이 없는 단계는 건너뛴다`() {
        val partial = TransformModel(exists = { it == TransformModel.path("grandeur", 3) })
        partial.replay("grandeur", 5)
        assertEquals(listOf(3), partial.state.value.clips.map { it.toStage })
        partial.onLeveledUp("grandeur", 2)
        assertEquals(listOf(3), partial.state.value.clips.map { it.toStage })
    }

    @Test fun `이름 뒤 조사 - 받침 있으면 이, 없으면 가`() {
        assertEquals("이", subjectParticle("차곡")); assertEquals("가", subjectParticle("붕붕이")); assertEquals("가", subjectParticle("Grand"))
    }
}

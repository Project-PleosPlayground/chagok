package ai.safetypoc.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 알림 상태: 띄우기, 다시 띄우기, 내리기 */
class NoticeHostStateTest {
    private val slotFull = "도전 슬롯이 가득 찼어요."

    @Test
    fun `띄우면 떠 있는 알림이 된다`() {
        val state = NoticeHostState()
        assertNull(state.current)
        state.show(slotFull)
        assertEquals(slotFull, state.current?.text)
    }

    @Test
    fun `같은 글을 다시 띄우면 순번이 바뀌어 타이머를 다시 잰다`() {
        val state = NoticeHostState()
        state.show(slotFull)
        val first = state.current
        state.show(slotFull)
        assertEquals(slotFull, state.current?.text)
        assertNotEquals(first, state.current)
    }

    @Test
    fun `앞의 알림 시간이 끝나도 그 뒤에 다시 띄운 알림은 남는다`() {
        val state = NoticeHostState()
        state.show(slotFull)
        val first = state.current!!
        state.show(slotFull)
        val second = state.current!!
        state.dismiss(first)
        assertEquals(second, state.current)
        state.dismiss(second)
        assertNull(state.current)
    }
}

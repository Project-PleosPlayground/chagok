package ai.safetypoc.app.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

/** 포인트 숫자 표기 (디자인 시스템 8절: 천 단위 쉼표) */
class FormatPointsTest {
    @Test
    fun `천 단위마다 쉼표를 넣는다`() {
        assertEquals("33,456", formatPoints(33456))
        assertEquals("1,000,000", formatPoints(1_000_000))
    }

    @Test
    fun `천 미만은 쉼표가 없다`() {
        assertEquals("0", formatPoints(0))
        assertEquals("120", formatPoints(120))
    }
}

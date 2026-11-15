package core.ai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class AiReportPlaceTest {
    private val r = AiReport(
        "{장소}에서 또 급하게 멈췄어요.", "다음엔 {장소}에서 천천히 출발해 보세요.",
        listOf("같이 달렸어!", "{장소}에서 깜짝 놀랐어!", "깜빡이 좋았어!", "다음엔 {장소}에서 천천히!"),
    )

    @Test fun `장소 빈칸은 기기 안 도로 이름으로 채운다`() {
        val f = r.withPlace("테헤란로")
        assertEquals("테헤란로에서 또 급하게 멈췄어요.", f.report)
        assertEquals("다음엔 테헤란로에서 천천히 출발해 보세요.", f.tip)
        assertEquals("다음엔 테헤란로에서 천천히!", f.panels!![3])
    }

    @Test fun `도로 이름이 없으면 그곳`() {
        assertEquals("그곳에서 깜짝 놀랐어!", r.withPlace(null).panels!![1])
        assertEquals("그곳에서 또 급하게 멈췄어요.", r.withPlace(" ").report)
    }
}

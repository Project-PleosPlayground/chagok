package ai.safetypoc.app.ui.report

import core.ai.Hotspots
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapShapesTest {
    @Test fun `반경 원의 꼭짓점은 모두 중심에서 반경만큼 떨어진 닫힌 고리다`() {
        val ring = MapShapes.circle(37.4979, 127.0276, 200.0)
        assertEquals(49, ring.size); assertEquals(ring.first(), ring.last())
        for ((la, ln) in ring) {
            val d = Hotspots.distanceM(37.4979, 127.0276, la, ln)
            assertTrue("200m 에서 1m 안쪽: $d", kotlin.math.abs(d - 200.0) < 1.0)
        }
    }

    @Test fun `점들을 다 담는 범위`() {
        assertNull(MapShapes.bounds(emptyList()))
        assertArrayEquals(doubleArrayOf(37.49, 127.02, 37.51, 127.05), MapShapes.bounds(listOf(37.5 to 127.05, 37.49 to 127.03, 37.51 to 127.02))!!, 1e-9)
    }
}

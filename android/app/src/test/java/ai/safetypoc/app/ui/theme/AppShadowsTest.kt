package ai.safetypoc.app.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 그림자 겹의 불투명도 범위 */
class AppShadowsTest {
    @Test
    fun `디자인 시스템 그림자는 모두 만들 수 있다`() {
        assertTrue(listOf(AppShadows.card, AppShadows.float, AppShadows.soft).all { it.layers.isNotEmpty() })
    }

    @Test
    fun `불투명도 1이나 0인 겹은 만들 수 없다`() {
        assertThrows(IllegalArgumentException::class.java) { ShadowLayer(4.dp, 4.dp, 1f) }
        assertThrows(IllegalArgumentException::class.java) { ShadowLayer(4.dp, 4.dp, 0f) }
    }
}

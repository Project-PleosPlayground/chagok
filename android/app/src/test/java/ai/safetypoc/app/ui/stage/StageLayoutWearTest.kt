package ai.safetypoc.app.ui.stage

import core.content.BundledContent
import core.item.ItemSlot
import core.item.StageItem
import core.item.StagePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class StageLayoutWearTest {
    private val pack = BundledContent.pack
    private val hat = pack.items.first { it.imageKey in StageLayout.HEAD_WEAR }.id
    private val cloud = pack.items.first { it.imageKey == "Accessories/Cloud" }.id

    @Test fun `산타 모자는 옮기지 않았으면 지금 단계 머리에 기울여 씌운다`() {
        for (stage in 1..5) {
            val v = StageLayout.views(pack, listOf(StageItem(hat)), characterStage = stage).single()
            assertEquals(StageLayout.headWearPoint(stage), v.position)
            assertEquals(StageLayout.HEAD_WEAR_TILT, v.tilt)
        }
    }

    @Test fun `옮긴 모자는 그 자리, 다른 소품은 머리 위 자리에 기울이지 않는다`() {
        val moved = StagePoint(100f, 200f)
        assertEquals(moved, StageLayout.views(pack, listOf(StageItem(hat, moved)), characterStage = 3).single().position)
        val c = StageLayout.views(pack, listOf(StageItem(cloud)), characterStage = 3).single()
        assertEquals(StageLayout.slotPoint(ItemSlot.ABOVE_HEAD), c.position)
        assertEquals(0f, c.tilt)
    }
}

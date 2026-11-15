package core.item

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** 08 "스테이지 배치" */
class StageRulesTest {
    private val pet1 = ItemDef(ItemId(1), ItemCategory.PET, "pet_1_name", "pet_1", 10)
    private val pet2 = ItemDef(ItemId(2), ItemCategory.PET, "pet_2_name", "pet_2", 10)
    private val prop = ItemDef(ItemId(3), ItemCategory.PROP, "prop_name", "prop", 10)
    private val bg1 = ItemDef(ItemId(4), ItemCategory.BACKGROUND, "bg_1_name", "bg_1", 10)
    private val bg2 = ItemDef(ItemId(5), ItemCategory.BACKGROUND, "bg_2_name", "bg_2", 10)
    private val catalog = listOf(pet1, pet2, prop, bg1, bg2).associateBy { it.id }
    private val items: (ItemId) -> ItemDef? = { catalog[it] }
    private fun ids(stage: List<StageItem>) = stage.map { it.itemId.value }

    @Test fun `여러 개 놓고, 새로 놓은 것이 맨 위다`() {
        var s = StageRules.place(emptyList(), pet1, items)
        s = StageRules.place(s, prop, items)
        s = StageRules.place(s, pet2, items)
        assertEquals(listOf(1, 3, 2), ids(s))
        assertEquals(listOf(null, null, null), s.map { it.position }, "처음에는 처음 자리")
    }

    @Test fun `같은 아이템은 한 번만 놓고, 이미 있으면 순서도 그대로다`() {
        val s = StageRules.place(StageRules.place(emptyList(), pet1, items), prop, items)
        assertSame(s, StageRules.place(s, pet1, items))
    }

    @Test fun `배경도 여러 개 겹쳐 놓는다(트리·눈)`() {
        var s = StageRules.place(StageRules.place(emptyList(), bg1, items), pet1, items)
        s = StageRules.place(s, bg2, items)
        assertEquals(3, s.size)
    }

    @Test fun `옮긴 아이템은 그 위치로 가고 맨 위로 온다`() {
        val s = listOf(StageItem(ItemId(1)), StageItem(ItemId(3)), StageItem(ItemId(2)))
        val moved = StageRules.move(s, ItemId(1), StagePoint(300f, 900f))
        assertEquals(listOf(3, 2, 1), ids(moved))
        assertEquals(StagePoint(300f, 900f), moved.last().position)
        assertSame(s, StageRules.move(s, ItemId(9), StagePoint(0f, 0f)), "놓이지 않은 아이템은 그대로")
    }

    @Test fun `빼면 그 아이템만 빠지고 나머지 순서와 위치는 그대로다`() {
        val s = listOf(StageItem(ItemId(1), StagePoint(1f, 2f)), StageItem(ItemId(3)), StageItem(ItemId(2), StagePoint(5f, 6f)))
        assertEquals(listOf(s[0], s[2]), StageRules.remove(s, ItemId(3)))
    }

    @Test fun `저장할 때 도감에 없는 것과 가지지 않은 것은 뺀다`() {
        val s = listOf(StageItem(ItemId(1)), StageItem(ItemId(99)), StageItem(ItemId(2)))
        assertEquals(listOf(1), ids(StageRules.sanitize(s, owned = setOf(ItemId(1), ItemId(99)), items)))
    }

    @Test fun `저장할 때 같은 아이템은 맨 위 하나만 남기고 배경은 여럿 둔다`() {
        val s = listOf(
            StageItem(ItemId(1), StagePoint(1f, 1f)), StageItem(ItemId(4)), StageItem(ItemId(3)),
            StageItem(ItemId(1), StagePoint(2f, 2f)), StageItem(ItemId(5)),
        )
        val kept = StageRules.sanitize(s, owned = catalog.keys, items)
        assertEquals(listOf(4, 3, 1, 5), ids(kept))
        assertEquals(StagePoint(2f, 2f), kept[2].position)
    }
}

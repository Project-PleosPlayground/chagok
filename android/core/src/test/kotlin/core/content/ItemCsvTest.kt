package core.content

import core.item.ItemCategory
import core.item.ItemId
import core.item.ItemSlot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemCsvTest {
    private val items = """
        아이템ID,이름,종류,이미지,구매_포인트
        1,item_1_name,펫,Pet/Turtle/Turtle_Lv1,200
        16,item_16_name,소품,Accessories/Fortune_pouch,150
    """.trimIndent()
    private val texts = """
        키,문구,메모
        item_1_name,현무 Lv1,
        item_16_name,복주머니,
        mission_brake_name,급제동 {n}회 이하,아이템이 아님
    """.trimIndent()

    @Test fun `데이터 컬럼 사전 형식의 아이템 CSV 를 옮긴다`() {
        val r = ItemCsv.read(items, texts)
        assertEquals(emptyList<String>(), r.problems)
        val pet = r.items!!.single { it.id == ItemId(1) }
        assertEquals(ItemCategory.PET, pet.category); assertEquals("Pet/Turtle/Turtle_Lv1", pet.imageKey); assertEquals(200, pet.price)
        assertEquals(ItemCategory.PROP, r.items!!.single { it.id == ItemId(16) }.category)
        assertEquals(ItemSlot.BOTTOM_RIGHT, pet.slot); assertEquals(ItemSlot.ABOVE_HEAD, r.items!!.single { it.id == ItemId(16) }.slot, "자리는 종류로 채운다")
        assertEquals(mapOf("item_1_name" to "현무 Lv1", "item_16_name" to "복주머니"), r.texts, "아이템이 가리키는 문구만")
    }

    @Test fun `종류가 펫·소품·배경이 아니거나 숫자가 아니면 줄 번호와 함께 알려 준다`() {
        val bad = ItemCsv.read(items.replace(",펫,", ",모자,").replace(",150", ",싸게"), texts)
        assertNull(bad.items)
        assertTrue(bad.problems.any { "2행" in it && "종류" in it }, "${bad.problems}")
        assertTrue(bad.problems.any { "3행" in it && "구매_포인트" in it }, "${bad.problems}")
    }

    @Test fun `내장 아이템은 같은 형식의 CSV 에서 만든 것과 같다`() {
        val p = BundledContent.pack
        val csv = "아이템ID,이름,종류,이미지,구매_포인트\n" + p.items.joinToString("\n") { "${it.id},${it.nameKey},${it.category.label},${it.imageKey},${it.price}" }
        // 장소 이름(place_*)은 아이템 이름이 아니라 CSV 문구 표에 넣지 않는다
        val names = p.itemTexts.filterKeys { !it.startsWith("place_") }
        val txt = "키,문구\n" + names.entries.joinToString("\n") { "${it.key},${it.value}" }
        val r = ItemCsv.read(csv, txt)
        // 장소 선물(place)은 아직 CSV 컬럼이 없다. 나머지 칸만 같은지 본다
        assertEquals(p.items.map { it.copy(place = null) }, r.items); assertEquals(names, r.texts)
    }

    @Test fun `아이템 이름 키에 문구가 없거나 이미지 경로가 규칙에 맞지 않으면 콘텐츠 검사에 걸린다`() {
        val p = BundledContent.pack
        val first = p.items.first()
        assertTrue(ContentValidator.problems(p.copy(itemTexts = p.itemTexts - first.nameKey)).any { "이름 키" in it })
        assertTrue(ContentValidator.problems(p.copy(items = listOf(first.copy(imageKey = "Pet/Turtle/Turtle Lv1.png")) + p.items.drop(1))).any { "이미지 경로" in it })
    }
}

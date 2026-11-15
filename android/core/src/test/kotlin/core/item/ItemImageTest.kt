package core.item

import core.content.BundledContent
import core.content.ContentValidator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ItemImageTest {
    @Test fun `이미지 경로를 assets 경로로 바꾼다`() {
        assertEquals("item/Pet/Haechi/Haechi_Lv1.png", ItemImage.assetPath("Pet/Haechi/Haechi_Lv1"))
        assertEquals("item/Accessories/Fortune_pouch.png", ItemImage.assetPath("Accessories/Fortune_pouch"))
        assertEquals("item/cloud.png", ItemImage.assetPath("cloud"), "조각 하나도 경로다")
    }

    @Test fun `규칙에 맞지 않는 값은 assets 경로가 없다`() {
        for (bad in listOf("", "/Pet/Bird", "Pet/Bird/", "Pet//Bird", "Pet/Bird.png", "Pet/../Bird", "Pet/Bird Lv1", "Pet\\Bird", "펫/새"))
            assertNull(ItemImage.assetPath(bad), bad)
    }

    @Test fun `내장 아이템의 이미지 값은 모두 경로 규칙에 맞는다`() {
        val bad = BundledContent.pack.items.filterNot { ItemImage.isValid(it.imageKey) }
        assertEquals(emptyList<ItemDef>(), bad)
    }

    @Test fun `아이템 이미지 규칙은 캐릭터 그림 폴더·미션 아이콘 규칙을 바꾸지 않는다`() {
        val p = BundledContent.pack
        val c = p.characters.first()
        assertTrue(ContentValidator.problems(p.copy(characters = listOf(c.copy(assetKey = "Avante/Lv1")) + p.characters.drop(1)))
            .any { "그림 폴더 이름" in it }, "캐릭터는 여전히 영문 소문자·숫자·밑줄만")
        val m = p.missions
        val row = m.conditions.first()
        assertTrue(ContentValidator.problems(p.copy(missions = m.copy(conditions = listOf(row.copy(iconKey = "Icon/Brake")) + m.conditions.drop(1))))
            .any { "아이콘 이름" in it }, "미션 아이콘도 그대로")
    }
}

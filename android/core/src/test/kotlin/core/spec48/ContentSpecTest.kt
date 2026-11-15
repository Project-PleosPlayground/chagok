package core.spec48

import core.character.CharacterDef
import core.character.CharacterId
import core.content.BundledContent
import core.content.ContentCodec
import core.content.ContentPack
import core.content.ContentValidator
import core.item.ItemCategory
import core.item.ItemDef
import core.item.ItemId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 결정 6, 7, 10: 내장 콘텐츠, 검증, JSON 의 id 형식 */
class ContentSpecTest {
    private val base: ContentPack = BundledContent.pack

    private fun ch(id: Int, key: String, name: String = "이름$id") = CharacterDef(CharacterId(id), name, key)
    private fun item(id: Int, cat: ItemCategory = ItemCategory.PROP) = ItemDef(ItemId(id), cat, "item_${id}_name", "item_$id", 10)

    private fun assertProblem(p: ContentPack, why: String) {
        val probs = ContentValidator.problems(p)
        assertTrue(probs.isNotEmpty(), "문제를 보고해야 함: $why")
    }

    // ---- 결정 7: 내장 콘텐츠 ----
    @Test fun `내장 캐릭터는 3개, id 3·6·7`() {
        assertEquals(listOf(3, 6, 7), base.characters.map { it.id.value }.sorted())
    }

    @Test fun `내장 캐릭터 assetKey 는 id 순서로 grandeur ioniq3 ioniq5`() {
        val keys = base.characters.sortedBy { it.id.value }.map { it.assetKey }
        assertEquals(listOf("grandeur", "ioniq3", "ioniq5"), keys)
    }

    @Test fun `내장 캐릭터 이름은 비어 있지 않다`() {
        base.characters.forEach { assertTrue(it.name.isNotBlank(), "id=${it.id.value}") }
    }

    @Test fun `내장 아이템은 28개, id 1부터 28, 양수이고 중복 없음`() {
        val ids = base.items.map { it.id.value }
        assertEquals(28, ids.size)
        assertEquals((1..28).toList(), ids.sorted())
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it >= 1 })
    }

    @Test fun `내장 콘텐츠는 검증을 통과한다`() {
        assertEquals(emptyList<String>(), ContentValidator.problems(base))
    }

    // ---- 결정 6: 검증 ----
    @Test fun `캐릭터 없음은 문제`() = assertProblem(base.copy(characters = emptyList()), "캐릭터 없음")

    @Test fun `캐릭터 id 중복은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "a"), ch(1, "b"))), "id 중복")

    @Test fun `캐릭터 assetKey 중복은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "same"), ch(2, "same"))), "assetKey 중복")

    @Test fun `캐릭터 id 0 은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(0, "a"), ch(2, "b"))), "id 0")

    @Test fun `캐릭터 id 음수는 문제`() =
        assertProblem(base.copy(characters = listOf(ch(-1, "a"), ch(2, "b"))), "id -1")

    @Test fun `캐릭터 이름 빈 문자열은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "a", name = ""), ch(2, "b"))), "이름 빈 문자열")

    @Test fun `캐릭터 이름 공백만은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "a", name = "   "), ch(2, "b"))), "이름 공백")

    @Test fun `assetKey 대문자는 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "Avante"))), "대문자")

    @Test fun `assetKey 하이픈은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "santa-fe"))), "하이픈")

    @Test fun `assetKey 공백 포함은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "santa fe"))), "공백")

    @Test fun `assetKey 빈 문자열은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, ""))), "빈 assetKey")

    @Test fun `assetKey 한글은 문제`() =
        assertProblem(base.copy(characters = listOf(ch(1, "아반떼"))), "한글")

    @Test fun `assetKey 소문자 숫자 밑줄은 통과`() {
        val p = base.copy(characters = listOf(ch(1, "a_1"), ch(2, "car_2_x"), ch(3, "9")))
        assertEquals(emptyList<String>(), ContentValidator.problems(p))
    }

    @Test fun `아이템 id 0 은 문제`() =
        assertProblem(base.copy(items = base.items + item(0)), "아이템 id 0")

    @Test fun `아이템 id 음수는 문제`() =
        assertProblem(base.copy(items = base.items + item(-5)), "아이템 id 음수")

    @Test fun `아이템 id 중복은 문제`() {
        val first = base.items.first()
        assertProblem(base.copy(items = base.items + first.copy(nameKey = "item_copy_name")), "아이템 id 중복")
    }

    @Test fun `id 는 목록 순서와 무관하다 - 역순, 띄엄띄엄 id 도 통과`() {
        val p = base.copy(characters = listOf(ch(9, "z"), ch(3, "y"), ch(7, "x")))
        assertEquals(emptyList<String>(), ContentValidator.problems(p))
        val q = base.copy(items = base.items.reversed())
        assertEquals(emptyList<String>(), ContentValidator.problems(q))
    }

    // ---- 결정 10: JSON ----
    @Test fun `JSON 에 id 는 정수로 쓴다`() {
        val p = base.copy(characters = listOf(ch(7, "seven")), items = listOf(item(42)))
        val text = ContentCodec.encode(p)
        assertTrue(Regex("\"id\"\\s*:\\s*7\\b").containsMatchIn(text), "캐릭터 id 7 이 정수로 있어야 함")
        assertTrue(Regex("\"id\"\\s*:\\s*42\\b").containsMatchIn(text), "아이템 id 42 가 정수로 있어야 함")
        // 캐릭터·아이템 배열 안의 id 만 본다 (미션 id 는 #48 범위 밖)
        val root = Json.parseToJsonElement(text).jsonObject
        for (key in listOf("characters", "items")) {
            root.getValue(key).jsonArray.forEach { e ->
                val id = e.jsonObject.getValue("id")
                assertTrue(id is JsonPrimitive && !id.jsonPrimitive.isString && id.jsonPrimitive.intOrNull != null,
                    "$key 의 id 는 정수여야 함: $id")
            }
        }
    }

    @Test fun `내장 콘텐츠 JSON 의 캐릭터 아이템 id 도 정수`() {
        val root = Json.parseToJsonElement(ContentCodec.encode(base)).jsonObject
        for (key in listOf("characters", "items")) {
            root.getValue(key).jsonArray.forEach { e ->
                val id = e.jsonObject.getValue("id")
                assertTrue(id is JsonPrimitive && !id.jsonPrimitive.isString && id.jsonPrimitive.intOrNull != null,
                    "$key 의 id 는 정수여야 함: $id")
            }
        }
    }

    @Test fun `JSON 왕복은 같은 값`() {
        val back = ContentCodec.decode(ContentCodec.encode(base))
        assertEquals(base, back)
    }

    @Test fun `정수 id 를 쓴 JSON 을 읽는다`() {
        val p = base.copy(characters = listOf(ch(7, "seven")), items = listOf(item(42)))
        val back = ContentCodec.decode(ContentCodec.encode(p))
        assertEquals(CharacterId(7), back.characters.single().id)
        assertEquals(ItemId(42), back.items.single().id)
    }
}

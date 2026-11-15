package core.content

import core.item.ItemCategory
import core.item.ItemDef
import core.item.ItemId
import core.item.ItemSlot

/**
 * 기획 스프레드시트의 아이템 탭을 아이템 목록으로 옮긴다. 컬럼은 기획/데이터_컬럼_사전.md 의 "아이템"을 따른다.
 * 아이템 이름 문구는 콘텐츠 텍스트 탭에서 아이템이 가리키는 키만 가져온다. 자리(slot)는 CSV 컬럼이 없어 종류로 채운다.
 */
object ItemCsv {
    const val ITEMS_FILE = "아이템.csv"

    private const val ITEM_ID = "아이템ID"
    private const val CATEGORY = "종류"
    private const val NAME = "이름"
    private const val IMAGE = "이미지"
    private const val PRICE = "구매_포인트"

    /** 옮긴 결과. [problems] 가 비어 있지 않으면 [items]·[texts] 는 null */
    class Result(val items: List<ItemDef>?, val texts: Map<String, String>?, val problems: List<String>)

    fun read(itemsCsv: String, textsCsv: String): Result {
        val reader = Csv.Reader()
        val table = reader.table(ITEMS_FILE, itemsCsv, listOf(ITEM_ID, CATEGORY, NAME, IMAGE, PRICE))
        val allTexts = reader.texts(MissionCsv.TEXTS_FILE, textsCsv)
        if (reader.problems.isNotEmpty() || table == null || allTexts == null) return Result(null, null, reader.problems)
        val items = table.rows.mapNotNull { r ->
            val id = reader.int(ITEMS_FILE, r, ITEM_ID); val price = reader.int(ITEMS_FILE, r, PRICE)
            val category = ItemCategory.byLabel(r[CATEGORY])
                ?: null.also { reader.problems += "$ITEMS_FILE ${r.line}행: $CATEGORY 는 ${ItemCategory.entries.joinToString("·") { it.label }} 중 하나여야 한다: '${r[CATEGORY]}'" }
            if (id == null || price == null || category == null) null
            else ItemDef(ItemId(id), category, nameKey = r[NAME], imageKey = r[IMAGE], price = price, slot = ItemSlot.forCategory(category))
        }
        if (reader.problems.isNotEmpty()) return Result(null, null, reader.problems)
        val used = items.map { it.nameKey }.toSet()
        return Result(items, allTexts.filterKeys { it in used }, emptyList())
    }
}

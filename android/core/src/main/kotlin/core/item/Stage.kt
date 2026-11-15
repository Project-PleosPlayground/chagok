package core.item

/** 스테이지에 놓인 아이템 하나 (08 "스테이지 배치"). [position] 이 null 이면 아직 옮기지 않아 처음 자리([ItemDef.slot])에 있다 */
data class StageItem(val itemId: ItemId, val position: StagePoint? = null)

/**
 * 스테이지 배치 규칙 (08 "스테이지 배치"). 배치 목록은 아래에서 위 순서이고, 마지막이 맨 위다.
 * - 아이템은 여러 개 놓고, 같은 아이템은 한 번만 놓는다. 배경도 여러 개 놓는다(트리·눈처럼 겹쳐 쓰는 배경, 11/15).
 * - 새로 놓거나 옮긴 아이템이 맨 위로 온다.
 * 배경은 그릴 때 항상 맨 아래라(스펙_스테이지 "렌더 순서") 목록 안 순서와 무관하다.
 */
object StageRules {
    fun contains(stage: List<StageItem>, id: ItemId): Boolean = stage.any { it.itemId == id }

    /** [item] 을 처음 자리에 맨 위로 놓는다. 이미 있으면 그대로 */
    @Suppress("UNUSED_PARAMETER")
    fun place(stage: List<StageItem>, item: ItemDef, items: (ItemId) -> ItemDef?): List<StageItem> {
        if (contains(stage, item.id)) return stage
        return stage + StageItem(item.id)
    }

    fun remove(stage: List<StageItem>, id: ItemId): List<StageItem> = stage.filter { it.itemId != id }

    /** [id] 를 [to] 로 옮기고 맨 위로 올린다. 놓이지 않은 아이템이면 그대로 */
    fun move(stage: List<StageItem>, id: ItemId, to: StagePoint): List<StageItem> {
        val moved = stage.firstOrNull { it.itemId == id } ?: return stage
        return remove(stage, id) + moved.copy(position = to)
    }

    /**
     * 저장할 수 있는 배치로 정리한다. 도감에 없는 것과 가지지 않은 것은 뺀다.
     * 같은 아이템이 여럿이면 맨 위 하나만 남긴다. 남은 것의 순서는 그대로다.
     */
    fun sanitize(stage: List<StageItem>, owned: Set<ItemId>, items: (ItemId) -> ItemDef?): List<StageItem> {
        val seen = HashSet<ItemId>()
        val keptFromTop = stage.asReversed().filter { s ->
            items(s.itemId) ?: return@filter false
            s.itemId in owned && seen.add(s.itemId)
        }
        return keptFromTop.asReversed()
    }
}

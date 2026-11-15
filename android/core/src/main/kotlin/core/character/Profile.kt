package core.character

import core.item.ItemId
import core.item.StageItem

/** 프로필. 피그마 "프로필 선택" 화면 전제로 여러 개 가능. 하나만 쓰기로 하면 DEFAULT 하나만 만들면 된다. */
data class Profile(val id: String, val name: String, val createdMs: Long) {
    companion object { const val DEFAULT = "default" }
}

/** 프로필의 고른 캐릭터, 코인, 아이템 (#48) */
data class ProfileState(
    val profileId: String,
    /** 고른 캐릭터. 아직 고르지 않았으면 null */
    val selected: CharacterId? = null,
    val coins: Int = 0,
    /** 가진 아이템. 도감 = 인벤토리라 같은 아이템은 한 번만 들어간다 */
    val owned: Set<ItemId> = emptySet(),
    /** 스테이지에 놓인 아이템. 아래에서 위 순서(마지막이 맨 위). 상점의 저장 버튼으로만 바뀐다 (08 "스테이지 배치") */
    val placed: List<StageItem> = emptyList(),
)

/** 캐릭터의 별칭, 경험치, 단계 (#48) */
data class CharacterProgress(
    val profileId: String,
    val characterId: CharacterId,
    /** 사용자가 지은 별칭. 비어 있으면 화면은 CharacterDef.name 을 쓴다 */
    val alias: String = "",
    val xp: Int = 0,
    val stage: Int = 1,          // 1~5
)

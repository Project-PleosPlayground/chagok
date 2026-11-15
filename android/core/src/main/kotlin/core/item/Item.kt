package core.item

import kotlinx.serialization.Serializable

/**
 * 아이템 종류. 상점 탭을 나누고, 배경은 한 개만 놓고 맨 아래 그린다 (스펙_스테이지 "렌더 순서", 08 "스테이지 배치").
 * 종류 enum 은 판정·표시 코드와 같이 바뀌므로 원격 콘텐츠 업데이트(#31)로 바꾸지 않는다. 아이템 목록은 ContentPack 에 있다.
 * [label] 은 기획 CSV(데이터 컬럼 사전 "아이템"의 종류 칸)에 적는 이름이다.
 */
@Serializable
enum class ItemCategory(val label: String) {
    PET("펫"),
    PROP("소품"),
    BACKGROUND("배경");

    companion object {
        fun byLabel(label: String): ItemCategory? = entries.firstOrNull { it.label == label }
    }
}

/**
 * 아이템이 처음 놓이는 자리 (스펙_스테이지 "자리"). 기획 CSV 컬럼이 아니라 가져오기 도구가 종류로 채운다([forCategory]).
 * [label] 은 이 칸을 CSV 컬럼으로 옮길 때 쓸 이름이다.
 */
@Serializable
enum class ItemSlot(val label: String) {
    /** 펫 자리. 이름은 처음 위치(오른쪽 아래)에서 왔고, 내장·원격 콘텐츠 JSON 과 맞추려고 그대로 둔다. 지금은 캐릭터 왼쪽 위 공중 */
    BOTTOM_RIGHT("오른쪽 아래"),
    ABOVE_HEAD("머리 위");

    companion object {
        /** 종류별 자리. 배경의 자리는 [미정]이라 null */
        fun forCategory(category: ItemCategory): ItemSlot? = when (category) {
            ItemCategory.PET -> BOTTOM_RIGHT
            ItemCategory.PROP -> ABOVE_HEAD
            ItemCategory.BACKGROUND -> null
        }
    }
}

/**
 * 아이템 종류 번호 (#48). 도감의 아이템 하나에 하나. 목록 순서와 무관하다.
 * 한 번 붙이면 바꾸지 않고, 지운 번호는 다시 쓰지 않는다. 사용자 저장 데이터(가진 아이템, 스테이지 배치)가 이 번호를 가리키기 때문이다.
 * JSON 에는 정수 하나로 쓴다.
 */
@JvmInline
@Serializable
value class ItemId(val value: Int) {
    override fun toString() = value.toString()
}

/** 아이템 정의 하나 (데이터 컬럼 사전 "아이템"). 도감 = 인벤토리라 같은 아이템을 두 번 가질 수 없다. 소유 여부는 ProfileState.owned. */
@Serializable
data class ItemDef(
    val id: ItemId,
    val category: ItemCategory,
    /** 카드 이름의 콘텐츠 텍스트 키. 문구는 ContentPack.itemTexts */
    val nameKey: String,
    /** 이미지 경로. 기획/디자인/Image/Item/ 아래 PNG의 상대 경로, 확장자 없음 (예: Pet/Haechi/Haechi_Lv1). 규칙은 ItemImage */
    val imageKey: String,
    /** 구매 포인트 */
    val price: Int,
    /** 처음 놓이는 자리. null 이면 정하지 않음 */
    val slot: ItemSlot? = null,
    /** 장소 선물 아이템이면 그 장소. 코인으로 살 수 없고, 주행 중이나 도착한 곳이 반경 안이면 받는다 (11/15 드라이빙 이벤트) */
    val place: PlaceUnlock? = null,
)

/**
 * 장소 선물: 이 좌표 [radiusM] 안을 지나가거나 도착하면 아이템을 준다. [nameKey] 는 장소 이름의 콘텐츠 텍스트 키.
 * 위치 비교는 기기 안에서만 한다(좌표를 서버로 보내지 않는다).
 */
@Serializable
data class PlaceUnlock(val nameKey: String, val lat: Double, val lng: Double, val radiusM: Double)

/** 스테이지 위 한 점. 단위는 스테이지 좌표(스펙_스테이지 "스테이지") */
@Serializable
data class StagePoint(val x: Float, val y: Float)

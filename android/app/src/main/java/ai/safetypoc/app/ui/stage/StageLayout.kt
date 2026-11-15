package ai.safetypoc.app.ui.stage

import core.content.ContentPack
import core.item.ItemCategory
import core.item.ItemId
import core.item.ItemSlot
import core.item.StageItem
import core.item.StagePoint

/** 스테이지에 그릴 아이템 하나. [position] 은 그림의 중심 */
data class StageItemView(
    val id: ItemId,
    val category: ItemCategory,
    val name: String,
    val imageKey: String,
    val position: StagePoint,
    /** 사지 않고 미리보기로 놓은 것(08 "적용"). 저장하지 않는다 */
    val preview: Boolean = false,
    /** 그림 기울기(도, 시계 방향 +). 머리에 쓰는 소품만 기울인다 */
    val tilt: Float = 0f,
)

/** 스테이지 좌표계 (기획/스펙_스테이지.md "스테이지"). 단위는 스테이지 px, 왼쪽 위가 (0, 0) */
object StageLayout {
    /** 스테이지 기준 캔버스. 화면 배율 = 스테이지 높이(dp) ÷ [HEIGHT] */
    const val WIDTH = 1500f
    const val HEIGHT = 1700f

    /** 캐릭터 프레임 (110, 210)~(1390, 1490). 가로·세로 가운데, 캐릭터 프레임 1024(2× 기준)의 1.25배 */
    const val CHARACTER_LEFT = 110f
    const val CHARACTER_TOP = 210f
    const val CHARACTER_SIZE = 1280f

    /** [임시] 아이템 그림을 비율을 지켜 담는 네모의 한 변. 위치는 네모 중심이다. 아이템별 캔버스 크기(06 "아이템 데이터")가 정해지면 바꾼다 */
    const val ITEM_BOX = 238f

    /** 자리 좌표 (스펙_스테이지 "자리") */
    fun slotPoint(slot: ItemSlot?): StagePoint = when (slot) {
        ItemSlot.BOTTOM_RIGHT -> StagePoint(300f, 560f)
        ItemSlot.ABOVE_HEAD -> StagePoint(750f, 82f)
        null -> StagePoint(WIDTH / 2, HEIGHT / 2)
    }

    /**
     * [임시] 머리에 쓰는 소품(산타 모자). 아이템 데이터에 "쓰는 자리" 칸이 생기기 전까지 이미지 값으로 고른다.
     * 옮기지 않았으면 지금 단계 캐릭터의 머리 위에 [HEAD_WEAR_TILT] 만큼 기울여 씌운다.
     */
    val HEAD_WEAR = setOf("Accessories/Santa_hat")

    /** 스테이지 위 네모(스테이지 px) */
    data class StageBox(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * 배경 아이템 중 스테이지 전체가 아니라 정해진 자리에 놓는 소품. 캐릭터 뒤 왼쪽, 바닥은 캐릭터 발과 맞춘다 (11/15 크리스마스 트리).
     * 여기 없는 배경은 스테이지 전체에 그린다
     */
    fun backgroundBox(imageKey: String): StageBox? = BACKGROUND_PROPS[imageKey]
    const val CHRISTMAS_TREE = "Background/Christmas_tree"
    /** 눈 내리기: 그림 대신 스테이지 위로 눈송이를 그린다(그림은 상점 아이콘용) */
    const val SNOW = "Background/Snow"
    /** 장소 선물 배경(11/15): 트리와 겹치지 않게 캐릭터 오른쪽 뒤. 땅 펫이 앞에 선다 */
    private val PLACE_ITEMS = listOf("Background/Place_apple", "Background/Place_namsan", "Background/Place_cheomseongdae", "Background/Place_hyundai_hq")
    private val PLACE_BOX = StageBox(left = 1010f, top = 900f, width = 450f, height = 600f)
    private val BACKGROUND_PROPS = mapOf(
        CHRISTMAS_TREE to StageBox(left = -120f, top = 760f, width = 560f, height = 760f),
        // 눈 그림은 스테이지에 그리지 않는다(크기 0)
        SNOW to StageBox(left = 0f, top = 0f, width = 0f, height = 0f),
    ) + PLACE_ITEMS.associateWith { PLACE_BOX }
    const val HEAD_WEAR_TILT = -14f

    /**
     * 단계별 머리에 쓰는 소품의 그림 중심(스테이지 px). 그랜저 정지 그림(still_lv1~5)에서 잰 머리(1·2단계는 지붕) 꼭대기보다 115 px 위(챙이 머리 윗부분을 덮는다). 4단계는 어깨 바퀴가 머리보다 높아 에뮬레이터에서 머리에 맞춰 75 px 내렸다.
     * [임시] 차종마다 머리 위치가 다르다. 지금은 그랜저 그림 기준이다.
     */
    private val HEAD_WEAR_POINTS = listOf(
        StagePoint(796f, 703f), StagePoint(828f, 655f), StagePoint(780f, 689f), StagePoint(748f, 625f), StagePoint(716f, 564f),
    )

/**
     * 땅에 서는 펫(현무·해태): 하늘에 뜨지 않고 캐릭터 오른쪽 아래 바닥에 선다. 주작만 지금처럼 왼쪽 위 공중 (11/15)
     */
    fun isGroundPet(imageKey: String): Boolean = GROUND_PETS.any { imageKey.startsWith(it) }
    private val GROUND_PETS = listOf("Pet/Turtle/", "Pet/Haechi/")
    /** 땅 펫 자리: 캐릭터 발 높이, 오른쪽 */
    val GROUND_PET_POINT = StagePoint(1290f, 1390f)

        fun headWearPoint(characterStage: Int): StagePoint = HEAD_WEAR_POINTS[characterStage.coerceIn(1, HEAD_WEAR_POINTS.size) - 1]

    fun clamp(p: StagePoint) = StagePoint(p.x.coerceIn(0f, WIDTH), p.y.coerceIn(0f, HEIGHT))

    /**
     * 그릴 순서(아래부터)로 만든다. 배경이 맨 아래이고, 나머지는 배치 순서다(스펙_스테이지 "렌더 순서", 08 "스테이지 배치").
     * [previewId] 는 미리보기로 표시한다. 콘텐츠에 없는 아이템은 그리지 않는다.
     */
    fun views(pack: ContentPack, placed: List<StageItem>, previewId: ItemId? = null, characterStage: Int = 1): List<StageItemView> {
        val all = placed.mapNotNull { s ->
            val def = pack.items.firstOrNull { it.id == s.itemId } ?: return@mapNotNull null
            val wear = def.imageKey in HEAD_WEAR
            StageItemView(def.id, def.category, pack.itemTexts[def.nameKey] ?: def.nameKey, def.imageKey,
                s.position ?: if (wear) headWearPoint(characterStage) else if (isGroundPet(def.imageKey)) GROUND_PET_POINT else slotPoint(def.slot), preview = def.id == previewId,
                tilt = if (wear) HEAD_WEAR_TILT else 0f)
        }
        val (bg, rest) = all.partition { it.category == ItemCategory.BACKGROUND }
        return bg + rest
    }

    /** [p] 를 덮는 아이템 중 맨 위 하나(배경 제외). 없으면 null */
    fun topAt(views: List<StageItemView>, p: StagePoint): StageItemView? {
        val half = ITEM_BOX / 2
        return views.lastOrNull {
            it.category != ItemCategory.BACKGROUND && kotlin.math.abs(it.position.x - p.x) <= half && kotlin.math.abs(it.position.y - p.y) <= half
        }
    }
}

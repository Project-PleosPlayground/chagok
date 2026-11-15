package ai.safetypoc.app.ui.decorate

import ai.safetypoc.app.ui.stage.StageItemView
import ai.safetypoc.app.ui.stage.StageLayout
import core.character.ProfileState
import core.character.ProfileStore
import core.character.Wallet
import core.content.ContentPack
import core.item.ItemCategory
import core.item.ItemId
import core.item.StageItem
import core.item.StagePoint
import core.item.StageRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 상점의 아이템 슬롯 하나 (08 "상점") */
data class ShopSlot(
    val id: ItemId,
    val name: String,
    val imageKey: String,
    val price: Int,
    val owned: Boolean,
    /** 스테이지에 놓여 있다(선택된 슬롯) */
    val selected: Boolean,
    /** 장소 선물 아이템이면 그 장소 이름. 코인으로 살 수 없고 그곳에 가야 받는다 (11/15) */
    val place: String? = null,
)

/** 개별 아이템 페이지: 가지지 않은 아이템 하나 (08 "개별 아이템 페이지") */
data class ItemPage(val item: ShopSlot, val applied: Boolean)

/** 종류 탭 하나. [enabled] 가 false 면 터치를 막는다 */
data class ShopTab(val category: ItemCategory, val enabled: Boolean)

/** 팝업으로 알릴 것 */
sealed interface ShopDialog {
    /** 살지 묻는다 (디자인 시스템 7절 confirm_popup "아이템 구매") */
    data class ConfirmBuy(val item: ShopSlot) : ShopDialog
    /** 포인트가 모자라다 (08 "개별 아이템 페이지") */
    data class NotEnough(val item: ShopSlot, val points: Int) : ShopDialog
    /** 주행 중이라 하지 않았다. 화면은 팝업이 아니라 알림(L3)으로 한 번 띄운다 */
    data object Driving : ShopDialog
}

/** 상점이 보여 줄 값 (08 "상점", "스테이지 배치") */
data class ShopState(
    val points: Int = 0,
    val tabs: List<ShopTab> = emptyList(),
    val selected: ItemCategory = ItemCategory.PET,
    /** 아이템 슬롯. 가진 것이 앞, 그 안에서 이름순 */
    val slots: List<ShopSlot> = emptyList(),
    /** 열린 개별 아이템 페이지. null 이면 아이템 목록 */
    val page: ItemPage? = null,
    /** 스테이지에 그릴 아이템(아래부터). 저장 전 미리보기 상태다 */
    val stage: List<StageItemView> = emptyList(),
    /** 저장할 변경이 있다: 가진 아이템의 배치가 저장된 배치와 다르다. 적용한 미리보기는 세지 않는다. 저장 버튼은 이때만 보인다 */
    val unsaved: Boolean = false,
    val dialog: ShopDialog? = null,
    /** 스테이지에 그릴 캐릭터: 고른 캐릭터의 그림 폴더 이름과 단계. 고르지 않았으면 null */
    val characterAssetKey: String? = null,
    val characterStage: Int = 1,
)

/** 상점에서 하는 동작 */
interface ShopActions {
    fun selectTab(category: ItemCategory)
    /** 슬롯을 눌렀을 때: 가진 것은 스테이지에 놓거나(선택) 뺀다(선택 풀기). 가지지 않은 것은 개별 아이템 페이지를 연다 */
    fun tapSlot(id: ItemId)
    /** 개별 아이템 페이지의 아이템을 스테이지에 미리보기로 놓는다 */
    fun apply()
    /** 개별 아이템 페이지의 "구매하기": 포인트가 넉넉하면 살지 묻고, 모자라면 포인트 부족을 알린다 */
    fun requestBuy()
    /** 개별 아이템 페이지의 아이템을 산다. 사면 적용이 풀리고 아이템 목록으로 돌아간다 */
    fun buy()
    /** 개별 아이템 페이지를 닫는다. 적용이 풀린다 */
    fun closePage()
    /** 뒤로: 개별 아이템 페이지가 열려 있으면 닫고, 아니면 상점을 나간다 */
    fun back()
    /** 포인트 부족 팝업의 "미션하러 가기": 팝업과 개별 아이템 페이지를 닫고 미션 탭으로 간다 */
    fun goToMissions()
    /** 스테이지의 아이템을 옮긴다(드래그 앤 드롭). 옮긴 것이 맨 위로 온다 */
    fun moveItem(id: ItemId, to: StagePoint)
    /** 스테이지 배치를 저장한다 */
    fun save()
    fun dismissDialog()
}

/**
 * 상점: 아이템 슬롯, 개별 아이템 페이지, 스테이지 배치 (08).
 * 스테이지는 저장 전까지 미리보기(초안)이고, [save] 를 불러야 프로필에 남는다. 상점에 들어올 때 [enter] 로 초안을 저장된 배치로 되돌린다.
 * [enabledCategories] 밖의 종류 탭은 터치를 막는다 (08 "1차 제출 범위").
 * [onLeave] 는 상점을 닫고 이전 화면으로, [onGoMissions] 는 미션 탭으로 가는 화면 이동이다.
 */
class ShopModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val profiles: ProfileStore,
    private val wallet: Wallet,
    private val content: () -> ContentPack,
    private val driving: () -> Boolean,
    private val enabledCategories: Set<ItemCategory> = FIRST_SUBMISSION_CATEGORIES,
    private val onChanged: () -> Unit = {},
    private val onLeave: () -> Unit = {},
    private val onGoMissions: () -> Unit = {},
) : ShopActions {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(ShopState())
    val state: StateFlow<ShopState> = _state.asStateFlow()

    private var tab = ItemCategory.PET
    /** 스테이지 초안(아래에서 위). 적용한 미리보기 아이템도 여기 들어 있어, 옮기면 다른 아이템과 같이 맨 위로 온다 */
    private var layout: List<StageItem> = emptyList()
    /** 적용한 미리보기 아이템. 저장하지 않는다 */
    private var previewId: ItemId? = null
    private var pageItem: ItemId? = null
    private var dialog: ShopDialog? = null

    /** 저장할 배치: 초안에서 미리보기를 뺀 것 */
    private fun draft() = layout.filter { it.itemId != previewId }

    /** 상점에 들어올 때: 저장된 배치로 초안을 되돌리고 아이템 목록을 보인다 */
    fun enter() {
        layout = profiles.state(profileId).placed
        previewId = null; pageItem = null; dialog = null
        refresh()
    }

    /** 포인트·가진 아이템을 다시 읽는다. 초안과 열린 페이지는 그대로다 */
    fun refresh() {
        val pack = content(); val profile = profiles.state(profileId)
        val slots = pack.items.filter { it.category == tab }
            .map { slot(pack, profile, it.id)!! }
            .sortedWith(compareBy<ShopSlot>({ !it.owned }, { it.name }))
        _state.value = ShopState(
            points = profile.coins,
            tabs = ItemCategory.entries.map { ShopTab(it, it in enabledCategories) },
            selected = tab,
            slots = slots,
            page = pageItem?.let { id -> slot(pack, profile, id)?.let { ItemPage(it, applied = previewId != null) } },
            stage = StageLayout.views(pack, layout, previewId, characterStage = profile.selected?.let { profiles.progress(profileId, it).stage } ?: 1),
            unsaved = draft() != profile.placed,
            dialog = dialog,
            characterAssetKey = profile.selected?.let { id -> pack.characters.firstOrNull { it.id == id }?.assetKey },
            characterStage = profile.selected?.let { profiles.progress(profileId, it).stage } ?: 1,
        )
    }

    override fun selectTab(category: ItemCategory) {
        if (category !in enabledCategories) return
        tab = category; pageItem = null; clearPreview()
        refresh()
    }

    override fun tapSlot(id: ItemId) {
        val def = wallet.item(id) ?: return
        if (id !in profiles.state(profileId).owned) {
            // 페이지를 여는 것은 배치를 바꾸지 않아 주행 중에도 연다
            pageItem = id; clearPreview()
            return refresh()
        }
        if (driving()) return show(ShopDialog.Driving)
        layout = if (StageRules.contains(layout, id)) StageRules.remove(layout, id) else StageRules.place(layout, def, wallet::item)
        refresh()
    }

    override fun apply() {
        val id = pageItem ?: return
        if (driving()) return show(ShopDialog.Driving)
        if (previewId == null) {
            // 미리보기 배경도 저장된 배경을 빼지 않게 맨 위에 더하기만 한다
            layout = layout + StageItem(id); previewId = id
        }
        refresh()
    }

    override fun requestBuy() {
        val id = pageItem ?: return
        if (driving()) return show(ShopDialog.Driving)
        val profile = profiles.state(profileId)
        val item = slot(content(), profile, id) ?: return closePage()
        if (item.place != null) return
        show(if (profile.coins < item.price) ShopDialog.NotEnough(item, profile.coins) else ShopDialog.ConfirmBuy(item))
    }

    override fun buy() {
        val id = pageItem ?: return
        if (driving()) return show(ShopDialog.Driving)
        val profile = profiles.state(profileId)
        when (val r = wallet.buy(profile, id)) {
            is Wallet.BuyResult.Ok -> {
                profiles.saveState(r.state)
                pageItem = null; clearPreview(); dialog = null
                refresh(); onChanged()
            }
            Wallet.BuyResult.NotEnough -> slot(content(), profile, id)?.let { show(ShopDialog.NotEnough(it, profile.coins)) }
            Wallet.BuyResult.AlreadyOwned, Wallet.BuyResult.Unknown, Wallet.BuyResult.PlaceOnly -> { dialog = null; closePage() }
        }
    }

    override fun closePage() {
        pageItem = null; clearPreview()
        refresh()
    }

    override fun back() {
        if (pageItem != null) closePage() else onLeave()
    }

    override fun goToMissions() {
        dialog = null; pageItem = null; clearPreview()
        refresh()
        onGoMissions()
    }

    override fun moveItem(id: ItemId, to: StagePoint) {
        // 끄는 동안 이동이 계속 들어오므로, 이미 띄운 주행 중 알림은 다시 띄우지 않는다
        if (driving()) {
            if (dialog != ShopDialog.Driving) show(ShopDialog.Driving)
            return
        }
        layout = StageRules.move(layout, id, StageLayout.clamp(to))
        refresh()
    }

    override fun save() {
        if (driving()) return show(ShopDialog.Driving)
        val next = wallet.saveStage(profiles.state(profileId), draft())
        profiles.saveState(next)
        // 저장에서 빠진 것만 초안에서 뺀다. 미리보기를 포함한 순서는 그대로 둔다
        layout = layout.filter { it.itemId == previewId || StageRules.contains(next.placed, it.itemId) }
        refresh(); onChanged()
    }

    override fun dismissDialog() {
        dialog = null
        refresh()
    }

    private fun clearPreview() {
        previewId?.let { layout = StageRules.remove(layout, it) }
        previewId = null
    }

    private fun show(d: ShopDialog) {
        dialog = d
        refresh()
    }

    private fun slot(pack: ContentPack, profile: ProfileState, id: ItemId): ShopSlot? {
        val def = pack.items.firstOrNull { it.id == id } ?: return null
        return ShopSlot(def.id, pack.itemTexts[def.nameKey] ?: def.nameKey, def.imageKey, def.price,
            owned = def.id in profile.owned, selected = StageRules.contains(draft(), def.id),
            place = def.place?.let { pack.itemTexts[it.nameKey] ?: it.nameKey })
    }

    companion object {
        /** 1차 제출에서 여는 종류 (08 "1차 제출 범위"). 11/14 소품·배경도 연다. 배경은 그림이 없어 빈 목록 */
        val FIRST_SUBMISSION_CATEGORIES = ItemCategory.entries.toSet()
    }
}

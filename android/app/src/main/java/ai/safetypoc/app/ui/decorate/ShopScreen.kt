package ai.safetypoc.app.ui.decorate

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.CharacterStill
import ai.safetypoc.app.ui.component.BackButton
import ai.safetypoc.app.ui.component.ButtonDark
import ai.safetypoc.app.ui.component.CoinBar
import ai.safetypoc.app.ui.component.ConfirmPopup
import ai.safetypoc.app.ui.component.NoticeHost
import ai.safetypoc.app.ui.component.rememberNoticeHostState
import ai.safetypoc.app.ui.component.Scrollbar
import ai.safetypoc.app.ui.component.formatPoints
import ai.safetypoc.app.ui.stage.StageCanvas
import ai.safetypoc.app.ui.stage.StageItemView
import ai.safetypoc.app.ui.stage.StageLayout
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import core.item.ItemCategory
import core.item.ItemId
import core.item.ItemSlot
import core.item.StagePoint

/** 아이템 목록의 열 수 (영역사양_상점화면 6) */
private const val LIST_COLUMNS = 2

/**
 * 아이템 상점 (영역사양_상점화면). 메뉴 없이 창 전체를 쓴다.
 * L1 스테이지를 오른쪽 영역 가운데에 먼저 놓고, L2 UI(왼쪽 store_panel, coin_bar, 저장 버튼)를 위에 깐다. 팝업·알림(L3)은 맨 위.
 * 버튼이 없는 곳의 터치는 스테이지로 가서 놓인 아이템을 끌어 옮긴다.
 */
@Composable
fun ShopScreen(state: ShopState, actions: ShopActions, modifier: Modifier = Modifier) {
    // 뒤로 가기 키: 개별 아이템 페이지가 열려 있으면 페이지를 닫는다(하위 화면은 이전 화면으로, Pleos CCP Back 정책)
    BackHandler(enabled = state.page != null) { actions.closePage() }
    // 주행 중 알림은 모델이 dialog 로 한 번 알리고, 화면이 알림으로 띄운 뒤 바로 지운다
    val noticeHost = rememberNoticeHostState()
    val drivingText = stringResource(R.string.mission_notice_driving)
    LaunchedEffect(state.dialog) {
        if (state.dialog == ShopDialog.Driving) {
            noticeHost.show(drivingText)
            actions.dismissDialog()
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().background(AppPalette.white)) {
        val stageHeight = maxHeight - StoreScreenSizes.stageAreaTop - StoreScreenSizes.stageAreaBottom
        // StageCanvas 는 받은 영역에서 여백 a 를 위아래로 뺀 높이를 스테이지 높이로 쓴다
        StageCanvas(
            state.stage,
            Modifier
                .padding(start = StoreScreenSizes.panelWidth, top = StoreScreenSizes.stageAreaTop - AppDimens.screenMargin)
                .fillMaxWidth()
                .height(stageHeight + AppDimens.screenMargin * 2),
            onMove = actions::moveItem,
            character = state.characterAssetKey?.let { key ->
                { CharacterStill(key, state.characterStage, Modifier.fillMaxSize()) {} }
            },
        )
        StorePanel(state, actions, Modifier.width(StoreScreenSizes.panelWidth).fillMaxHeight())
        CoinBar(state.points, Modifier.align(Alignment.TopEnd).padding(AppDimens.screenMargin))
        Box(
            Modifier.align(Alignment.BottomEnd)
                .width(maxWidth - StoreScreenSizes.panelWidth)
                .padding(bottom = AppDimens.screenMargin),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // 저장할 변경이 있을 때만 저장 버튼을 보인다
            if (state.unsaved) ButtonDark(stringResource(R.string.save_button), actions::save)
        }
        state.dialog?.let { ShopDialogView(it, actions) }
        // 알림(L3): 창 아래 a, 가로 가운데 (영역사양_팝업 4)
        NoticeHost(noticeHost, Modifier.align(Alignment.BottomCenter).padding(bottom = AppDimens.screenMargin))
    }
}

/** store_panel: 뒤로 + 제목 + segmented_tab + 아이템 목록 또는 item_detail */
@Composable
private fun StorePanel(state: ShopState, actions: ShopActions, modifier: Modifier) {
    Column(
        modifier
            .background(AppPalette.surfaceBlue)
            .padding(start = AppDimens.screenMargin, top = AppDimens.screenMargin, end = AppDimens.screenMargin),
    ) {
        Box(Modifier.fillMaxWidth().height(StoreScreenSizes.headerHeight)) {
            BackButton(actions::back, stringResource(R.string.back), Modifier.align(Alignment.CenterStart))
            Text(
                stringResource(R.string.shop_title),
                Modifier.align(Alignment.Center),
                style = AppType.mediumBold,
                color = AppPalette.ink,
            )
        }
        Spacer(Modifier.height(StoreScreenSizes.headerToTab))
        SegmentedTab(state.tabs, state.selected, actions::selectTab, Modifier.fillMaxWidth())
        Spacer(Modifier.height(StoreScreenSizes.tabToList))
        val page = state.page
        if (page == null) ItemList(state.slots, actions::tapSlot, Modifier.fillMaxWidth().weight(1f))
        else ItemDetail(page, actions::requestBuy, actions::apply, Modifier.fillMaxWidth())
    }
}

/** 아이템 목록: item_box 2열, 패널 가로 가운데. 창 아래 끝까지 가고 세로로 스크롤한다 */
@Composable
private fun ItemList(slots: List<ShopSlot>, onTap: (ItemId) -> Unit, modifier: Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(bottom = AppDimens.screenMargin),
            verticalArrangement = Arrangement.spacedBy(ItemBoxSizes.rowGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (slots.isEmpty()) {
                Text(stringResource(R.string.shop_empty), Modifier.padding(top = AppDimens.screenMargin), style = AppType.smallRegular, color = AppPalette.textSecondary)
            }
            for (row in slots.chunked(LIST_COLUMNS)) {
                Row(
                    Modifier.width(ItemBoxSizes.width * LIST_COLUMNS + ItemBoxSizes.columnGap * (LIST_COLUMNS - 1)),
                    horizontalArrangement = Arrangement.spacedBy(ItemBoxSizes.columnGap),
                ) {
                    row.forEach { slot -> ItemBox(slot, onClick = { onTap(slot.id) }) }
                }
            }
        }
        Scrollbar(scroll, Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun ShopDialogView(d: ShopDialog, actions: ShopActions) {
    when (d) {
        is ShopDialog.ConfirmBuy -> ConfirmPopup(
            title = stringResource(R.string.shop_buy_title),
            body = stringResource(R.string.shop_buy_body, d.item.name, formatPoints(d.item.price)),
            confirmText = stringResource(R.string.shop_buy),
            dismissText = stringResource(R.string.shop_close),
            onConfirm = actions::buy,
            onDismiss = actions::dismissDialog,
        )
        is ShopDialog.NotEnough -> ConfirmPopup(
            title = stringResource(R.string.shop_not_enough_title),
            body = stringResource(R.string.shop_not_enough_body),
            confirmText = stringResource(R.string.shop_go_missions),
            dismissText = stringResource(R.string.shop_close),
            onConfirm = actions::goToMissions,
            onDismiss = actions::dismissDialog,
        )
        // 화면 위쪽 LaunchedEffect 가 알림으로 띄운다
        ShopDialog.Driving -> Unit
    }
}

/** 미리보기 창: 2칸 창 1700 × 1184 dp (디자인 시스템 3절) */
private const val PREVIEW_WINDOW_WIDTH_DP = 1700
private const val PREVIEW_WINDOW_HEIGHT_DP = 1184

private object NoShopActions : ShopActions {
    override fun selectTab(category: ItemCategory) = Unit
    override fun tapSlot(id: ItemId) = Unit
    override fun apply() = Unit
    override fun requestBuy() = Unit
    override fun buy() = Unit
    override fun closePage() = Unit
    override fun back() = Unit
    override fun goToMissions() = Unit
    override fun moveItem(id: ItemId, to: StagePoint) = Unit
    override fun save() = Unit
    override fun dismissDialog() = Unit
}

/** 미리보기 칸: 가진 것 넷(둘은 놓음), 잠긴 것 넷. 가진 것이 앞, 그 안에서 이름순 */
private fun previewSlots(selected: Set<Int>): List<ShopSlot> {
    val owned = listOf(1 to "현무 LV.1", 2 to "현무 LV.2", 11 to "해태 LV.1", 6 to "주작 LV.1")
    val locked = listOf(7 to "주작 LV.2", 12 to "해태 LV.2", 3 to "현무 LV.3", 8 to "주작 LV.3")
    return owned.sortedBy { it.second }.map { (id, name) -> ShopSlot(ItemId(id), name, "", PREVIEW_PRICE, owned = true, selected = id in selected) } +
        locked.sortedBy { it.second }.map { (id, name) -> ShopSlot(ItemId(id), name, "", PREVIEW_PRICE, owned = false, selected = false) }
}

private fun previewStage(slots: List<ShopSlot>, preview: ShopSlot? = null): List<StageItemView> =
    (slots.filter { it.selected } + listOfNotNull(preview)).mapIndexed { i, s ->
        val home = StageLayout.slotPoint(ItemSlot.BOTTOM_RIGHT)
        // 겹쳐 보이지 않게 칸마다 왼쪽으로 한 칸씩 옮겨 놓는다
        StageItemView(s.id, ItemCategory.PET, s.name, s.imageKey, home.copy(x = home.x - StageLayout.ITEM_BOX * i), preview = s == preview)
    }

private fun previewState(selected: Set<Int> = emptySet(), page: ItemPage? = null, dialog: ShopDialog? = null): ShopState {
    val slots = previewSlots(selected)
    return ShopState(
        points = PREVIEW_POINTS,
        tabs = ItemCategory.entries.map { ShopTab(it, it == ItemCategory.PET) },
        slots = slots,
        page = page,
        stage = previewStage(slots, page?.takeIf { it.applied }?.item),
        unsaved = selected.isNotEmpty(),
        dialog = dialog,
    )
}

/** 미리보기 포인트: 시안의 값 */
private const val PREVIEW_POINTS = 33456
/** 포인트 부족 미리보기의 포인트 */
private const val PREVIEW_POINTS_SHORT = 150
private val previewLocked get() = previewSlots(emptySet()).first { !it.owned }

@Composable
private fun ShopPreviewFrame(state: ShopState) {
    AppTheme { ShopScreen(state, NoShopActions) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH_DP, heightDp = PREVIEW_WINDOW_HEIGHT_DP)
@Composable
internal fun ShopListPreview() = ShopPreviewFrame(previewState())

@Preview(widthDp = PREVIEW_WINDOW_WIDTH_DP, heightDp = PREVIEW_WINDOW_HEIGHT_DP)
@Composable
internal fun ShopSelectedPreview() = ShopPreviewFrame(previewState(selected = setOf(1, 11)))

@Preview(widthDp = PREVIEW_WINDOW_WIDTH_DP, heightDp = PREVIEW_WINDOW_HEIGHT_DP)
@Composable
internal fun ShopItemPagePreview() = ShopPreviewFrame(previewState(page = ItemPage(previewLocked, applied = true)))

@Preview(widthDp = PREVIEW_WINDOW_WIDTH_DP, heightDp = PREVIEW_WINDOW_HEIGHT_DP)
@Composable
internal fun ShopBuyPopupPreview() = ShopPreviewFrame(
    previewState(page = ItemPage(previewLocked, applied = false), dialog = ShopDialog.ConfirmBuy(previewLocked)),
)

@Preview(widthDp = PREVIEW_WINDOW_WIDTH_DP, heightDp = PREVIEW_WINDOW_HEIGHT_DP)
@Composable
internal fun ShopNotEnoughPopupPreview() = ShopPreviewFrame(
    previewState(page = ItemPage(previewLocked, applied = false), dialog = ShopDialog.NotEnough(previewLocked, PREVIEW_POINTS_SHORT))
        .copy(points = PREVIEW_POINTS_SHORT),
)

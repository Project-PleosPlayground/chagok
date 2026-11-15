package ai.safetypoc.app.ui.decorate

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.ItemArt
import ai.safetypoc.app.ui.component.ButtonDark
import ai.safetypoc.app.ui.component.ButtonLight
import ai.safetypoc.app.ui.component.formatPoints
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.DividerSizes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import core.item.ItemCategory
import core.item.ItemId

/**
 * segmented_tab: 아이템 종류 칸 3개. 바탕은 `line`, 고른 칸은 흰 칸이다.
 * 바탕은 radius_l, 칸은 radius_m 둥근 사각형이다. 칸 모서리 = 바탕 모서리 − 바탕 안 여백이라 두 곡선이 나란하다.
 * 막힌 칸([ShopTab.enabled]가 false)은 글자를 흐리게 하고 눌러도 아무 일이 없다.
 * 고르지 않은 두 칸이 붙어 있으면 사이에 세로선을 긋는다.
 */
@Composable
fun SegmentedTab(
    tabs: List<ShopTab>,
    selected: ItemCategory,
    onSelect: (ItemCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(SegmentedTabSizes.height)
            .background(AppPalette.line, AppShapes.radiusL)
            .padding(SegmentedTabSizes.inset),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, tab ->
            if (index > 0) {
                val betweenUnselected = tab.category != selected && tabs[index - 1].category != selected
                Box(
                    Modifier
                        .width(DividerSizes.thickness)
                        .height(SegmentedTabSizes.dividerHeight)
                        .background(if (betweenUnselected) AppPalette.lineStrong else AppPalette.line),
                )
            }
            SegmentedCell(tab, tab.category == selected, onClick = { onSelect(tab.category) })
        }
    }
}

@Composable
private fun SegmentedCell(tab: ShopTab, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(SegmentedTabSizes.cellWidth, SegmentedTabSizes.cellHeight)
            .then(if (selected) Modifier.background(AppPalette.white, AppShapes.radiusM) else Modifier)
            .pressable(interactionSource, enabled = tab.enabled && !selected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = tab.category.label,
            style = if (selected) AppType.smallBold else AppType.smallRegular,
            color = if (tab.enabled) AppPalette.ink else AppTints.textDisabled,
        )
    }
}

/**
 * item_box: 아이템 슬롯 하나. 그림 + 이름, 모두 가운데 정렬.
 * 기본(가진 아이템, 흰 바탕) / 선택(스테이지에 놓임, check_icon) / 잠금(가지지 않음, lock_icon). 누르는 동안 누름 색을 덮는다.
 */
@Composable
fun ItemBox(slot: ShopSlot, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val selected = slot.owned && slot.selected
    Box(
        modifier = modifier
            .size(ItemBoxSizes.width, ItemBoxSizes.height)
            .background(if (selected) AppPalette.blueSoft else AppPalette.white, AppShapes.radiusXl)
            .then(if (pressed) Modifier.background(AppTints.tabButtonPressed, AppShapes.radiusXl) else Modifier)
            .border(ItemBoxSizes.border, if (selected) AppPalette.primaryBlue else AppPalette.lineStrong, AppShapes.radiusXl)
            .pressable(interactionSource, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = ItemBoxSizes.pictureTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 아직 안 받은 장소 선물은 옅게 보여 궁금하게 (11/15)
            val hidden = !slot.owned && slot.place != null
            ItemPicture(slot.imageKey, slot.name, Modifier.size(ItemBoxSizes.picture).alpha(if (hidden) PLACE_LOCKED_ALPHA else 1f))
            Spacer(Modifier.height(ItemBoxSizes.pictureToName))
            ItemName(slot.name)
        }
        when {
            selected -> Image(
                painter = painterResource(R.drawable.check_icon),
                contentDescription = stringResource(R.string.shop_selected),
                modifier = Modifier.align(Alignment.TopEnd).padding(ItemBoxSizes.badgeInset).size(ItemBoxSizes.checkIcon),
            )
            !slot.owned -> Image(
                painter = painterResource(R.drawable.lock_icon),
                contentDescription = stringResource(R.string.shop_locked),
                modifier = Modifier.align(Alignment.TopEnd).padding(ItemBoxSizes.badgeInset).size(ItemBoxSizes.lockIcon),
            )
        }
    }
}

/**
 * item_detail: 개별 아이템 페이지. 큰 상자(그림) + 이름 + 가격(coin_icon + 숫자) + "구매하기"(button_light) · "적용하기"(button_dark).
 */
@Composable
fun ItemDetail(
    page: ItemPage,
    onBuy: () -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(ItemDetailSizes.boxWidth, ItemDetailSizes.boxHeight)
                .background(AppPalette.white, AppShapes.radiusXl)
                .border(ItemDetailSizes.border, AppPalette.lineStrong, AppShapes.radiusXl),
            contentAlignment = Alignment.Center,
        ) {
            ItemPicture(page.item.imageKey, page.item.name, Modifier.size(ItemDetailSizes.picture))
        }
        Spacer(Modifier.height(ItemDetailSizes.boxToName))
        ItemName(page.item.name)
        Spacer(Modifier.height(ItemDetailSizes.nameToPrice))
        val place = page.item.place
        if (place != null) {
            // 장소 선물: 가격 대신 받는 곳. 살 수 없어 구매 버튼이 없다 (11/15)
            Text(
                stringResource(R.string.shop_place_hint, place),
                Modifier.background(AppPalette.blueSoft, AppShapes.pill).padding(horizontal = ItemDetailSizes.placePadH, vertical = ItemDetailSizes.placePadV),
                style = AppType.smallBold, color = AppPalette.primaryBlue,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ItemDetailSizes.coinGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.coin_icon),
                    contentDescription = null,
                    modifier = Modifier.size(ItemDetailSizes.coinIcon),
                )
                Text(text = formatPoints(page.item.price), style = AppType.smallBold, color = AppPalette.ink)
            }
        }
        Spacer(Modifier.height(ItemDetailSizes.priceToButtons))
        Row(horizontalArrangement = Arrangement.spacedBy(ItemDetailSizes.buttonGap)) {
            if (place == null) ButtonLight(stringResource(R.string.shop_buy), onBuy)
            ButtonDark(stringResource(R.string.shop_apply), onApply)
        }
    }
}

/** 아직 받지 않은 장소 선물 그림의 진하기 */
private const val PLACE_LOCKED_ALPHA = 0.35f

/** 아이템 그림. 그림 파일이 없거나 읽는 동안은 [ItemArt] 가 회색 자리를 그린다 */
@Composable
private fun ItemPicture(image: String, name: String, modifier: Modifier) {
    ItemArt(image, contentDescription = name, modifier = modifier)
}

@Composable
private fun ItemName(name: String) {
    Text(
        text = name,
        style = AppType.mediumBold,
        color = AppPalette.ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 미리보기 칸: 가진 것, 놓은 것, 잠긴 것 */
private val previewSlots = listOf(
    ShopSlot(ItemId(1), "현무 LV.1", "Pet/Turtle/Turtle_Lv1", PREVIEW_PRICE, owned = true, selected = false),
    ShopSlot(ItemId(2), "현무 LV.2", "Pet/Turtle/Turtle_Lv2", PREVIEW_PRICE, owned = true, selected = true),
    ShopSlot(ItemId(6), "주작 LV.1", "Pet/Bird/Bird_Lv1", PREVIEW_PRICE, owned = false, selected = false),
)

private val previewTabs = ItemCategory.entries.map { ShopTab(it, it == ItemCategory.PET) }

@Composable
private fun StorePreviewFrame(content: @Composable () -> Unit) {
    AppTheme {
        Box(Modifier.background(AppPalette.surfaceBlue).padding(AppDimens.screenMargin)) { content() }
    }
}

@Preview
@Composable
internal fun SegmentedTabPreview() = StorePreviewFrame {
    SegmentedTab(previewTabs, ItemCategory.PET, onSelect = {})
}

@Preview
@Composable
internal fun ItemBoxStatesPreview() = StorePreviewFrame {
    Row(horizontalArrangement = Arrangement.spacedBy(AppDimens.touchGap)) {
        previewSlots.forEach { ItemBox(it, onClick = {}) }
    }
}

@Preview
@Composable
internal fun ItemDetailPreview() = StorePreviewFrame {
    ItemDetail(ItemPage(previewSlots.last(), applied = false), onBuy = {}, onApply = {})
}

/** 미리보기 가격: 모든 아이템 200포인트 (08 "얻기") */
internal const val PREVIEW_PRICE = 200

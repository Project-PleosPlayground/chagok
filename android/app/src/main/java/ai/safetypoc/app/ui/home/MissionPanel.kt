package ai.safetypoc.app.ui.home

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.ButtonDark
import ai.safetypoc.app.ui.component.ButtonSize
import ai.safetypoc.app.ui.mission.MissionCard
import ai.safetypoc.app.ui.mission.MissionTabState
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.appShadow
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.annotation.StringRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import core.mission.MissionStatus
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.LocalDriving
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

/** mission_panel 에 놓는 mission_card 최대 수 (영역사양_홈화면 5-2) */
const val MISSION_PANEL_MAX_CARDS = 3

/** 홈·주행 화면 mission_panel 이 보여 줄 값: 도전 슬롯의 미션 */
data class MissionPanelState(val cards: List<MissionCard> = emptyList()) {
    /** 판정이 끝나 성공한 카드 수 */
    val succeeded: Int get() = cards.count { it.status == MissionStatus.SUCCEEDED }
}

/** 미션 탭 상태의 도전 슬롯에서 mission_panel 값을 만든다. 도전한 순서대로 앞에서 [MISSION_PANEL_MAX_CARDS] 장 */
fun MissionTabState.toPanel(): MissionPanelState = MissionPanelState(slot.take(MISSION_PANEL_MAX_CARDS))

/** mission_panel 자리 안의 배치 값 [임시] */
object MissionPanelSlotSizes {
    /** 패널 안쪽 여백 */
    val padding = 24.dp
    /** 줄 사이 */
    val rowGap = 16.dp
    /** 미션 없음 상태의 패널 높이: mission_card 3장일 때와 같다 (mission_panel_default ≈ 689) */
    val emptyHeight = 688.dp
    /** mission_empty 흰 원 지름 (디자인 시스템 6절 mission_empty) */
    val emptyCircle = 128.dp
    /** mission_empty clipboard_icon 한 변 (아이콘 원본 크기) */
    val emptyIcon = 56.dp
    /** mission_empty 원 아래 ~ 안내 글, 안내 글 ~ 버튼 */
    val emptyGap = 24.dp
    /** mission_empty 안내 두 줄 사이 */
    val emptyLineGap = 8.dp
    /** 1칸 창: 접힌 버튼 ↔ 펼친 패널로 바뀌는 시간(밀리초) */
    const val FOLD_MS = 250
}

/**
 * mission_panel 자리. 바탕 surface, radius_xl.
 * 미션 있음: 높이 = 내용. 머리(제목, 성공 수)와 카드마다 제목·진행을 글자로 보인다. mission_card 모양은 #61 에서 들어온다.
 * 미션 없음: 머리 없이 mission_empty 를 패널 가운데에 놓는다(homeView_noMission). [onPickMission] 이 없으면 버튼을 그리지 않는다.
 * 1칸 창이면 [FoldableMissionPanel].
 */
@Composable
fun MissionPanelSlot(state: MissionPanelState, onPickMission: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (LocalNarrowWindow.current) {
        FoldableMissionPanel(state, onPickMission, modifier)
        return
    }
    if (state.cards.isEmpty()) {
        Box(
            modifier.height(MissionPanelSlotSizes.emptyHeight).background(AppPalette.surface, AppShapes.radiusXl),
            contentAlignment = Alignment.Center,
        ) { MissionEmpty(onPickMission) }
        return
    }
    Column(
        modifier.background(AppPalette.surface, AppShapes.radiusXl).padding(MissionPanelSlotSizes.padding),
        verticalArrangement = Arrangement.spacedBy(MissionPanelSlotSizes.rowGap),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mission_topic), style = AppType.smallBold)
            Text(
                stringResource(R.string.mission_panel_succeeded, state.cards.size, state.succeeded),
                style = AppType.smallRegular, color = AppPalette.textTertiary,
            )
        }
        for (card in state.cards) MissionCardRow(card)
    }
}

/**
 * 1칸 창 mission_panel. 처음에는 접힌 버튼("도전 중인 미션 ▾")이고, 누르면 패널로 펼쳐진다. 펼친 머리를 누르면 다시 접힌다.
 * 받은 자리의 오른쪽 위를 기준으로 왼쪽·아래로 커진다. 주행 중에는 움직임 없이 바로 바뀐다(디자인 시스템 2절 "주행 중 움직임").
 */
@Composable
private fun FoldableMissionPanel(state: MissionPanelState, onPickMission: (() -> Unit)?, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val spec: FiniteAnimationSpec<IntSize> = if (LocalDriving.current) snap() else tween(MissionPanelSlotSizes.FOLD_MS)
    val pad = MissionPanelSlotSizes.padding
    Column(
        modifier.animateContentSize(spec)
            .then(if (open) Modifier.fillMaxWidth() else Modifier)
            .background(AppPalette.surface, AppShapes.radiusXl),
    ) {
        // 머리: 누르면 접고 편다
        Row(
            Modifier.then(if (open) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = AppDimens.touchTarget)
                .pressable(remember { MutableInteractionSource() }) { open = !open }
                .padding(horizontal = pad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MissionPanelSlotSizes.rowGap),
        ) {
            Text(stringResource(R.string.mission_topic), style = AppType.smallBold)
            if (open) {
                Spacer(Modifier.weight(1f))
                if (state.cards.isNotEmpty()) {
                    Text(
                        stringResource(R.string.mission_panel_succeeded, state.cards.size, state.succeeded),
                        style = AppType.smallRegular, color = AppPalette.textTertiary,
                    )
                }
            }
            Text(if (open) FOLD_OPEN_MARK else FOLD_CLOSED_MARK, style = AppType.smallRegular, color = AppPalette.textTertiary)
        }
        if (open) {
            Column(
                Modifier.fillMaxWidth().padding(start = pad, end = pad, bottom = pad),
                verticalArrangement = Arrangement.spacedBy(MissionPanelSlotSizes.rowGap),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.cards.isEmpty()) MissionEmpty(onPickMission)
                for (card in state.cards) MissionCardRow(card)
            }
        }
    }
}

/** 접힌 패널 머리 끝 표시 (TabFrame 의 접힌 탭 버튼과 같은 글자) */
private const val FOLD_CLOSED_MARK = "▾"
/** 펼친 패널 머리 끝 표시 */
private const val FOLD_OPEN_MARK = "▴"

/** mission_empty: 흰 원(clipboard_icon, shadow_card) + 안내 두 줄 + button_dark "미션 고르기"(→ 미션 탭). 모두 가로 가운데 */
@Composable
private fun MissionEmpty(onPickMission: (() -> Unit)?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(MissionPanelSlotSizes.emptyCircle)
                .appShadow(AppShadows.card, CircleShape)
                .background(AppPalette.white, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.clipboard_icon), contentDescription = null, Modifier.size(MissionPanelSlotSizes.emptyIcon))
        }
        Spacer(Modifier.height(MissionPanelSlotSizes.emptyGap))
        Text(stringResource(R.string.mission_empty_title), style = AppType.smallBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(MissionPanelSlotSizes.emptyLineGap))
        Text(
            stringResource(R.string.mission_empty_body),
            style = AppType.smallRegular, color = AppPalette.textSecondary, textAlign = TextAlign.Center,
        )
        if (onPickMission != null) {
            Spacer(Modifier.height(MissionPanelSlotSizes.emptyGap))
            ButtonDark(stringResource(R.string.mission_pick), onPickMission, size = ButtonSize.EmptyState)
        }
    }
}

/**
 * 카드 한 장: 상태 칩 + 제목, 설명 한 줄, (도전 중이면) 진행 막대와 "3/10", 보상(경험치·포인트).
 * 바탕은 상태로 나눈다. 성공 green_soft, 실패 red_soft, 도전 중 white [가안, 시안 11/14]
 */
@Composable
private fun MissionCardRow(card: MissionCard) {
    val look = cardLook(card.status)
    Column(
        Modifier.fillMaxWidth()
            .background(look.background, AppShapes.radiusL)
            .border(MissionCardSizes.border, look.border, AppShapes.radiusL)
            .padding(MissionCardSizes.padding),
        verticalArrangement = Arrangement.spacedBy(MissionCardSizes.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionCardSizes.gap)) {
            Box(
                Modifier.background(look.chip, AppShapes.pill).padding(MissionCardSizes.chipPadH, MissionCardSizes.chipPadV),
            ) { Text(stringResource(look.label), style = MissionCardSizes.chipText, color = look.chipText) }
            Text(card.title, style = AppType.smallBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            card.description, style = MissionCardSizes.bodyText, color = AppPalette.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (card.status == MissionStatus.IN_PROGRESS) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionCardSizes.gap)) {
                Box(Modifier.weight(1f).height(MissionCardSizes.barHeight).background(AppPalette.line, AppShapes.pill)) {
                    if (card.progressFraction > 0f) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(card.progressFraction).background(AppPalette.primaryBlue, AppShapes.pill))
                    }
                }
                // 센 값이 아직 없으면 "-" 대신 0 으로 보인다
                Text(
                    card.progress.replace("-/", "0/"), style = MissionCardSizes.chipText, color = AppPalette.ink,
                    textAlign = TextAlign.End, modifier = Modifier.widthIn(min = MissionCardSizes.countWidth),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionCardSizes.rewardGap)) {
            if (card.rewardXp > 0) Reward(R.drawable.exp_icon, card.rewardXp)
            if (card.rewardCoins > 0) Reward(R.drawable.coin_icon, card.rewardCoins)
        }
    }
}

@Composable
private fun Reward(@DrawableRes icon: Int, amount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionCardSizes.iconGap)) {
        Image(painterResource(icon), contentDescription = null, Modifier.size(MissionCardSizes.rewardIcon))
        Text(amount.toString(), style = MissionCardSizes.chipText)
    }
}

private class CardLook(val background: Color, val border: Color, val chip: Color, val chipText: Color, @StringRes val label: Int)

private fun cardLook(status: MissionStatus): CardLook = when (status) {
    MissionStatus.SUCCEEDED -> CardLook(AppPalette.greenSoft, AppPalette.green, AppPalette.green, AppPalette.white, R.string.mission_status_succeeded)
    MissionStatus.FAILED -> CardLook(AppPalette.redSoft, AppPalette.red, AppPalette.red, AppPalette.white, R.string.mission_status_failed)
    else -> CardLook(AppPalette.white, AppPalette.white, AppPalette.surfaceBlue, AppPalette.primaryBlue, R.string.mission_status_in_progress)
}

/** mission_panel 카드 안의 값 [가안] */
private object MissionCardSizes {
    val padding = 20.dp
    val gap = 10.dp
    val border = 2.dp
    val chipPadH = 12.dp
    val chipPadV = 4.dp
    val chipText = AppType.smallBold.copy(fontSize = 18.sp)
    val bodyText = AppType.smallRegular.copy(fontSize = 18.sp)
    val barHeight = 10.dp
    val rewardIcon = 26.dp
    val iconGap = 6.dp
    val rewardGap = 20.dp
    /** 진행 숫자 칸. 자릿수가 달라도 막대 끝이 맞는다 */
    val countWidth = 48.dp
}

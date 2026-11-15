package ai.safetypoc.app.ui.mission

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.ConfirmPopup
import ai.safetypoc.app.ui.component.InfoIcon
import ai.safetypoc.app.ui.component.MissionInfo
import ai.safetypoc.app.ui.component.NoticeHost
import ai.safetypoc.app.ui.component.Scrollbar
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.component.rememberNoticeHostState
import ai.safetypoc.app.ui.nav.TabFrameSizes
import ai.safetypoc.app.ui.nav.TabHeaderActions
import ai.safetypoc.app.ui.nav.tabContentArea
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.InfoIconSizes
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import ai.safetypoc.app.ui.theme.MissionInfoSizes
import ai.safetypoc.app.ui.theme.TabButtonSizes
import ai.safetypoc.app.ui.theme.inkOrDisabled
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import core.mission.MissionKind
import core.mission.MissionStatus
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val MS_PER_DAY = MS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR * HOURS_PER_DAY

/** 완료 날짜 표기 (영역사양_미션화면 "카드 안") */
private val completedDate = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT)

/**
 * 미션 탭 (영역사양_미션화면). 창 전체를 받는다. tab_bar 는 [ai.safetypoc.app.ui.nav.TabFrame] 이 그린다.
 * - 3 미션 목록: tab_bar 오른쪽 + a ~ →a, 세로 스크롤. 섹션 머리는 왼쪽 열, 카드는 오른쪽 열. 순서 도전 중 → 개인 → 주간
 * - 3-5 스크롤 막대: 오른쪽 끝, 카드 열과 [MissionScreenSizes.scrollbarGap] 띄움
 * - 1칸 창: 목록은 헤더 아래 전체 폭. 섹션 머리를 카드 위에 두고 카드는 남은 폭을 다 쓴다.
 *   스크롤 막대 간격은 [MissionScreenSizes.narrowScrollbarGap], complete_mission 은 헤더 오른쪽
 * - 4 complete_mission: ↓a, tab_bar 와 가로 가운데. 누르면 완료 미션 목록 ↔ 미션 목록
 * 확인 팝업은 tab_bar 위까지 덮어야 해서 [MissionDialogLayer] 가 따로 그린다.
 */
@Composable
fun MissionScreen(state: MissionTabState, actions: MissionTabActions, modifier: Modifier = Modifier) {
    var showCompleted by rememberSaveable { mutableStateOf(false) }
    val noticeHost = rememberNoticeHostState()
    val noticeTexts = MissionNotice.entries.associateWith { stringResource(noticeText(it)) }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            noticeHost.show(noticeTexts.getValue(it))
            actions.dismissNotice()
        }
    }
    // 남은 시간 HH:MM:SS 를 초마다 다시 그린다. 완료 목록에는 남은 시간이 없어 멈춘다
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showCompleted) {
        nowMs = System.currentTimeMillis()
        while (!showCompleted) {
            delay(MS_PER_SECOND)
            nowMs = System.currentTimeMillis()
        }
    }
    Box(modifier.fillMaxSize()) {
        val scroll = rememberScrollState()
        Row(Modifier.tabContentArea(compact = true)) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll)) {
                if (showCompleted) CompletedList(state)
                else MissionLists(state, actions, nowMs)
            }
            Spacer(Modifier.width(if (LocalNarrowWindow.current) MissionScreenSizes.narrowScrollbarGap else MissionScreenSizes.scrollbarGap))
            Scrollbar(scroll, Modifier.fillMaxHeight())
        }
        // 완료한 미션이 없으면 누를 수 없다. 완료 목록을 보는 중이면 미션 목록으로 돌아갈 수 있게 둔다
        val completeButton = @Composable {
            CompleteMissionButton(
                selected = showCompleted,
                enabled = showCompleted || state.completed.isNotEmpty(),
                onClick = { showCompleted = !showCompleted },
            )
        }
        if (LocalNarrowWindow.current) {
            TabHeaderActions { completeButton() }
        } else {
            Box(
                Modifier.align(Alignment.BottomStart)
                    .padding(start = AppDimens.screenMargin, bottom = AppDimens.screenMargin)
                    .width(TabFrameSizes.compactWidth),
                contentAlignment = Alignment.Center,
            ) { completeButton() }
        }
        NoticeHost(noticeHost, Modifier.align(Alignment.BottomCenter).padding(bottom = AppDimens.screenMargin))
    }
}

/** 미션 탭의 확인 팝업(L3). tab_bar 까지 덮도록 탭 뼈대 위에 그린다 */
@Composable
fun MissionDialogLayer(state: MissionTabState, actions: MissionTabActions) {
    val dialog = state.dialog ?: return
    val close = stringResource(R.string.mission_popup_close)
    when (dialog) {
        is MissionDialog.Challenge -> ConfirmPopup(
            title = stringResource(R.string.mission_popup_challenge_title), body = dialog.card.title,
            confirmText = stringResource(R.string.mission_popup_challenge_confirm), dismissText = close,
            onConfirm = actions::confirmDialog, onDismiss = actions::dismissDialog,
        )
        is MissionDialog.Cancel -> ConfirmPopup(
            title = stringResource(R.string.mission_popup_cancel_title),
            body = stringResource(R.string.mission_popup_cancel_body, dialog.card.rewardXp, dialog.card.rewardCoins),
            confirmText = stringResource(R.string.mission_popup_cancel_confirm),
            dismissText = stringResource(R.string.mission_popup_cancel_dismiss),
            onConfirm = actions::confirmDialog, onDismiss = actions::dismissDialog,
        )
        is MissionDialog.Clear -> ConfirmPopup(
            title = stringResource(R.string.mission_popup_clear_title), body = dialog.card.title,
            confirmText = stringResource(R.string.mission_popup_clear_confirm), dismissText = close,
            onConfirm = actions::confirmDialog, onDismiss = actions::dismissDialog,
        )
    }
}

/** 미션 목록: 도전 슬롯(늘 [MissionTabState.slotCount] 칸) → 개인 → 주간 */
@Composable
private fun MissionLists(state: MissionTabState, actions: MissionTabActions, nowMs: Long) {
    Section(R.drawable.challenge_icon, stringResource(R.string.mission_topic), stringResource(R.string.mission_info_challenge)) {
        for (card in state.slot) MissionBox(card, nowMs, onLongPress = { actions.longPressCard(card.uid) })
        repeat((state.slotCount - state.slot.size).coerceAtLeast(0)) { MissionBlank(stringResource(R.string.mission_blank_slot)) }
    }
    Spacer(Modifier.height(MissionScreenSizes.sectionGap))
    Section(R.drawable.personal_icon, stringResource(R.string.mission_section_personal), stringResource(R.string.mission_info_personal)) {
        if (state.personal.isEmpty()) {
            // 남은 개인 미션이 없을 때: 도전 중인 개인 미션이 있으면 '다 골랐다', 없으면 '다 끝냈다'
            val picked = state.slot.any { it.kind == MissionKind.PERSONAL && it.status == MissionStatus.IN_PROGRESS }
            MissionBlank(stringResource(if (picked) R.string.mission_blank_personal_picked else R.string.mission_blank_personal))
        }
        for (card in state.personal) MissionBox(card, nowMs, dimmed = state.slotFull, onClick = { actions.tapCard(card.uid) })
    }
    Spacer(Modifier.height(MissionScreenSizes.sectionGap))
    Section(R.drawable.weekly_icon, stringResource(R.string.mission_section_weekly), stringResource(R.string.mission_info_weekly)) {
        for (card in state.weekly) MissionBox(card, nowMs, dimmed = state.slotFull, onClick = { actions.tapCard(card.uid) })
    }
}

/** 완료 미션 목록: 최근 성공이 위. 비었으면 안내 한 줄 */
@Composable
private fun CompletedList(state: MissionTabState) {
    Section(R.drawable.complete_icon, stringResource(R.string.mission_section_complete), stringResource(R.string.mission_info_complete)) {
        if (state.completed.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(MissionBoxSizes.height), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.mission_complete_empty), style = AppType.smallRegular, color = AppPalette.textSecondary)
            }
        }
        for (card in state.completed) MissionBox(card, nowMs = 0L, completed = true)
    }
}

/**
 * mission_section: 왼쪽 열에 아이콘 상자 + 제목 + info_icon(첫 카드 위쪽과 맞춤), 오른쪽 열에 카드 목록.
 * 1칸 창이면 머리를 카드 목록 위에 두고 카드 목록이 폭을 다 쓴다. 카드는 받은 폭을 채운다.
 */
@Composable
private fun Section(@DrawableRes icon: Int, title: String, info: String, cards: @Composable () -> Unit) {
    val header = @Composable { modifier: Modifier ->
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(icon), contentDescription = null, Modifier.size(MissionScreenSizes.headerIcon))
            Spacer(Modifier.width(MissionScreenSizes.headerIconGap))
            Text(title, style = AppType.mediumBold)
            InfoWithBubble(info)
        }
    }
    val cardList = @Composable { modifier: Modifier ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(MissionScreenSizes.cardGap)) { cards() }
    }
    if (LocalNarrowWindow.current) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MissionScreenSizes.narrowHeaderGap)) {
            header(Modifier)
            cardList(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth()) {
            header(Modifier.weight(1f))
            cardList(Modifier.width(MissionScreenSizes.cardColumnWidth))
        }
    }
}

/** info_icon 과 mission_info 말풍선. 말풍선은 아이콘 오른쪽 [MissionInfoSizes.iconGap], 세로 가운데에 다른 요소 위로 뜬다 */
@Composable
private fun InfoWithBubble(text: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        InfoIcon(onClick = { open = true }, contentDescription = stringResource(R.string.mission_info_open))
        if (open) {
            val x = with(LocalDensity.current) {
                ((InfoIconSizes.touchArea + InfoIconSizes.icon) / 2 + MissionInfoSizes.iconGap).roundToPx()
            }
            Popup(alignment = Alignment.CenterStart, offset = IntOffset(x, 0), onDismissRequest = { open = false }) {
                MissionInfo(text, onDismiss = { open = false })
            }
        }
    }
}

/**
 * mission_box: 폭 832, 높이는 최소 168이고 내용이 넘치면 늘어난다. 위 줄 (status_chip) 제목 … 보상 칩, 그다음 설명,
 * 도전 중이면 아래 줄에 달성률(가운데)·남은 시간(오른쪽, 한 줄에 안 들어가면 다음 줄),
 * 완료 목록이면 아래 줄 오른쪽에 완료 날짜. [onClick] 은 누름, [onLongPress] 는 길게 누름. 둘 다 없으면 눌러도 아무 일이 없다.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MissionBox(
    card: MissionCard,
    nowMs: Long,
    dimmed: Boolean = false,
    completed: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
) {
    val background = when {
        completed -> AppPalette.surface
        card.status == MissionStatus.OFFERED -> AppPalette.white
        card.status == MissionStatus.IN_PROGRESS -> AppPalette.surfaceBlue
        card.status == MissionStatus.SUCCEEDED -> AppTints.successCard
        card.status == MissionStatus.FAILED -> AppTints.failCard
        else -> AppPalette.surface
    }
    val outline = if (!completed && card.status == MissionStatus.OFFERED) {
        Modifier.border(MissionBoxSizes.border, AppPalette.line, AppShapes.radiusL)
    } else Modifier
    val press = if (onClick == null && onLongPress == null) Modifier else Modifier.combinedClickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick ?: {},
        onLongClick = onLongPress,
    )
    Column(
        Modifier.fillMaxWidth().heightIn(min = MissionBoxSizes.height)
            .alpha(if (dimmed) MissionScreenSizes.DIM_ALPHA else 1f)
            .background(background, AppShapes.radiusL)
            .then(outline)
            .then(press)
            .padding(horizontal = MissionBoxSizes.horizontalPadding, vertical = MissionBoxSizes.verticalPadding),
        verticalArrangement = Arrangement.spacedBy(MissionBoxSizes.lineGap, Alignment.CenterVertically),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!completed && card.status.judged) {
                StatusChip(card.status)
                Spacer(Modifier.width(StatusChipSizes.titleGap))
            }
            Text(card.title, Modifier.weight(1f), style = AppType.smallBold, maxLines = MissionBoxSizes.TITLE_MAX_LINES, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(RewardChipSizes.gap)) {
                if (card.rewardCoins > 0) RewardChip(R.drawable.coin_icon, card.rewardCoins)
                if (card.rewardXp > 0) RewardChip(R.drawable.exp_icon, card.rewardXp)
            }
        }
        Text(
            card.description, style = AppType.smallRegular, color = AppPalette.textSecondary,
            maxLines = MissionBoxSizes.DESCRIPTION_MAX_LINES, overflow = TextOverflow.Ellipsis,
        )
        if (completed) {
            card.completedAtMs?.let { at ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    LabelValue(stringResource(R.string.mission_completed_label), completedDate.format(Instant.ofEpochMilli(at).atZone(MISSION_ZONE)))
                }
            }
        } else if (card.status == MissionStatus.IN_PROGRESS) {
            val timeLeftText = timeLeft(card.validUntilMs - nowMs)
            ProgressTimeRow(
                progress = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionBoxSizes.partGap)) {
                        Text(stringResource(R.string.mission_progress_label), style = AppType.smallRegular, color = AppPalette.textSecondary)
                        ProgressBar(card.progressFraction)
                        // 아직 센 값이 없으면 "-" 대신 0 으로 보인다
                        Text(card.progress.replace("-/", "0/"), style = AppType.smallBold)
                    }
                },
                timeLeft = { LabelValue(stringResource(R.string.mission_time_left_label), timeLeftText) },
            )
        }
    }
}

/**
 * mission_box 아래 줄: [progress] 는 가운데, [timeLeft] 는 오른쪽 끝, 둘 다 세로 가운데.
 * 둘 사이가 [MissionBoxSizes.partGap] 보다 좁아지면 [progress] 를 왼쪽에 두고 [timeLeft] 를 다음 줄 오른쪽으로 내린다.
 */
@Composable
private fun ProgressTimeRow(progress: @Composable () -> Unit, timeLeft: @Composable () -> Unit) {
    Layout(content = { progress(); timeLeft() }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val p = measurables[0].measure(loose)
        val t = measurables[1].measure(loose)
        val width = constraints.maxWidth
        val progressEnd = (width + p.width) / 2
        val oneLine = progressEnd + MissionBoxSizes.partGap.roundToPx() <= width - t.width
        if (oneLine) {
            val height = maxOf(p.height, t.height)
            layout(width, height) {
                p.place((width - p.width) / 2, (height - p.height) / 2)
                t.place(width - t.width, (height - t.height) / 2)
            }
        } else {
            val gap = MissionBoxSizes.lineGap.roundToPx()
            layout(width, p.height + gap + t.height) {
                p.place(0, 0)
                t.place(width - t.width, p.height + gap)
            }
        }
    }
}

/** 라벨(small_regular, text_secondary) + 값(small_bold) */
@Composable
private fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MissionBoxSizes.partGap)) {
        Text(label, style = AppType.smallRegular, color = AppPalette.textSecondary)
        Text(value, style = AppType.smallBold)
    }
}

/** 남은 시간: 하루 이상이면 "N일 남음", 아니면 HH:MM:SS */
@Composable
private fun timeLeft(remainingMs: Long): String {
    val ms = remainingMs.coerceAtLeast(0L)
    if (ms >= MS_PER_DAY) return stringResource(R.string.mission_days_left, (ms / MS_PER_DAY).toInt())
    val seconds = ms / MS_PER_SECOND
    val h = seconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
    val m = seconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
    val s = seconds % SECONDS_PER_MINUTE
    return String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, s)
}

/** mission_progress_bar: 바탕 line, 채움 primary_blue (홈 미션 카드와 맞춘다, 11/14) */
@Composable
private fun ProgressBar(fraction: Float) {
    Box(
        Modifier.size(MissionProgressBarSizes.width, MissionProgressBarSizes.height)
            .background(AppPalette.line, AppShapes.pill),
    ) {
        if (fraction > 0f) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(AppPalette.primaryBlue, AppShapes.pill))
        }
    }
}

/** status_chip: "성공"(green_soft) / "실패"(red_soft) */
@Composable
private fun StatusChip(status: MissionStatus) {
    val (text, background, color) = when (status) {
        MissionStatus.SUCCEEDED -> Triple(stringResource(R.string.mission_status_succeeded), AppPalette.greenSoft, AppPalette.greenDark)
        else -> Triple(stringResource(R.string.mission_status_failed), AppPalette.redSoft, AppPalette.red)
    }
    Box(
        Modifier.size(StatusChipSizes.width, StatusChipSizes.height).background(background, AppShapes.pill),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = AppType.smallRegular, color = color) }
}

/** reward 칩: 바탕 white, 테두리 line 1, 아이콘 + 숫자 */
@Composable
private fun RewardChip(@DrawableRes icon: Int, amount: Int) {
    Row(
        Modifier.height(RewardChipSizes.height)
            .background(AppPalette.white, AppShapes.pill)
            .border(RewardChipSizes.border, AppPalette.line, AppShapes.pill)
            .padding(horizontal = RewardChipSizes.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RewardChipSizes.gap),
    ) {
        Image(painterResource(icon), contentDescription = null, Modifier.size(RewardChipSizes.icon))
        Text(amount.toString(), style = AppType.smallBold)
    }
}

/** mission_blank: 바탕 surface_blue, 점선 테두리 line 2, 안내 글 가운데. 눌러도 아무 일이 없다 */
@Composable
private fun MissionBlank(text: String) {
    val density = LocalDensity.current
    val stroke = with(density) { MissionBoxSizes.border.toPx() }
    val dash = with(density) { floatArrayOf(MissionBoxSizes.dash.toPx(), MissionBoxSizes.dashGap.toPx()) }
    val radius = with(density) { MissionScreenSizes.cardRadius.toPx() }
    Box(
        Modifier.fillMaxWidth().height(MissionBoxSizes.height)
            .background(AppPalette.surfaceBlue, AppShapes.radiusL)
            .drawBehind {
                drawRoundRect(
                    color = AppPalette.line,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(dash)),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = AppType.smallRegular, color = AppPalette.textSecondary, textAlign = TextAlign.Center)
    }
}

/**
 * complete_mission: 기본 surface + 테두리 line_strong 1, 선택이면 button_dark 바탕 흰 글자. 누르는 영역 높이 64.
 * [enabled] 가 false 면 눌리지 않고 글자·테두리를 text_disabled 로 그린다 (tab_button 비활성과 같은 모양)
 */
@Composable
private fun CompleteMissionButton(selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.height(AppDimens.touchTarget).pressable(interaction, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val edge = inkOrDisabled(AppPalette.lineStrong, enabled)
        val background: Color = if (selected) AppPalette.buttonDark else AppPalette.surface
        Box(
            Modifier.size(CompleteMissionSizes.width, CompleteMissionSizes.height)
                .background(background, AppShapes.pill)
                .then(if (selected) Modifier else Modifier.border(CompleteMissionSizes.border, edge, AppShapes.pill)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.complete_mission_button), style = AppType.smallBold,
                color = if (selected) AppPalette.white else inkOrDisabled(AppPalette.ink, enabled),
            )
        }
    }
}

private fun noticeText(n: MissionNotice) = when (n) {
    MissionNotice.DRIVING -> R.string.mission_notice_driving
    MissionNotice.SLOT_FULL -> R.string.mission_notice_slot_full
    MissionNotice.EXPIRED -> R.string.mission_notice_expired
    MissionNotice.CANCELLED -> R.string.mission_notice_cancelled
}

package ai.safetypoc.app.ui.nav

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.TabBar
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes

import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.appShadow
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import ai.safetypoc.app.ui.theme.TabButtonSizes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** 탭 화면 배치 값 (디자인 시스템 3절 "화면 뼈대") */
object TabFrameSizes {
    /** 창 왼쪽 끝에서 탭 내용(미션, 통계 등)까지: 여백 a + tab_bar 폭 + 여백 a [임시] */
    val contentStart = AppDimens.screenMargin + TabButtonSizes.width + AppDimens.screenMargin
    /** 접힌 탭(미션·통계): 작은 버튼 폭 [가안] (11/14) */
    val compactWidth = 128.dp
    /** 접힌 탭 화면의 내용 시작 */
    val compactContentStart = AppDimens.screenMargin + compactWidth + AppDimens.screenMargin
    /** 1칸 창 헤더 오른쪽 칸: 버튼 사이 */
    val headerActionGap = 16.dp
    /** 1칸 창: 창 위 끝에서 헤더(접힌 탭 버튼 줄) 아래 끝까지 */
    val headerBottom = AppDimens.screenMargin + TabButtonSizes.height
    /** 1칸 창: 창 위 끝에서 내용 시작까지. 헤더 아래 누르는 영역 간격 [임시] */
    val narrowContentTop = headerBottom + AppDimens.touchGap
}

/**
 * 1칸 창 헤더 오른쪽 칸. [TabFrame] 이 만들어 내용에 내려 주고, 화면은 [TabHeaderActions] 로 채운다.
 * 한 번에 한 화면의 버튼만 둔다.
 */
internal class TabHeaderSlot {
    var actions by mutableStateOf<(@Composable RowScope.() -> Unit)?>(null)
}

internal val LocalTabHeaderSlot = staticCompositionLocalOf<TabHeaderSlot?> { null }

/**
 * 1칸 창에서 [content] 를 헤더 오른쪽 칸(↑→a, 접힌 탭 버튼과 세로 가운데)에 놓는다.
 * 이 함수가 화면에서 빠지면 칸도 빈다. [TabFrame] 밖이면 아무것도 하지 않는다.
 */
@Composable
internal fun TabHeaderActions(content: @Composable RowScope.() -> Unit) {
    val slot = LocalTabHeaderSlot.current ?: return
    val latest by rememberUpdatedState(content)
    DisposableEffect(slot) {
        val actions: @Composable RowScope.() -> Unit = { latest(this) }
        slot.actions = actions
        onDispose { if (slot.actions === actions) slot.actions = null }
    }
}

/**
 * 탭 화면의 뼈대: 창 전체에 [content] 를 그리고, 그 위 왼쪽 위(↑←a)에 tab_bar 를 놓는다.
 * [compact] 면(미션·통계) tab_bar 대신 지금 탭 이름이 적힌 작은 버튼만 두고, 누르면 tab_bar 가 화면 위에 뜬다.
 * 바깥을 누르거나 탭을 고르면 다시 접힌다. 내용이 더 넓게 쓰인다 (11/14).
 * 1칸 창([LocalNarrowWindow])이면 [compact] 와 상관없이 늘 접고, 위 줄을 헤더로 쓴다.
 * 헤더 왼쪽은 접힌 탭 버튼, 오른쪽은 화면이 [TabHeaderActions] 로 넘긴 버튼이다. 내용은 [tabContentArea] 로 헤더 아래에 놓는다.
 * [driving] 이면 tab_bar 의 버튼이 모두 비활성이다(디자인 시스템 2절 "주행 중 조작").
 * [selectedIndex] 가 [labels] 범위 밖이면 선택된 탭이 없다.
 */
@Composable
internal fun TabFrame(
    labels: List<String>,
    selectedIndex: Int,
    driving: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val folded = compact || LocalNarrowWindow.current
    // 탭이 아닌 화면(설정 등)에서는 접힌 버튼에 지금 탭 이름 대신 "메뉴"를 쓴다
    val foldedLabel = labels.getOrNull(selectedIndex) ?: stringResource(R.string.tab_menu)
    var open by remember(folded, driving) { mutableStateOf(false) }
    val headerSlot = remember { TabHeaderSlot() }
    Box(modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalTabHeaderSlot provides headerSlot, content = content)
        if (LocalNarrowWindow.current) {
            headerSlot.actions?.let { actions ->
                Row(
                    Modifier.align(Alignment.TopEnd).padding(AppDimens.screenMargin).height(TabButtonSizes.height),
                    horizontalArrangement = Arrangement.spacedBy(TabFrameSizes.headerActionGap),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        }
        val place = Modifier.align(Alignment.TopStart).padding(AppDimens.screenMargin)
        // 주행 중에는 작은 탭 버튼만 둔다(11/14). 눌러 펼쳐도 다른 탭으로는 갈 수 없다(주행 중 조작 금지): 탭이 흐리게 보인다
        if (driving) {
            CompactTabButton(foldedLabel, place) { open = true }
            if (open) {
                Box(
                    Modifier.fillMaxSize().background(AppPalette.ink.copy(alpha = TAB_SCRIM_ALPHA))
                        .pressable(remember { MutableInteractionSource() }) { open = false },
                )
                TabBar(labels = labels, selectedIndex = selectedIndex, driving = true, onSelect = {}, modifier = place)
            }
            return@Box
        }
        if (!folded) {
            TabBar(labels = labels, selectedIndex = selectedIndex, driving = false, onSelect = onSelect, modifier = place)
            return@Box
        }
        CompactTabButton(foldedLabel, place) { open = true }
        if (open) {
            // 바깥(옅게 가린 화면)을 누르면 닫는다
            Box(
                Modifier.fillMaxSize().background(AppPalette.ink.copy(alpha = TAB_SCRIM_ALPHA))
                    .pressable(remember { MutableInteractionSource() }) { open = false },
            )
            TabBar(labels = labels, selectedIndex = selectedIndex, driving = false, onSelect = { open = false; onSelect(it) }, modifier = place)
        }
    }
}

/** 접힌 tab_bar: 흰 판에 지금 탭 이름과 아래 작은 ▾. 누르면 tab_bar 가 뜬다 */
@Composable
private fun CompactTabButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.size(TabFrameSizes.compactWidth, TabButtonSizes.height)
            .appShadow(AppShadows.soft, AppShapes.radiusXl)
            .background(AppPalette.white, AppShapes.radiusXl)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, style = AppType.smallBold, color = AppPalette.primaryBlue, maxLines = 1)
        Text("▾", style = AppType.smallRegular, color = AppPalette.textTertiary)
    }
}

/**
 * 스테이지가 없는 탭 내용(미션, 통계, 주행 종료, 디버그)이 놓이는 영역: tab_bar 오른쪽, 나머지 세 변은 여백 a.
 * 1칸 창이면 헤더 아래 [TabFrameSizes.narrowContentTop] 부터 좌우 여백 a.
 */
@Composable
internal fun Modifier.tabContentArea(compact: Boolean = false): Modifier =
    if (LocalNarrowWindow.current) fillMaxSize().padding(
        start = AppDimens.screenMargin,
        top = TabFrameSizes.narrowContentTop,
        end = AppDimens.screenMargin,
        bottom = AppDimens.screenMargin,
    ) else fillMaxSize().padding(
        start = if (compact) TabFrameSizes.compactContentStart else TabFrameSizes.contentStart,
        top = AppDimens.screenMargin,
        end = AppDimens.screenMargin,
        bottom = AppDimens.screenMargin,
    )

/** 펼친 탭 뒤 화면을 옅게 가린다 */
private const val TAB_SCRIM_ALPHA = 0.12f

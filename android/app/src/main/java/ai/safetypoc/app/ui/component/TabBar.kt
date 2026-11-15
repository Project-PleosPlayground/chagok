package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.TabBarSizes
import ai.safetypoc.app.ui.theme.TabButtonSizes
import androidx.compose.foundation.background
import ai.safetypoc.app.ui.theme.appShadow
import ai.safetypoc.app.ui.theme.AppShadows
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview

/** tab_button 상태. 누름은 상태가 아니라 누르는 동안의 모양이다 */
enum class TabButtonState {
    /** 기본: 바탕 없음, text_secondary 글자. 누르는 동안 연한 바탕 */
    Default,
    /** 선택: white 글자. 파란 알약은 tab_bar 가 버튼 밑에 그리고 옮긴다 */
    Selected,
    /** 주행 중 비활성: surface 바탕, text_disabled 글자, 눌리지 않음 */
    Disabled,
}

/** tab_button: tab_bar 안의 메뉴 버튼 하나 */
@Composable
fun TabButton(
    label: String,
    state: TabButtonState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    TabButtonBody(
        label = label,
        state = state,
        pressed = pressed,
        modifier = modifier.pressable(
            interactionSource = interactionSource,
            enabled = state != TabButtonState.Disabled,
            onClick = onClick,
        ),
    )
}

/** tab_button의 모양. [pressed]는 기본 상태에서만 바탕을 바꾼다 */
@Composable
private fun TabButtonBody(
    label: String,
    state: TabButtonState,
    pressed: Boolean,
    modifier: Modifier = Modifier,
) {
    val background: Color
    val textColor: Color
    when (state) {
        TabButtonState.Default -> {
            background = if (pressed) AppTints.tabButtonPressed else Color.Transparent
            textColor = AppPalette.textSecondary
        }
        TabButtonState.Selected -> {
            background = Color.Transparent
            textColor = AppPalette.white
        }
        TabButtonState.Disabled -> {
            background = Color.Transparent
            textColor = AppTints.textDisabled
        }
    }
    Box(
        modifier = modifier
            .semantics { selected = state == TabButtonState.Selected }
            .size(TabButtonSizes.width, TabButtonSizes.height)
            .clip(AppShapes.radiusXl)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = AppType.mediumBold, color = textColor)
    }
}

/**
 * tab_bar: 탭 화면 왼쪽 위의 메뉴. 흰 판 하나에 [labels] 순서대로 tab_button을 세로로 놓고,
 * 고른 탭 밑의 파란 알약(drive_start_button 과 같은 그라데이션)이 탭을 바꾸면 미끄러지듯 따라간다 (11/14 [가안]).
 * [driving]이면 모든 버튼이 비활성이고 알약도 그리지 않는다(주행 중에는 TabFrame 이 tab_bar 를 숨긴다).
 */
@Composable
fun TabBar(
    labels: List<String>,
    selectedIndex: Int,
    driving: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val step = TabButtonSizes.height + TabBarSizes.innerGap
    val pillY by animateDpAsState(step * selectedIndex.coerceAtLeast(0), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "tabPill")
    Box(
        modifier
            .appShadow(AppShadows.soft, AppShapes.radiusXl)
            .background(AppPalette.white, AppShapes.radiusXl)
            .padding(TabBarSizes.inset),
    ) {
        if (!driving && selectedIndex in labels.indices) {
            Box(
                Modifier.offset(y = pillY).size(TabButtonSizes.width, TabButtonSizes.height)
                    .appShadow(AppShadows.float, AppShapes.radiusXl)
                    .background(TAB_PILL_BRUSH, AppShapes.radiusXl),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(TabBarSizes.innerGap)) {
            labels.forEachIndexed { index, label ->
                val state = when {
                    driving -> TabButtonState.Disabled
                    index == selectedIndex -> TabButtonState.Selected
                    else -> TabButtonState.Default
                }
                TabButton(label = label, state = state, onClick = { onSelect(index) })
            }
        }
    }
}

/** 고른 탭 알약: primary_blue 에서 위쪽을 흰색 12% 만큼 밝힌다 (drive_start_button 과 같다) */
private val TAB_PILL_BRUSH = Brush.verticalGradient(listOf(lerp(AppPalette.primaryBlue, AppPalette.white, 0.12f), AppPalette.primaryBlue))

@Composable
private fun TabPreviewFrame(content: @Composable () -> Unit) {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) { content() }
    }
}

private val previewTabLabels = listOf("홈", "미션", "아이템", "통계")

@Preview
@Composable
internal fun TabButtonDefaultPreview() = TabPreviewFrame {
    TabButton(label = "미션", state = TabButtonState.Default, onClick = {})
}

@Preview
@Composable
internal fun TabButtonPressedPreview() = TabPreviewFrame {
    TabButtonBody(label = "미션", state = TabButtonState.Default, pressed = true)
}

@Preview
@Composable
internal fun TabButtonSelectedPreview() = TabPreviewFrame {
    TabButton(label = "홈", state = TabButtonState.Selected, onClick = {})
}

@Preview
@Composable
internal fun TabButtonDisabledPreview() = TabPreviewFrame {
    TabButton(label = "통계", state = TabButtonState.Disabled, onClick = {})
}

@Preview
@Composable
internal fun TabBarDefaultPreview() = TabPreviewFrame {
    TabBar(labels = previewTabLabels, selectedIndex = 0, driving = false, onSelect = {})
}

@Preview
@Composable
internal fun TabBarDrivingPreview() = TabPreviewFrame {
    TabBar(labels = previewTabLabels, selectedIndex = 0, driving = true, onSelect = {})
}

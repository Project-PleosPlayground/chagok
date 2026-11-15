package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.ButtonDarkSizes
import ai.safetypoc.app.ui.theme.ButtonOutlineSizes
import ai.safetypoc.app.ui.theme.inkOrDisabled
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

/** button_dark·button_light의 크기 종류 */
enum class ButtonSize {
    /** 화면 안 */
    Screen,
    /** 팝업 안 */
    Popup,
    /** 빈 상태 안(mission_empty). 모서리 radius_xl */
    EmptyState,
}

/** button_dark: 진한 바탕에 흰 글자 버튼 */
@Composable
fun ButtonDark(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Screen,
    enabled: Boolean = true,
) {
    TextButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        width = size.width(),
        height = size.height(),
        shape = size.shape(),
        background = AppPalette.buttonDark,
        textColor = AppPalette.white,
    )
}

/** button_light: 회색 바탕 버튼. button_dark와 짝을 이룬다 */
@Composable
fun ButtonLight(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Screen,
    enabled: Boolean = true,
) {
    TextButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        width = size.width(),
        height = size.height(),
        shape = size.shape(),
        background = AppPalette.surface,
        textColor = AppPalette.ink,
    )
}

/** button_outline: 옅은 흰 바탕에 테두리 버튼 */
@Composable
fun ButtonOutline(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButtonBase(
        text = text,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        width = ButtonOutlineSizes.width,
        height = ButtonOutlineSizes.height,
        background = AppTints.buttonOutline,
        textColor = AppPalette.ink,
        border = BorderStroke(ButtonOutlineSizes.borderWidth, AppPalette.ink),
    )
}

private fun ButtonSize.width(): Dp = when (this) {
    ButtonSize.Screen -> ButtonDarkSizes.screenWidth
    ButtonSize.Popup -> ButtonDarkSizes.popupWidth
    ButtonSize.EmptyState -> ButtonDarkSizes.emptyStateWidth
}

private fun ButtonSize.height(): Dp = when (this) {
    ButtonSize.Screen -> ButtonDarkSizes.screenHeight
    ButtonSize.Popup -> ButtonDarkSizes.popupHeight
    ButtonSize.EmptyState -> ButtonDarkSizes.emptyStateHeight
}

private fun ButtonSize.shape(): Shape = when (this) {
    ButtonSize.Screen, ButtonSize.Popup -> AppShapes.radiusL
    ButtonSize.EmptyState -> AppShapes.radiusXl
}

/**
 * 글자 하나를 가운데 둔 버튼. 누르는 동안 바탕 위에 누름 색(ink 15%)을 덮는다.
 * [enabled]가 false면 눌리지 않고, tab_button 주행 중 비활성과 같은 모양(바탕 surface, 글자·테두리 text_disabled)으로 그린다
 */
@Composable
private fun TextButtonBase(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier,
    width: Dp,
    height: Dp,
    background: Color,
    textColor: Color,
    border: BorderStroke? = null,
    shape: Shape = AppShapes.radiusL,
    enabled: Boolean = true,
) {
    val fill = if (enabled) background else AppPalette.surface
    val ink = inkOrDisabled(textColor, enabled)
    val edge = if (enabled || border == null) border else BorderStroke(border.width, AppTints.textDisabled)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .size(width, height)
            .background(fill, shape)
            .then(if (pressed) Modifier.background(AppTints.tabButtonPressed, shape) else Modifier)
            .then(if (edge != null) Modifier.border(edge, shape) else Modifier)
            .pressable(interactionSource, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = AppType.smallBold, color = ink, textAlign = TextAlign.Center)
    }
}

@Preview
@Composable
internal fun ButtonDarkScreenPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonDark(text = "확인", onClick = {})
        }
    }
}

@Preview
@Composable
internal fun ButtonDarkPopupPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonDark(text = "확인", onClick = {}, size = ButtonSize.Popup)
        }
    }
}

@Preview
@Composable
internal fun ButtonDarkDisabledPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonDark(text = "저장", onClick = {}, enabled = false)
        }
    }
}

@Preview
@Composable
internal fun ButtonOutlineDisabledPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonOutline(text = "취소", onClick = {}, enabled = false)
        }
    }
}

@Preview
@Composable
internal fun ButtonDarkEmptyStatePreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonDark(text = "미션 고르기", onClick = {}, size = ButtonSize.EmptyState)
        }
    }
}

@Preview
@Composable
internal fun ButtonLightScreenPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonLight(text = "취소", onClick = {})
        }
    }
}

@Preview
@Composable
internal fun ButtonLightPopupPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            ButtonLight(text = "취소", onClick = {}, size = ButtonSize.Popup)
        }
    }
}

@Preview
@Composable
internal fun ButtonOutlineDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.surface).padding(AppDimens.screenMargin)) {
            ButtonOutline(text = "다시", onClick = {})
        }
    }
}

package ai.safetypoc.app.ui.component

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.BackButtonSizes
import ai.safetypoc.app.ui.theme.InfoIconSizes
import ai.safetypoc.app.ui.theme.NextButtonSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview

/** next_button: navy 원 안에 next_icon */
@Composable
fun NextButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(NextButtonSizes.diameter)
            .background(AppPalette.navy, AppShapes.circle)
            .pressable(interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.next_icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(NextButtonSizes.iconFrame),
            tint = AppPalette.white,
        )
    }
}

/** back_button: back_icon. 누르는 영역은 아이콘보다 크다 */
@Composable
fun BackButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(BackButtonSizes.touchArea)
            .pressable(interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.back_icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(BackButtonSizes.icon),
            tint = AppPalette.ink,
        )
    }
}

/** info_icon: 원 안 느낌표. 누르는 영역은 아이콘보다 크다 */
@Composable
fun InfoIcon(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(InfoIconSizes.touchArea)
            .pressable(interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.info_icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(InfoIconSizes.icon),
            tint = AppTints.infoIcon,
        )
    }
}

@Preview
@Composable
internal fun NextButtonDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            NextButton(onClick = {}, contentDescription = "다음")
        }
    }
}

@Preview
@Composable
internal fun BackButtonDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.surfaceBlue).padding(AppDimens.screenMargin)) {
            BackButton(onClick = {}, contentDescription = "뒤로")
        }
    }
}

@Preview
@Composable
internal fun InfoIconDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            InfoIcon(onClick = {}, contentDescription = "미션 설명")
        }
    }
}

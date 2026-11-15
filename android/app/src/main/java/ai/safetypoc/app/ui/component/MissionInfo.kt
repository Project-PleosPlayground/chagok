package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTiming
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.MissionInfoSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

/** mission_info 글의 최대 줄 수 */
private const val MISSION_INFO_MAX_LINES = 2

/**
 * mission_info: info_icon을 누르면 뜨는 말풍선(L3). 막(scrim)이 없고 뒤 화면을 막지 않는다.
 * 폭은 글에 맞춘다. 최대 폭은 부르는 쪽이 [modifier]로 준다. 넘치면 두 줄.
 * [AppTiming.MISSION_INFO_MS] 뒤, 또는 말풍선을 누르면 [onDismiss].
 * 자리(info_icon 오른쪽 [MissionInfoSizes.iconGap], 세로 가운데)는 화면이 잡는다.
 */
@Composable
fun MissionInfo(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(text) {
        delay(AppTiming.MISSION_INFO_MS)
        currentOnDismiss()
    }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = AppDimens.touchTarget)
            .pressable(interaction, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .defaultMinSize(minHeight = MissionInfoSizes.minHeight)
                .background(AppPalette.white, AppShapes.pill)
                .border(MissionInfoSizes.border, AppTints.missionInfoBorder, AppShapes.pill)
                .padding(
                    horizontal = MissionInfoSizes.horizontalPadding,
                    vertical = MissionInfoSizes.verticalPadding,
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = text,
                color = AppPalette.ink,
                style = AppType.smallRegular,
                maxLines = MISSION_INFO_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview
@Composable
internal fun MissionInfoOneLinePreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.surface).padding(AppDimens.screenMargin)) {
            MissionInfo(
                text = "최근 1개월 동안 완료한 미션을 볼 수 있어요.",
                onDismiss = {},
            )
        }
    }
}

@Preview
@Composable
internal fun MissionInfoTwoLinesPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.surface).padding(AppDimens.screenMargin)) {
            MissionInfo(
                text = "한번 주행에 최대 3개까지 도전할 수 있어요.\n미션을 길게 누르면 도전을 그만둘 수 있습니다.",
                onDismiss = {},
            )
        }
    }
}

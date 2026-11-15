package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.DividerSizes
import ai.safetypoc.app.ui.theme.TabButtonSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

/** divider: 가로 구분선. 폭은 놓인 자리를 채운다 */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(DividerSizes.thickness)
            .background(AppPalette.lineStrong),
    )
}

@Preview
@Composable
internal fun DividerDefaultPreview() {
    AppTheme {
        Box(Modifier.background(AppPalette.white).padding(AppDimens.screenMargin)) {
            Divider(Modifier.width(TabButtonSizes.width))
        }
    }
}

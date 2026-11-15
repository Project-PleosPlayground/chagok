package ai.safetypoc.app.ui.drive

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.home.PREVIEW_WINDOW_HEIGHT
import ai.safetypoc.app.ui.home.PREVIEW_WINDOW_WIDTH
import ai.safetypoc.app.ui.nav.Destination
import ai.safetypoc.app.ui.nav.Phase
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview

/**
 * 프로텍터를 덮는 때: 주행 중이고 진입 전 화면(온보딩, 프로필 선택·추가·수정)에 있을 때.
 * [driving] 은 홈 고정([ai.safetypoc.app.ui.nav.Navigator.lockToHome])을 켜는 값과 같다([ai.safetypoc.app.drive.DriveMode.driving]).
 * 진입 후에는 덮지 않고 홈(주행 화면) 고정이 맡는다.
 */
fun protectorShown(driving: Boolean, current: Destination): Boolean = driving && current.phase == Phase.ENTRY

/**
 * 주행 중 전면 프로텍터: 지금 화면 위 창 전체에 반투명 막과 안내 문구를 덮는다.
 * 막이 모든 터치를 받아 버리므로 아래 화면은 눌리지 않는다. 뒤로 가기 키는 막지 않는다(앱 숨김).
 */
@Composable
fun DriveProtector(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(AppTints.driveProtector)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(stringResource(R.string.drive_protector), style = AppType.largeBold, color = AppPalette.white, textAlign = TextAlign.Center)
    }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun DriveProtectorPreview() = DriveProtector()

package ai.safetypoc.app.ui.component

import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.ConfirmPopupSizes
import ai.safetypoc.app.ui.theme.appShadow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit

/** 줄 상자 높이를 [lineHeight] 그대로 두는 글자 모양 */
private fun TextStyle.withLineBox(lineHeight: TextUnit): TextStyle = copy(
    lineHeight = lineHeight,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/**
 * confirm_popup: 확인 팝업(L3). 화면 위에 겹쳐 놓는 전체 크기 막(scrim)과 가운데 카드.
 * 막을 누르거나 뒤로 가기(Back)를 누르면 [onDismiss]. 카드를 눌러도 아무 일이 없다.
 * [body]가 null이면 본문 자리를 뺀다.
 * 왼쪽 버튼 [confirmText]는 하려는 일([onConfirm]), 오른쪽 버튼 [dismissText]는 닫기([onDismiss]).
 */
@Composable
fun ConfirmPopup(
    title: String,
    body: String?,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onDismiss)
    val scrimInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppTints.scrim)
            .pressable(scrimInteraction, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ConfirmPopupSizes.width)
                .appShadow(AppShadows.soft, AppShapes.radiusXl)
                .background(AppPalette.white, AppShapes.radiusXl)
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(ConfirmPopupSizes.padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                color = AppPalette.ink,
                style = AppType.largeBold.withLineBox(ConfirmPopupSizes.titleLineHeight),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (body != null) {
                Spacer(Modifier.height(ConfirmPopupSizes.titleToBody))
                Text(
                    text = body,
                    modifier = Modifier.fillMaxWidth(),
                    color = AppTints.popupBody,
                    style = AppType.smallRegular.withLineBox(ConfirmPopupSizes.bodyLineHeight),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(ConfirmPopupSizes.textToButtons))
            Row(horizontalArrangement = Arrangement.spacedBy(ConfirmPopupSizes.buttonGap)) {
                ButtonDark(confirmText, onConfirm, size = ButtonSize.Popup)
                ButtonLight(dismissText, onDismiss, size = ButtonSize.Popup)
            }
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
internal fun ConfirmPopupWithBodyPreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(AppPalette.surface)) {
            ConfirmPopup(
                title = "이 미션에 도전할까요?",
                body = "[미션명]",
                confirmText = "도전하기",
                dismissText = "닫기",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

@Preview(device = Devices.DESKTOP)
@Composable
internal fun ConfirmPopupNoBodyPreview() {
    AppTheme {
        Box(Modifier.fillMaxSize().background(AppPalette.surface)) {
            ConfirmPopup(
                title = "포인트가 부족해요",
                body = null,
                confirmText = "미션하러 가기",
                dismissText = "닫기",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}

package ai.safetypoc.app.ui.home

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.CharacterStill
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppGradients
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 캐릭터 별명 최대 글자 수 */
const val CHARACTER_NAME_MAX = 8

/**
 * 캐릭터 이름 짓기 팝업 (11/14). 홈에 처음 들어왔는데 별명이 없을 때, 그리고 설정 캐릭터 카드에서 이름을 누를 때 뜬다.
 * 캐릭터 그림 + "이 친구의 이름을 지어 주세요" + 입력 칸(처음 값 [initial]) + 버튼. 비워 두고 누르면 [fallback] 으로 정한다.
 * [onDismiss] 가 있으면(이름 바꾸기) 바깥을 눌러 닫을 수 있고, 처음 짓기에는 닫기가 없다.
 */
@Composable
fun NameCharacterDialog(
    assetKey: String?,
    stage: Int,
    initial: String,
    fallback: String,
    onConfirm: (String) -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    var name by remember { mutableStateOf(initial) }
    Box(
        Modifier.fillMaxSize().background(AppTints.scrim)
            .pressable(remember { MutableInteractionSource() }) { onDismiss?.invoke() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(NameDialogSizes.width).clip(AppShapes.radiusXl).background(AppPalette.white)
                // 카드 안을 눌러도 닫히지 않게 눌림을 여기서 받는다
                .pressable(remember { MutableInteractionSource() }) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(NameDialogSizes.artHeight).background(AppGradients.scoreCardHead)) {
                CharacterStill(assetKey, stage, Modifier.fillMaxSize().padding(NameDialogSizes.artPadding)) {}
            }
            Column(
                Modifier.padding(NameDialogSizes.padding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(NameDialogSizes.gap),
            ) {
                Text(stringResource(R.string.name_character_title), style = AppType.mediumBold, color = AppPalette.ink, textAlign = TextAlign.Center)
                Text(stringResource(R.string.name_character_body), style = AppType.smallRegular, color = AppPalette.textSecondary, textAlign = TextAlign.Center)
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(CHARACTER_NAME_MAX) },
                    singleLine = true,
                    textStyle = AppType.mediumBold.copy(color = AppPalette.ink, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(AppPalette.primaryBlue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onConfirm(name.ifBlank { fallback }) }),
                    modifier = Modifier.fillMaxWidth().background(AppPalette.surface, AppShapes.pill)
                        .padding(horizontal = NameDialogSizes.fieldPadH, vertical = NameDialogSizes.fieldPadV),
                )
                // 카드 폭 그대로의 파란 버튼 (주행 시작 버튼과 같은 파랑)
                Box(
                    Modifier.fillMaxWidth().height(NameDialogSizes.buttonHeight).background(AppPalette.primaryBlue, AppShapes.pill)
                        .pressable(remember { MutableInteractionSource() }) { onConfirm(name.ifBlank { fallback }) },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.name_character_confirm), style = AppType.mediumBold, color = AppPalette.white) }
            }
        }
    }
}

/** 이름 짓기 팝업 배치 값 [가안] */
private object NameDialogSizes {
    val width = 640.dp
    val artHeight = 280.dp
    val artPadding = 24.dp
    val padding = 32.dp
    val gap = 16.dp
    val fieldPadH = 28.dp
    val fieldPadV = 18.dp
    val buttonHeight = 80.dp
}

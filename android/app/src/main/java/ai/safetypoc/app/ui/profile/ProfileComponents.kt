package ai.safetypoc.app.ui.profile

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.art.CharacterProfileArt
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppShadows
import ai.safetypoc.app.ui.theme.AppShapes
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppTints
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.appShadow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp

/**
 * 캐릭터 그림. 프로필 그림(`assets/character/<차종>/profile.png`, 시안의 Lv5 로봇)을 비율을 지켜 담는다.
 * 그림이 없거나 읽는 동안은 그림 없는 자리(platform_gray). 폭 [width], 높이는 캐릭터 그림 비율로 정한다.
 * [stage] 는 단계별 프로필 그림이 생기면 쓴다 [임시].
 */
@Composable
fun CharacterPicture(assetKey: String?, stage: Int, width: Dp, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null) {
    val frame = modifier.width(width).aspectRatio(CHARACTER_ASPECT)
    CharacterProfileArt(assetKey, frame, colorFilter) { Box(frame.background(AppPalette.platformGray, AppShapes.radiusM)) }
}

/** 누르는 동안 바탕 위에 누름 색을 덮는다. 공통 버튼(Buttons.kt)과 같은 누름 표시 */
private fun Modifier.pressOverlay(pressed: Boolean, shape: Shape): Modifier =
    if (pressed) background(AppTints.tabButtonPressed, shape) else this

/**
 * profile_card: 가운데 캐릭터 그림. 그리는 순서 profile_card → 캐릭터 그림 → edit_icon.
 * [editing]이면 그림을 흐리게 하고 가운데 edit_icon을 얹는다.
 */
@Composable
fun ProfileCard(item: ProfileCardItem, editing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(ProfileComponentSizes.card)
            // [가안] 흰 바탕 + 옅은 파랑 테두리. 누르면 테두리가 진해진다
            .appShadow(AppShadows.soft, AppShapes.radiusXl)
            .background(AppPalette.white, AppShapes.radiusXl)
            .border(
                if (pressed) ProfileCardStyle.borderPressedWidth else ProfileCardStyle.borderWidth,
                if (pressed) AppPalette.primaryBlue else ProfileCardStyle.border, AppShapes.radiusXl,
            )
            .pressable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CharacterPicture(
            item.characterAssetKey, item.stage, ProfileComponentSizes.cardPictureWidth,
            Modifier.alpha(if (editing) EDIT_PICTURE_ALPHA else 1f),
        )
        if (editing) {
            Icon(
                painter = painterResource(R.drawable.edit_icon),
                contentDescription = stringResource(R.string.profile_edit_description),
                modifier = Modifier.size(ProfileComponentSizes.editIcon),
                tint = AppPalette.ink,
            )
        }
    }
}

/** add 칸: profile_card 와 같은 크기·테두리, 가운데 옅은 파랑 원 안 add_icon [가안]. 카드와 한 줄로 가지런히 선다 */
@Composable
fun AddTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(ProfileComponentSizes.card)
            .background(AppPalette.white, AppShapes.radiusXl)
            .border(
                if (pressed) ProfileCardStyle.borderPressedWidth else ProfileCardStyle.borderWidth,
                if (pressed) AppPalette.primaryBlue else ProfileCardStyle.border, AppShapes.radiusXl,
            )
            .pressable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(ProfileCardStyle.addCircle).background(ProfileCardStyle.addFill, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.add_icon),
                contentDescription = stringResource(R.string.profile_add_description),
                modifier = Modifier.size(ProfileComponentSizes.addIcon),
                tint = AppPalette.primaryBlue,
            )
        }
    }
}

/** add_button: 둥근 사각 안 add_icon */
@Composable
fun AddButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(ProfileComponentSizes.addButton)
            // [임시] 디자인 시스템 6절에 바탕색이 없다. 회색 바탕 surface
            .background(AppPalette.surface, AppShapes.radiusM)
            .pressOverlay(pressed, AppShapes.radiusM)
            .pressable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.add_icon),
            contentDescription = stringResource(R.string.profile_add_description),
            modifier = Modifier.size(ProfileComponentSizes.addIcon),
            tint = AppPalette.ink,
        )
    }
}

/** profile_edit_button: 테두리 버튼. 수정 모드 밖에서는 "프로필 관리", 수정 모드에서는 같은 자리·크기의 "완료"([text]) */
@Composable
fun ProfileEditButton(onClick: () -> Unit, modifier: Modifier = Modifier, text: String = stringResource(R.string.profile_edit_button)) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(ProfileComponentSizes.editButtonWidth, ProfileComponentSizes.editButtonHeight)
            .background(AppPalette.white, AppShapes.radiusXl)
            .pressOverlay(pressed, AppShapes.radiusXl)
            .border(BorderStroke(ProfileComponentSizes.editButtonBorder, AppPalette.ink), AppShapes.radiusXl)
            .pressable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // [임시] 디자인 시스템 6절에 글자 모양이 없다. small_regular
        Text(text, style = AppType.smallRegular, color = AppPalette.ink)
    }
}

/**
 * text_field: 입력 칸 + 지우기 아이콘. 도움말은 화면이 아래에 둔다.
 * [enabled]가 false면 눌러도 키보드가 열리지 않는다(주행 중, 디자인 시스템 2절).
 */
@Composable
fun ProfileTextField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val clearInteraction = remember { MutableInteractionSource() }
    val fieldInteraction = remember { MutableInteractionSource() }
    val focused by fieldInteraction.collectIsFocusedAsState()
    // 연회색 칸, 테두리 없음. 입력 중에는 파란 테두리. 글자는 가운데, 비었을 때 회색 안내 (11/15)
    Row(
        modifier
            .size(ProfileComponentSizes.textFieldWidth, ProfileComponentSizes.textFieldHeight)
            .background(AppPalette.surface, AppShapes.radiusL)
            .border(BorderStroke(ProfileComponentSizes.textFieldBorder, if (focused) AppPalette.primaryBlue else Color.Transparent), AppShapes.radiusL)
            .padding(horizontal = ProfileComponentSizes.textFieldPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 지우기 아이콘 자리만큼 왼쪽도 비워 글자가 칸 한가운데 오게
        Spacer(Modifier.width(ProfileComponentSizes.clearTouch))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            enabled = enabled,
            singleLine = true,
            textStyle = AppType.mediumBold.copy(color = AppPalette.ink, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(AppPalette.primaryBlue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            interactionSource = fieldInteraction,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.profile_name_hint), style = AppType.mediumRegular, color = AppPalette.textTertiary, textAlign = TextAlign.Center)
                    }
                    inner()
                }
            },
        )
        if (value.isEmpty()) Spacer(Modifier.width(ProfileComponentSizes.clearTouch)) else {
            Box(
                Modifier
                    .size(ProfileComponentSizes.clearTouch)
                    .pressable(clearInteraction, enabled = enabled, onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(ProfileComponentSizes.clearIcon).background(AppPalette.textTertiary, AppShapes.circle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close_icon),
                        contentDescription = stringResource(R.string.profile_clear_name_description),
                        modifier = Modifier.size(ProfileComponentSizes.clearMark),
                        tint = AppPalette.white,
                    )
                }
            }
        }
    }
}

/** car_name_card: 가운데 차 이름. 원판 위에 얹는 리퀴드 글래스 알약 [가안] */
@Composable
fun CarNameCard(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(ProfileComponentSizes.carNameCardWidth, ProfileComponentSizes.carNameCardHeight)
            .appShadow(AppShadows.soft, AppShapes.pill)
            .background(Brush.verticalGradient(listOf(GlassPill.fillTop, GlassPill.fillBottom)), AppShapes.pill)
            .border(GlassPill.borderWidth, GlassPill.border, AppShapes.pill),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = AppType.smallBold.copy(letterSpacing = GlassPill.letterSpacing),
            color = AppPalette.white,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Preview
@Composable
internal fun ProfileCardDefaultPreview() {
    AppTheme { ProfileCard(ProfileCardItem("1", "프로필명", "grandeur", 5), editing = false, onClick = {}) }
}

@Preview
@Composable
internal fun ProfileCardEditPreview() {
    AppTheme { ProfileCard(ProfileCardItem("1", "프로필명", "grandeur", 5), editing = true, onClick = {}) }
}

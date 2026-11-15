package ai.safetypoc.app.ui.profile

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import ai.safetypoc.app.ui.theme.AppType
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview

/**
 * 프로필 화면 (영역사양_프로필화면). 메뉴 없이 창 전체를 쓴다.
 * 안내(2), 프로필 줄(3), 프로필 관리(4)는 창 가로 중앙. 수정 모드에서는 4 자리에 완료(5).
 * 1칸 창이면 프로필 줄 대신 2열로 놓고, 창이 낮으면 세로로 넘긴다.
 */
@Composable
fun ProfileScreen(state: ProfileScreenState, actions: ProfileActions, modifier: Modifier = Modifier) {
    val narrow = LocalNarrowWindow.current
    Box(modifier.fillMaxSize().background(AppPalette.white)) {
        Column(
            Modifier.fillMaxWidth().then(
                // 1칸 창: 프로필 관리(4) 아래로 넘겨 가리지 않게 끝에 그만큼 띄운다
                if (narrow) Modifier.verticalScroll(rememberScrollState())
                    .padding(bottom = ProfileScreenSizes.editButtonBottom + ProfileComponentSizes.editButtonHeight)
                else Modifier,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.fillMaxWidth().height(
                    if (narrow) ProfileScreenSizes.narrowGuideBottom else ProfileScreenSizes.rowTop - ProfileScreenSizes.guideToRow,
                ),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Guide(state)
            }
            Spacer(Modifier.height(if (narrow) ProfileScreenSizes.narrowGuideToGrid else ProfileScreenSizes.guideToRow))
            if (narrow) ProfileGrid(state, actions) else ProfileRow(state, actions)
        }
        if (state.showEditButton) {
            ProfileEditButton(
                onClick = actions::startEdit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ProfileScreenSizes.editButtonBottom),
            )
        }
        if (state.editing) {
            // 수정 모드를 끝내는 버튼은 들어간 자리(4)에 같은 크기로 둔다
            ProfileEditButton(
                onClick = actions::endEdit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = ProfileScreenSizes.editButtonBottom),
                text = stringResource(R.string.profile_edit_done),
            )
        }
    }
}

/** 안내(2). 프로필이 없으면 빈 상태 안내(제목 한 줄 + 설명 한 줄) */
@Composable
private fun Guide(state: ProfileScreenState) {
    when {
        state.profiles.isEmpty() -> {
            val lines = stringResource(R.string.profile_empty).lines()
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(lines.first(), style = AppType.largeBold, color = AppPalette.ink, textAlign = TextAlign.Center)
                if (lines.size > 1) {
                    Spacer(Modifier.height(ProfileScreenSizes.emptyLineGap))
                    // [임시] 설명 줄의 글자 모양은 빈 상태 패턴(디자인 시스템 7절)의 설명: small_regular, text_secondary
                    Text(
                        lines.drop(1).joinToString("\n"),
                        style = AppType.smallRegular,
                        color = AppPalette.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        else -> Text(
            stringResource(if (state.editing) R.string.profile_select_edit else R.string.profile_select),
            style = AppType.largeBold,
            color = AppPalette.ink,
            textAlign = TextAlign.Center,
        )
    }
}

/** 프로필 줄(3): profile_card들 + add_button 칸. 묶음 전체가 가로 중앙 */
@Composable
private fun ProfileRow(state: ProfileScreenState, actions: ProfileActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(ProfileScreenSizes.rowGap)) {
        for (tile in profileTiles(state, actions)) tile()
    }
}

/** 1칸 창의 프로필 칸: 프로필 줄(3)과 같은 칸을 [ProfileScreenSizes.NARROW_COLUMNS] 열로. 남는 칸은 마지막 줄 가로 중앙 */
@Composable
private fun ProfileGrid(state: ProfileScreenState, actions: ProfileActions) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ProfileScreenSizes.narrowRowGap),
    ) {
        for (line in profileTiles(state, actions).chunked(ProfileScreenSizes.NARROW_COLUMNS)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ProfileScreenSizes.narrowColumnGap)) {
                for (tile in line) tile()
            }
        }
    }
}

/** 프로필 칸 순서: profile_card(+ 이름)들, 그다음 add 칸(카드와 같은 모양·크기) */
private fun profileTiles(state: ProfileScreenState, actions: ProfileActions): List<@Composable () -> Unit> =
    state.profiles.map { item ->
        @Composable {
            Column(Modifier.width(ProfileComponentSizes.card), horizontalAlignment = Alignment.CenterHorizontally) {
                ProfileCard(item, state.editing, onClick = { actions.tapProfile(item.id) })
                Spacer(Modifier.height(ProfileScreenSizes.cardToName))
                Text(
                    item.name,
                    style = AppType.mediumBold,
                    color = AppPalette.ink,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } + if (state.canAdd) listOf(@Composable { AddTile(onClick = actions::tapAdd) }) else emptyList()

private val previewProfiles = listOf(
    ProfileCardItem("1", "프로필명", "ioniq3", 2),
    ProfileCardItem("2", "프로필명", "grandeur", 5),
    ProfileCardItem("3", "프로필명", "ioniq5", 1),
)

private object NoProfileActions : ProfileActions {
    override fun tapProfile(profileId: String) = Unit
    override fun tapAdd() = Unit
    override fun startEdit() = Unit
    override fun endEdit() = Unit
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun ProfileScreenEmptyPreview() {
    AppTheme { ProfileScreen(ProfileScreenState(), NoProfileActions) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun ProfileScreenOnePreview() {
    AppTheme { ProfileScreen(ProfileScreenState(previewProfiles.take(1)), NoProfileActions) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun ProfileScreenTwoPreview() {
    AppTheme { ProfileScreen(ProfileScreenState(previewProfiles.take(2)), NoProfileActions) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun ProfileScreenThreePreview() {
    AppTheme { ProfileScreen(ProfileScreenState(previewProfiles), NoProfileActions) }
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun ProfileScreenEditPreview() {
    AppTheme { ProfileScreen(ProfileScreenState(previewProfiles, editing = true), NoProfileActions) }
}

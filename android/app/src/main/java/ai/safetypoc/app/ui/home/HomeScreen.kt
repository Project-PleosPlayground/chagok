package ai.safetypoc.app.ui.home

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.DriveStartButton
import ai.safetypoc.app.ui.mission.MissionCard
import ai.safetypoc.app.ui.nav.TabFrame
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import core.mission.MissionKind
import core.mission.MissionStatus

/**
 * 홈 화면 (영역사양_홈화면). 뼈대는 [StageScreenLayout], 아래 가운데는 drive_start_button.
 * mission_panel 이 비었으면 "미션 고르기"로 미션 탭에 간다.
 */
@Composable
fun HomeScreen(
    state: HomeState, panel: MissionPanelState, actions: HomeActions, modifier: Modifier = Modifier,
    /** 캐릭터를 누르면 변신을 다시 본다. 볼 변신이 없으면(1단계) null 이고, 누르면 캐릭터가 흔들린다 */
    onTransform: (() -> Unit)? = null,
    /** 변신을 볼 수 있게 된 뒤 아직 한 번도 누르지 않았으면 "눌러 봐!" 말풍선 */
    showTransformHint: Boolean = false,
) {
    StageScreenLayout(
        state = state,
        missionPanel = { MissionPanelSlot(panel, onPickMission = actions::openMissions, modifier = it) },
        driveButton = { DriveStartButton(stringResource(R.string.drive_start), actions::startDrive, it) },
        modifier = modifier,
        onCharacterTap = { onTransform?.invoke(); onTransform != null },
        onSettings = actions::openSettings,
        onCoinLongPress = actions::toggleDebugTab,
        characterOverlay = if (showTransformHint && onTransform != null) { { TransformHint(state.stage) } } else null,
    )
}

/** Preview 값: 2칸 창(디자인 시스템 3절) */
internal const val PREVIEW_WINDOW_WIDTH = 1700
internal const val PREVIEW_WINDOW_HEIGHT = 1184

internal val previewHomeState = HomeState(characterName = "붕붕", stage = 2, xp = 150, stageProgress = 0.5f, coins = 33456)

internal fun previewCard(uid: String, title: String, status: MissionStatus, progress: String) = MissionCard(
    uid = uid, title = title, description = "", kind = MissionKind.PERSONAL, status = status, tier = 1,
    progress = progress, rewardCoins = 100, rewardXp = 50, remainingMs = 0L,
    canChallenge = false, canCancel = false, canClear = false,
)

internal val previewPanel = MissionPanelState(
    listOf(
        previewCard("1", "급제동 없이 주행하기", MissionStatus.IN_PROGRESS, "3/10"),
        previewCard("2", "깜빡이 켜고 차선 바꾸기", MissionStatus.SUCCEEDED, "5/5"),
        previewCard("3", "과속 없이 30분 주행하기", MissionStatus.FAILED, "0/1"),
    ),
)

/** Preview 의 탭 이름 (디자인 시스템 6절 tab_bar) */
internal val previewTabLabels = listOf("홈", "미션", "아이템", "통계")

/** 탭 뼈대와 흰 창 바탕을 씌운 Preview 틀 */
@Composable
internal fun StageScreenPreviewFrame(driving: Boolean, content: @Composable () -> Unit) {
    AppTheme(driving = driving) {
        Box(Modifier.fillMaxSize().background(AppPalette.white)) {
            TabFrame(labels = previewTabLabels, selectedIndex = 0, driving = driving, onSelect = {}) { content() }
        }
    }
}

private val noActions = object : HomeActions {
    override fun openMissions() = Unit
    override fun startDrive() = Unit
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun HomeMissionsPreview() = StageScreenPreviewFrame(driving = false) {
    HomeScreen(previewHomeState, previewPanel, noActions)
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun HomeNoMissionPreview() = StageScreenPreviewFrame(driving = false) {
    HomeScreen(previewHomeState, MissionPanelState(), noActions)
}

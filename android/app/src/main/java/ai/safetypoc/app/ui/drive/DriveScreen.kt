package ai.safetypoc.app.ui.drive

import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.DriveEndButton
import ai.safetypoc.app.ui.home.HomeState
import ai.safetypoc.app.ui.home.MissionPanelSlot
import ai.safetypoc.app.ui.home.MissionPanelState
import ai.safetypoc.app.ui.home.PREVIEW_WINDOW_HEIGHT
import ai.safetypoc.app.ui.home.PREVIEW_WINDOW_WIDTH
import ai.safetypoc.app.ui.home.ReactionOverlay
import ai.safetypoc.app.ui.home.StageScreenLayout
import ai.safetypoc.app.ui.home.StageScreenPreviewFrame
import ai.safetypoc.app.ui.home.previewHomeState
import ai.safetypoc.app.ui.home.previewPanel
import androidx.compose.runtime.Composable
import engine.FeedbackCue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview

/** 주행 화면에서 하는 동작 */
fun interface DriveActions {
    /** drive_end_button */
    fun endDrive()
}

/**
 * 주행 화면 (영역사양_주행화면). 홈과 뼈대가 같고, 아래 가운데는 drive_end_button 이다.
 * 애니메이션·스크롤 없이 값이 바뀌면 바로 다시 그린다(디자인 시스템 2절 "주행 중 움직임").
 * mission_panel 에서 다른 화면으로 가는 버튼은 두지 않는다(2절 "주행 중 조작").
 * 캐릭터는 홈과 같은 그림·자리다(11/14). 누를 수 없다. 주행 자세 그림(drive_lv*)은 급정거 같은 사건 반응용으로 남겨 둔다.
 * drive_end_button 은 홈의 drive_start_button 자리(아래 가운데)에 같은 크기로 놓는다(11/14).
 * [canEnd] 일 때만 보이고 누를 수 있다: 정차 중이고 주행 규제가 풀렸을 때(P 단). 그 밖에는 그리지 않는다. 자리는 그대로다.
 */
@Composable
fun DriveScreen(
    state: HomeState,
    panel: MissionPanelState,
    canEnd: Boolean,
    actions: DriveActions,
    modifier: Modifier = Modifier,
    /** 마지막 사건 반응: 캐릭터 몸짓과 머리 위 말풍선 (11/14) */
    reaction: FeedbackCue? = null,
) {
    StageScreenLayout(
        state = state,
        missionPanel = { MissionPanelSlot(panel, onPickMission = null, modifier = it) },
        driveButton = { if (canEnd) DriveEndButton(stringResource(R.string.drive_end), actions::endDrive, it) },
        modifier = modifier,
        onCharacterTap = null,
        reaction = reaction,
        eyeGlow = true,
        characterOverlay = { ReactionOverlay(reaction, state.stage) },
    )
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun DrivingMovingPreview() = StageScreenPreviewFrame(driving = true) {
    DriveScreen(previewHomeState, previewPanel, canEnd = false, actions = DriveActions {})
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun DrivingStoppedPreview() = StageScreenPreviewFrame(driving = true) {
    DriveScreen(previewHomeState, previewPanel, canEnd = true, actions = DriveActions {})
}

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun DrivingNoMissionPreview() = StageScreenPreviewFrame(driving = true) {
    DriveScreen(previewHomeState, MissionPanelState(), canEnd = false, actions = DriveActions {})
}

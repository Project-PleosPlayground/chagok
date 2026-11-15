package ai.safetypoc.app.ui.home

import ai.safetypoc.app.ui.stage.rememberCharacterMotion
import engine.FeedbackCue
import ai.safetypoc.app.R
import ai.safetypoc.app.ui.component.CoinBar
import ai.safetypoc.app.ui.component.ExpBar
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.stage.StageCanvas
import ai.safetypoc.app.ui.stage.StageLayout
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.LocalNarrowWindow
import ai.safetypoc.app.ui.nav.TabFrameSizes
import ai.safetypoc.app.ui.nav.TabHeaderActions
import ai.safetypoc.app.ui.theme.CoinBarSizes
import ai.safetypoc.app.ui.theme.DriveButtonSizes
import ai.safetypoc.app.ui.theme.ExpBarSizes
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 홈·주행 화면 배치 값 (영역사양_홈화면, 영역사양_주행화면). 두 화면은 뼈대가 같다 */
object StageScreenSizes {
    /** 위쪽 줄 높이: coin_bar 높이. exp_bar 는 이 줄의 세로 가운데에 놓는다(3 "4와 세로 중앙") */
    val topRowHeight = CoinBarSizes.height
    /** exp_bar 높이: 캡슐 높이 */
    val expBarHeight = ExpBarSizes.height
    /** 창 위 끝에서 exp_bar 아래 끝까지 */
    val expBarBottom = AppDimens.screenMargin + (topRowHeight + expBarHeight) / 2
    /** 창 아래 끝에서 주행 버튼 위 끝까지 */
    val driveButtonTop = AppDimens.screenMargin + DriveButtonSizes.height

    /** mission_panel 폭 [임시] 디자인 시스템 6절 "미션 부품"은 496. 캐릭터가 주인공이 되게 줄였다 (11/14 홈 검토) */
    val missionPanelWidth = 432.dp
    /** 설정 아이콘과 coin_bar 사이 */
    val settingsGap = 16.dp

    /** 1칸 창: 창 위 끝에서 exp_bar 아래 끝까지. exp_bar 줄은 헤더 아래 내용 시작에 놓는다 */
    val narrowExpBarBottom = TabFrameSizes.narrowContentTop + (topRowHeight + expBarHeight) / 2
    /** 1칸 창: 스테이지를 창 세로 가운데에서 내리는 양. exp_bar 아래 ~ 주행 버튼 위의 가운데에 오게 한다 */
    val narrowStageDrop = (narrowExpBarBottom - driveButtonTop) / 2
    /** 1칸 창: 창 위 끝에서 mission_panel 위 끝까지. exp_bar 아래 누르는 영역 간격 */
    val narrowMissionTop = narrowExpBarBottom + AppDimens.touchGap

    /** 스테이지 높이 = 빈 곳 높이: exp_bar 아래 a ~ 주행 버튼 위 a (스펙_스테이지 "화면별 스테이지 크기") */
    fun stageHeight(windowHeight: Dp): Dp =
        (windowHeight - expBarBottom - driveButtonTop - AppDimens.screenMargin * 2).coerceAtLeast(0.dp)
}

/**
 * 홈·주행 화면의 뼈대. 스테이지(L1)를 창 정중앙에 먼저 놓고, UI(L2)를 그 위에 깐다. UI 가 스테이지를 가려도 된다.
 * - 3 exp_bar ↑a 가로 중앙. 다음 단계까지의 진행(stageProgress)만큼 채운다
 * - 4 coin_bar ↑→a. 1칸 창이면 coin_bar·설정은 헤더 오른쪽([TabHeaderActions]), exp_bar 는 헤더 아래
 * - 5 [missionPanel]: →a, coin_bar 아래. 컨테이너는 ↓a 까지이고 넘치면 숨긴다. 받은 Modifier 는 폭과 위치를 정한다.
 *   1칸 창이면 exp_bar 아래 오른쪽에 붙이고 폭은 [missionPanel] 이 정한다(접힌 버튼 ↔ 펼친 패널)
 * - 1칸 창이면 스테이지를 [StageScreenSizes.narrowStageDrop] 만큼 내린다
 * - 6 [driveButton]: ↓a 가로 중앙
 * tab_bar(2)는 [ai.safetypoc.app.ui.nav.TabFrame] 이 그린다.
 */
@Composable
fun StageScreenLayout(
    state: HomeState,
    missionPanel: @Composable (Modifier) -> Unit,
    driveButton: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    /** 캐릭터를 눌렀을 때. false 를 돌려주면 캐릭터가 흔들린다. null 이면 누를 수 없다(주행 화면) */
    onCharacterTap: (() -> Boolean)? = null,
    /** 주행 화면: 마지막 사건 반응(몸짓) */
    reaction: FeedbackCue? = null,
    /** 주행 화면: 캐릭터 눈 빛 */
    eyeGlow: Boolean = false,
    /** 캐릭터 위에 얹는 것(변신 말풍선) */
    characterOverlay: (@Composable BoxScope.() -> Unit)? = null,
    /** coin_bar 길게 누르기. null 이면 반응 없음 */
    onCoinLongPress: (() -> Unit)? = null,
    /** coin_bar 오른쪽 설정 아이콘. null 이면 없다(주행 화면) */
    onSettings: (() -> Unit)? = null,
) {
    val motion = rememberCharacterMotion()
    val narrow = LocalNarrowWindow.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        // StageCanvas 는 받은 영역에서 여백 a 를 위아래로 뺀 높이를 스테이지 높이로 쓴다
        StageCanvas(
            state.placed,
            Modifier.align(Alignment.Center)
                .then(if (narrow) Modifier.offset(y = StageScreenSizes.narrowStageDrop) else Modifier)
                .fillMaxWidth()
                .height(StageScreenSizes.stageHeight(maxHeight) + AppDimens.screenMargin * 2),
            characterLabel = stringResource(R.string.stage_character_level, state.stage),
            character = state.characterAssetKey?.let { key ->
                { HomeCharacter(key, state.stage, onCharacterTap, reaction = reaction, motion = motion, eyeGlow = eyeGlow) }
            },
            characterOverlay = characterOverlay,
            motion = motion,
        )
        val coinAndSettings = @Composable {
            CoinBar(state.coins, onCoinLongPress?.let { f -> Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { f() }) } } ?: Modifier)
            onSettings?.let { SettingsIconButton(it) }
        }
        Box(Modifier.fillMaxSize().padding(AppDimens.screenMargin)) {
            if (narrow) {
                // 1칸 창: coin_bar·설정은 헤더 오른쪽, exp_bar 는 헤더 아래 가로 가운데
                TabHeaderActions { coinAndSettings() }
                Box(
                    Modifier.fillMaxWidth().padding(top = TabFrameSizes.narrowContentTop - AppDimens.screenMargin)
                        .height(StageScreenSizes.topRowHeight),
                ) {
                    ExpBar(state.stage, state.xpInStage, state.xpSpan, state.stageProgress, Modifier.align(Alignment.Center))
                }
            } else {
                Box(Modifier.fillMaxWidth().height(StageScreenSizes.topRowHeight)) {
                    ExpBar(state.stage, state.xpInStage, state.xpSpan, state.stageProgress, Modifier.align(Alignment.Center))
                    Row(
                        Modifier.align(Alignment.CenterEnd),
                        horizontalArrangement = Arrangement.spacedBy(StageScreenSizes.settingsGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { coinAndSettings() }
                }
            }
            if (narrow) {
                // 1칸 창: exp_bar 아래 오른쪽. 접힌 버튼에서 왼쪽·아래로 펼쳐진다
                Box(
                    Modifier.align(Alignment.TopEnd)
                        .padding(top = StageScreenSizes.narrowMissionTop - AppDimens.screenMargin)
                        .width(StageScreenSizes.missionPanelWidth),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    missionPanel(Modifier)
                }
            } else {
                // [임시] 창 세로 가운데. 위에 붙이면 캐릭터보다 무거워 보이고, 아래쪽이 운전석에서 손이 닿기 쉽다 (11/14 홈 검토)
                Box(
                    Modifier.align(Alignment.CenterEnd)
                        .width(StageScreenSizes.missionPanelWidth)
                        .clipToBounds(),
                ) {
                    missionPanel(Modifier.fillMaxWidth())
                }
            }
            driveButton(Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** 설정 아이콘 버튼: coin_bar 와 같은 높이의 원, surface 바탕에 ink 톱니바퀴 [가안] */
@Composable
private fun SettingsIconButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(CoinBarSizes.height).background(AppPalette.surface, CircleShape).pressable(interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.settings_icon), contentDescription = stringResource(R.string.tab_settings), Modifier.size(32.dp), tint = AppPalette.ink)
    }
}

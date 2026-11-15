package ai.safetypoc.app.ui.nav

import androidx.compose.foundation.layout.padding

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.LocalNarrowWindow

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import ai.safetypoc.app.ui.home.NameCharacterDialog
import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.DriveUiState
import ai.safetypoc.app.R
import ai.safetypoc.app.ui.debug.DriveDebugScreen
import ai.safetypoc.app.ui.decorate.ShopActions
import ai.safetypoc.app.ui.decorate.ShopScreen
import ai.safetypoc.app.ui.decorate.ShopState
import ai.safetypoc.app.ui.drive.DriveActions
import ai.safetypoc.app.ui.drive.DriveScreen
import ai.safetypoc.app.ui.home.HomeActions
import ai.safetypoc.app.ui.home.HomeScreen
import ai.safetypoc.app.ui.home.HomeState
import ai.safetypoc.app.ui.home.toPanel
import ai.safetypoc.app.ui.mission.MissionDialogLayer
import ai.safetypoc.app.ui.mission.MissionScreen
import ai.safetypoc.app.ui.mission.MissionTabActions
import ai.safetypoc.app.ui.mission.MissionTabState
import ai.safetypoc.app.ui.onboarding.ONBOARDING_MS
import ai.safetypoc.app.ui.onboarding.OnboardingContent
import ai.safetypoc.app.ui.onboarding.OnboardingScreen
import ai.safetypoc.app.ui.onboarding.StartupSound
import ai.safetypoc.app.ui.profile.AddProfileActions
import ai.safetypoc.app.ui.profile.AddProfileScreen
import ai.safetypoc.app.ui.profile.AddProfileState
import ai.safetypoc.app.ui.profile.ProfileActions
import ai.safetypoc.app.ui.profile.ProfileScreen
import ai.safetypoc.app.ui.profile.ProfileScreenState
import ai.safetypoc.app.ui.report.DriveEndActions
import ai.safetypoc.app.ui.report.DriveEndScreen
import ai.safetypoc.app.ui.report.DriveEndState
import ai.safetypoc.app.ui.report.ReportScreen
import ai.safetypoc.app.ui.report.ReportState
import ai.safetypoc.app.ui.settings.SettingsActions
import ai.safetypoc.app.ui.settings.SettingsScreen
import ai.safetypoc.app.ui.settings.SettingsState
import ai.safetypoc.app.ui.stats.StatsActions
import ai.safetypoc.app.ui.stats.StatsScreen
import ai.safetypoc.app.ui.stats.StatsState
import ai.safetypoc.app.ui.transform.TransformActions
import ai.safetypoc.app.ui.transform.TransformOverlay
import ai.safetypoc.app.ui.transform.TransformState
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

/** 주행 결과 화면은 홈 탭이 선택된 채로 그린다 (영역사양_주행결과화면 2) */
private fun tabOf(d: Destination) = if (d == Destination.DRIVE_END) Destination.HOME else d

/** tab_bar 의 버튼 하나: 가는 화면과 이름 */
private data class Tab(val destination: Destination, @StringRes val label: Int)

/** tab_bar 순서 (디자인 시스템 6절 tab_bar). 디버그 탭은 [AppRoot] 의 showDebugTab 일 때만 맨 아래에 둔다 */
private val TABS = listOf(
    Tab(Destination.HOME, R.string.tab_home),
    Tab(Destination.MISSION, R.string.tab_mission),
    Tab(Destination.SHOP, R.string.tab_shop),
    Tab(Destination.REPORT, R.string.tab_report),
)
private val DEBUG_TAB = Tab(Destination.DEBUG, R.string.tab_debug)

/**
 * 앱 화면 전체.
 * - 온보딩, 프로필 선택·추가, 아이템 상점은 메뉴 없이 창 전체를 쓴다(디자인 시스템 3절 "화면 뼈대").
 * - 그 밖의 화면은 왼쪽 위 tab_bar 와 함께 그린다. 홈은 [driving] 이면 주행 화면으로 바뀐다.
 *   주행 중에는 [Navigator] 가 홈에 고정되어 다른 화면으로 가지 않는다.
 */
@Composable
fun AppRoot(
    current: Destination,
    navigator: Navigator,
    home: HomeState,
    homeActions: HomeActions,
    driveActions: DriveActions,
    mission: MissionTabState,
    missionActions: MissionTabActions,
    shop: ShopState,
    shopActions: ShopActions,
    profile: ProfileScreenState,
    profileActions: ProfileActions,
    addProfile: AddProfileState,
    addProfileActions: AddProfileActions,
    driveEnd: DriveEndState?,
    driveEndActions: DriveEndActions,
    report: ReportState,
    stats: StatsState,
    statsActions: StatsActions,
    drive: DriveUiState,
    driving: Boolean,
    /** 시스템 주행 규제가 걸려 있는지(P 단이 아님). 걸려 있으면 주행 종료 버튼을 그리지 않는다 */
    driveRestricted: Boolean,
    onDriveStart: () -> Unit,
    onDriveStop: () -> Unit,
    onGrantPoints: () -> Unit,
    onGrantXp: () -> Unit,
    onReplayDemoDrive: (long: Boolean, namsan: Boolean) -> Unit,
    onSeedHistory: () -> Unit,
    onTrimDrives: () -> Unit,
    onLowBattery: () -> Unit,
    onResetStage: () -> Unit,
    settings: SettingsState,
    settingsActions: SettingsActions,
    transform: TransformState,
    transformActions: TransformActions,
    /** 홈 캐릭터를 눌렀을 때 변신을 다시 본다. 볼 영상이 없으면 null */
    onReplayTransform: (() -> Unit)?,
    /** 캐릭터를 눌러 변신을 본 적이 있는지. 없으면 "눌러 봐!" 말풍선 */
    transformHintSeen: Boolean,
    onResetProfiles: () -> Unit,
    onResetAllData: () -> Unit,
    showDebugTab: Boolean,
    startupSound: StartupSound,
    onHide: () -> Unit,
) {
    BackHandler { navigator.onBackKey(onHide) }
    when (current) {
        Destination.ONBOARDING -> {
            // 주행 중에는 온보딩 그림만 두고 프로텍터가 덮는다. 시동음과 1.5초 진행은 주행이 끝나면 시작한다(#76)
            if (driving) OnboardingContent()
            else {
                val timing = remember { navigator.enterOnboarding(SystemClock.elapsedRealtime(), ONBOARDING_MS) }
                OnboardingScreen(startupSound, timing, onDone = navigator::finishOnboarding)
            }
            return
        }
        Destination.SHOP -> {
            ShopScreen(shop, shopActions, Modifier.fillMaxSize())
            return
        }
        Destination.PROFILE -> {
            ProfileScreen(profile, profileActions, Modifier.fillMaxSize())
            return
        }
        Destination.ADD_PROFILE, Destination.EDIT_PROFILE -> {
            AddProfileScreen(addProfile, addProfileActions, driving, Modifier.fillMaxSize())
            return
        }
        else -> Unit
    }
    var renaming by remember { mutableStateOf(false) }
    val tabs = if (showDebugTab) TABS + DEBUG_TAB else TABS
    TabFrame(
        labels = tabs.map { stringResource(it.label) },
        selectedIndex = tabs.indexOfFirst { it.destination == tabOf(current) },
        driving = driving,
        onSelect = { navigator.go(tabs[it].destination) },
        // 미션·통계는 탭을 접어 내용을 넓게 쓴다 (11/14)
        compact = current == Destination.MISSION || current == Destination.REPORT || current == Destination.DRIVE_END,
    ) {
        val area = Modifier.tabContentArea(compact = current == Destination.REPORT)
        when (current) {
            Destination.HOME ->
                if (driving) DriveScreen(home, mission.toPanel(), canEnd = drive.stopped && !driveRestricted, actions = driveActions, reaction = drive.reaction)
                else HomeScreen(home, mission.toPanel(), homeActions, onTransform = onReplayTransform, showTransformHint = !transformHintSeen)
            Destination.MISSION -> MissionScreen(mission, missionActions, Modifier.fillMaxSize())
            // 통계도 주행 결과처럼 접힌 탭 버튼 아래 여백까지 쓴다 (11/15)
            Destination.REPORT -> StatsScreen(stats, statsActions, Modifier.fillMaxSize().padding(AppDimens.screenMargin))
            Destination.DRIVE_END -> DriveEndScreen(
                driveEnd, driveEndActions, Modifier.fillMaxSize(), characterAssetKey = home.characterAssetKey, stage = home.stage,
            )
            Destination.SETTINGS -> SettingsScreen(
                settings, settingsActions, area, characterAssetKey = home.characterAssetKey, characterName = home.characterName, stage = home.stage,
                onRenameCharacter = { renaming = true },
            )
            Destination.DEBUG -> DriveDebugScreen(
                drive, onDriveStart, onDriveStop, onGrantPoints, onGrantXp, onReplayDemoDrive, onSeedHistory, onTrimDrives, onLowBattery, onResetStage, onResetProfiles, onResetAllData, area,
            )
            Destination.ONBOARDING, Destination.SHOP, Destination.PROFILE, Destination.ADD_PROFILE, Destination.EDIT_PROFILE -> Unit
        }
    }
    // 미션 탭의 확인 팝업은 tab_bar 까지 덮는다
    if (current == Destination.MISSION) MissionDialogLayer(mission, missionActions)
    // 캐릭터 이름 짓기: 홈에 처음 들어왔는데 별명이 없을 때, 또는 설정에서 이름을 누를 때. 주행 중에는 띄우지 않는다(글자 입력)
    if (!driving && ((current == Destination.HOME && home.needsName) || renaming)) {
        NameCharacterDialog(
            assetKey = home.characterAssetKey, stage = home.stage,
            initial = if (home.needsName) AppConfig.DEFAULT_CHARACTER_ALIAS else home.characterName.orEmpty(),
            fallback = AppConfig.DEFAULT_CHARACTER_ALIAS,
            onConfirm = { homeActions.nameCharacter(it); renaming = false },
            onDismiss = if (home.needsName) null else { { renaming = false } },
        )
    }
    // 변신 영상은 정차 중에만 띄운다. 주행 중에 생긴 것은 정차하면 나온다
    if (!driving) TransformOverlay(transform, home.characterName, home.characterAssetKey, transformActions, sound = settings.voiceOn)
}

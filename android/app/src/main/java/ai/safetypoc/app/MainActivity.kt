package ai.safetypoc.app

import ai.safetypoc.app.ui.drive.DriveActions
import ai.safetypoc.app.ui.drive.DriveProtector
import ai.safetypoc.app.ui.drive.protectorShown
import ai.safetypoc.app.ui.home.HomeActions
import ai.safetypoc.app.ui.nav.AppRoot
import ai.safetypoc.app.ui.nav.Destination
import ai.safetypoc.app.ui.report.DriveEndActions
import ai.safetypoc.app.ui.report.toDriveEnd
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 화면 하나짜리 Activity. 화면 이동은 [ai.safetypoc.app.ui.nav.Navigator] 가 한다 */
class MainActivity : ComponentActivity() {

    private val app get() = application as SafetyApp

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { app.startSources() }

    private val homeActions = object : HomeActions {
        override fun openMissions() = app.navigator.go(Destination.MISSION)
        override fun openSettings() = app.navigator.go(Destination.SETTINGS)
        override fun toggleDebugTab() = app.toggleDebugTab()
        override fun nameCharacter(name: String) = app.home.setAlias(name)
        override fun startDrive() {
            startWithPermissions()
            app.startDrive()
        }
    }

    private val driveActions = DriveActions { app.endDrive() }

    private val driveEndActions = object : DriveEndActions {
        override fun answerAiConsent(on: Boolean) = app.answerAiConsent(on)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val driving by app.driveMode.driving.collectAsStateWithLifecycle()
            val driveRestricted by app.driveRestricted.collectAsStateWithLifecycle()
            AppTheme(driving = driving) {
                Surface(Modifier.fillMaxSize(), color = AppPalette.white) {
                    val current by app.navigator.current.collectAsStateWithLifecycle()
                    val home by app.home.state.collectAsStateWithLifecycle()
                    val mission by app.missionTab.state.collectAsStateWithLifecycle()
                    val shop by app.shop.state.collectAsStateWithLifecycle()
                    val profile by app.profile.state.collectAsStateWithLifecycle()
                    val addProfile by app.addProfile.state.collectAsStateWithLifecycle()
                    val drive by app.host.state.collectAsStateWithLifecycle()
                    val driveEnd by app.drives.last.collectAsStateWithLifecycle()
                    val report by app.report.state.collectAsStateWithLifecycle()
                    val stats by app.stats.state.collectAsStateWithLifecycle()
                    val settings by app.settings.state.collectAsStateWithLifecycle()
                    val transform by app.transform.state.collectAsStateWithLifecycle()
                    val debugTabOn by app.debugTabOn.collectAsStateWithLifecycle()
                    val transformHintSeen by app.transform.hintSeen.collectAsStateWithLifecycle()
                    // 진입 전 화면(온보딩, 프로필 선택·추가·수정)에서 주행이 시작되면 화면 위에 프로텍터를 덮어 조작을 막는다. 진입 후에는 홈 고정이 맡는다. 주행 판정은 홈 고정과 같은 [driving] 이다
                    Box(Modifier.fillMaxSize()) {
                        AppRoot(
                            current = current, navigator = app.navigator, home = home, homeActions = homeActions, driveActions = driveActions,
                            mission = mission, missionActions = app.missionTab,
                            shop = shop, shopActions = app.shop,
                            profile = profile, profileActions = app.profile,
                            addProfile = addProfile, addProfileActions = app.addProfile,
                            driveEnd = driveEnd?.toDriveEnd(app.graph.growth)?.copy(askAiConsent = settings.askAiOnDriveEnd), driveEndActions = driveEndActions,
                            report = report, stats = stats, statsActions = app.stats,
                            drive = drive, driving = driving, driveRestricted = driveRestricted, onDriveStart = ::startWithPermissions, onDriveStop = app.host::stop, onGrantPoints = app::grantDebugPoints, onGrantXp = app::grantDebugXp, onReplayDemoDrive = app::replayDemoDrive, onSeedHistory = app::seedDemoHistory, onTrimDrives = app::trimTestDrives, onLowBattery = app::debugLowBattery, onResetStage = app::resetDemoStage,
                            settings = settings, settingsActions = app.settings,
                            transform = transform, transformActions = app.transform, transformHintSeen = transformHintSeen,
                            onReplayTransform = app::replayTransform.takeIf { app.transform.clipsUpTo(home.characterAssetKey, home.stage).isNotEmpty() },
                            onResetProfiles = app::resetProfiles, onResetAllData = app::resetAllData,
                            showDebugTab = debugTabOn,
                            startupSound = app.startupSound, onHide = { moveTaskToBack(true) },
                        )
                        if (protectorShown(driving, current)) DriveProtector()
                    }
                }
            }
        }
        startWithPermissions()
    }

    override fun onDestroy() {
        if (isFinishing) app.host.stop()
        super.onDestroy()
    }

    private fun startWithPermissions() {
        val missing = permissionsToRequest(
            AppConfig.runtimePermissions.toList(),
            granted = { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED },
            runtime = ::isRuntimePermission,
        )
        if (missing.isEmpty()) app.startSources() else permissionRequest.launch(missing.toTypedArray())
    }

    /** 이 기기에 정의된 dangerous 권한인지. 정의되지 않은 권한은 요청해도 창만 잠깐 열렸다 닫힌다 */
    private fun isRuntimePermission(name: String): Boolean = try {
        packageManager.getPermissionInfo(name, 0).protection == PermissionInfo.PROTECTION_DANGEROUS
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * 실행 중에 요청할 권한: 사용자가 허락할 수 있는(runtime) 권한 중 아직 받지 못한 것.
 * 기기에 없거나 설치 때 받는 권한은 빼야 권한 창이 실행할 때마다 잠깐 열렸다 닫히지 않는다.
 */
internal fun permissionsToRequest(all: List<String>, granted: (String) -> Boolean, runtime: (String) -> Boolean): List<String> =
    all.filter { runtime(it) && !granted(it) }

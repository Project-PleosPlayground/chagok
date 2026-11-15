package ai.safetypoc.app

import ai.safetypoc.app.ai.AiCharacters
import ai.safetypoc.app.ai.AiConsent
import ai.safetypoc.app.ai.HttpAiClient
import ai.safetypoc.app.content.ContentFiles
import ai.safetypoc.app.content.HttpFetcher
import ai.safetypoc.app.content.RemoteContentUpdater
import ai.safetypoc.app.data.store.FileDocumentStore
import ai.safetypoc.app.drive.DemoDriveReplay
import ai.safetypoc.app.drive.DemoHistory
import ai.safetypoc.app.location.DemoRoute
import core.character.Profile
import core.mission.MissionKind
import core.session.SessionRecord
import ai.safetypoc.app.location.RoadNames
import ai.safetypoc.app.location.EventLocations
import ai.safetypoc.app.location.GeoFix
import ai.safetypoc.app.location.GpsLocation
import ai.safetypoc.app.drive.DriveMode
import ai.safetypoc.app.gleo.GleoActionReceiver
import ai.safetypoc.app.gleo.GleoAnswers
import ai.safetypoc.app.drive.DriveRestriction
import ai.safetypoc.app.drive.DriveRecorder
import ai.safetypoc.app.source.AaosCarPropertySource
import ai.safetypoc.app.source.PleosVehicleSdkSource
import ai.safetypoc.app.source.VehicleStatusMonitor
import ai.safetypoc.app.ui.decorate.ShopModel
import ai.safetypoc.app.ui.home.HomeModel
import ai.safetypoc.app.ui.mission.MissionTabModel
import ai.safetypoc.app.ui.nav.Destination
import ai.safetypoc.app.ui.nav.Navigator
import ai.safetypoc.app.ui.onboarding.StartupSound
import ai.safetypoc.app.ui.profile.AddProfileModel
import ai.safetypoc.app.ui.profile.ProfileModel
import ai.safetypoc.app.ui.profile.ProfileNavigation
import ai.safetypoc.app.ui.report.ReportModel
import ai.safetypoc.app.settings.AppSettings
import ai.safetypoc.app.settings.PrefsFlags
import ai.safetypoc.app.ui.settings.SettingsModel
import ai.safetypoc.app.ui.stats.StatsModel
import ai.safetypoc.app.ui.transform.TransformModel
import ai.safetypoc.app.voice.SwitchableVoicePlayer
import ai.safetypoc.app.voice.SfxVoicePlayer
import ai.safetypoc.app.ui.art.CharacterArtStore
import ai.safetypoc.app.ui.art.ItemArtStore
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import core.content.BundledContent
import core.content.ContentState
import core.content.ContentUpdate
import core.flow.Rewards
import core.flow.PlaceGift
import core.flow.SessionEndSummary
import core.mission.LiveDrive
import core.session.VehicleStatus
import core.store.DocumentMissionStore
import core.store.DocumentProfileStore
import core.store.DocumentSessionStore
import engine.SessionLifecycle
import engine.DrivingEvent
import engine.SessionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * 엔진 호스트, 화면 뒤 로직, 화면 이동을 앱 수명에 묶는다.
 * 화면 회전 등으로 Activity 가 다시 만들어져도 세션과 지금 화면이 유지된다.
 * :core 객체는 메인 스레드에서만 다룬다. 끝난 주행은 [EngineHost] 가 메인 스레드에서 넘긴다.
 */
class SafetyApp : Application() {
    lateinit var host: EngineHost
        private set
    lateinit var graph: AppGraph
        private set
    lateinit var navigator: Navigator
        private set
    /** 온보딩 시동음. 재생 스레드를 하나 쓰므로 Activity 가 다시 만들어져도 앱에 하나만 둔다 */
    val startupSound: StartupSound by lazy { StartupSound.raw(this) }
    lateinit var home: HomeModel
        private set
    lateinit var missionTab: MissionTabModel
        private set
    lateinit var shop: ShopModel
        private set
    /** 프로필 선택 화면. 프로필을 고르면 홈으로 간다. 홈·미션·상점·리포트는 [AppGraph.profileId] 의 데이터를 쓴다 */
    lateinit var profile: ProfileModel
        private set
    lateinit var addProfile: AddProfileModel
        private set
    lateinit var drives: DriveRecorder
        private set
    lateinit var report: ReportModel
        private set
    /** 통계 탭 (DashboardView) */
    lateinit var stats: StatsModel
        private set
    /** 변신 영상: 레벨업하면 한 편, 홈 "변신 다시 보기"는 지금 단계까지 */
    lateinit var transform: TransformModel
        private set
    /** 주행 중인지. 주행 중이면 홈(주행 화면)에 고정한다 */
    lateinit var driveMode: DriveMode
        private set
    /** Gleo 음성 질의에 답한다 (#3). [GleoActionReceiver] 가 부른다 */
    lateinit var gleo: GleoAnswers
        private set
    /** 시스템 주행 규제. 권한 없이 읽어, 권한을 받기 전에도 주행 모드에 들어간다 */
    private val driveRestriction by lazy { DriveRestriction(this) }
    /** 시스템 주행 규제가 걸려 있는지(P 단이 아님) */
    val driveRestricted: StateFlow<Boolean> get() = driveRestriction.restricted
    /** 주행 중 미션 진행도용 지금 주행 (07 "판정 시점") */
    private lateinit var liveDrive: LiveDrive
    /** 안전벨트·배터리·충전구. 출발 전 안전벨트·충전·배터리 미션의 판정 근거 */
    private lateinit var vehicleStatus: VehicleStatusMonitor
    /** 원격 콘텐츠 확인·다운로드 (#31). 비주행 상태에서 [checkContentIfDue] */
    lateinit var contentUpdater: RemoteContentUpdater
        private set
    private var lastContentCheckMs = 0L
    /** AI 리포트 동의 (#13). 기본 꺼짐 */
    lateinit var aiConsent: AiConsent
        private set
    /** 음성·위치 기록 설정 */
    lateinit var appSettings: AppSettings
        private set
    /** 설정 탭 (#101) */
    lateinit var settings: SettingsModel
        private set
    /** GPS 마지막 위치와 이벤트 위치 (#13 반복 지점). 기기 안 기록에만 쓴다 */
    lateinit var gps: GpsLocation
        private set
    private lateinit var eventLocations: EventLocations
    private lateinit var roadNames: RoadNames
    /** 프로필·세션·받은 미션을 저장하는 기기 파일 (#10) */
    private lateinit var documents: FileDocumentStore

    private val main = MainScope()

    /** 앱이 숨겨지거나 메모리가 모자라면 그림 캐시를 비운다. 다시 그릴 때 필요한 그림만 읽는다 */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            CharacterArtStore.clear()
            ItemArtStore.clear()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 저장해 둔 원격 콘텐츠가 있으면 그것, 없으면 내장 (#31). 서버 주소·토큰은 BuildConfig(local.properties)
        val contentFiles = ContentFiles(File(filesDir, ContentFiles.DIR))
        aiConsent = AiConsent(PrefsFlags(getSharedPreferences(AiConsent.PREFS, MODE_PRIVATE)))
        appSettings = AppSettings(PrefsFlags(getSharedPreferences(AppSettings.PREFS, MODE_PRIVATE)))
        // 디버그 탭은 켤 때마다 이번 실행에서만 보인다. 앱을 다시 켜면 늘 숨김(시연 영상에 나오지 않게, 11/15)
        appSettings.debugTabOn = false
        // 프로필·세션·받은 미션은 기기 파일에 저장한다 (#10). 읽지 못한 문서는 건너뛰고 로그에 남긴다
        documents = FileDocumentStore(File(filesDir, FileDocumentStore.DIR), log = { Log.w(LOG_TAG, it) })
        val skipped: (String) -> Unit = { where -> Log.w(LOG_TAG, "저장 문서를 읽지 못해 건너뜀: $where") }
        val loadStartMs = SystemClock.elapsedRealtime()
        val profileStore = DocumentProfileStore(documents, onSkip = skipped)
        val sessionStore = DocumentSessionStore(documents, onSkip = skipped)
        val missionStore = DocumentMissionStore(documents, onSkip = skipped)
        Log.i(LOG_TAG, "저장 데이터 읽기 ${SystemClock.elapsedRealtime() - loadStartMs}ms")
        graph = AppGraph(
            profiles = profileStore,
            sessions = sessionStore,
            missionStore = missionStore,
            content = ContentState(ContentUpdate.pickAtStartupFromBytes(BundledContent.pack, contentFiles.readPack(), BuildConfig.VERSION_CODE)),
            aiClient = HttpAiClient.fromConfig(BuildConfig.AI_RELAY_URL, BuildConfig.AI_RELAY_TOKEN, log = { Log.w(LOG_TAG, it) }),
            aiMinDriveMinutes = AppConfig.AI_MIN_DRIVE_MINUTES,
            aiConsent = aiConsent::isOn,
            defaultCharacterAsset = AppConfig.DEFAULT_CHARACTER_ASSET,
            demoStartStage = BuildConfig.DEMO_START_STAGE,
            defaultAlias = AppConfig.DEFAULT_CHARACTER_ALIAS,
            lastProfileId = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString(KEY_CURRENT_PROFILE, null),
        )
        // 기본 프로필 데이터를 옮겼으면 AI 리포트 동의도 같은 프로필로 옮긴다 (#105)
        graph.adoptedDefaultTo?.let { to -> aiConsent.move(Profile.DEFAULT, to); Log.i(LOG_TAG, "기본 프로필 데이터를 먼저 만든 프로필로 옮김") }
        if (BuildConfig.DEMO_START_STAGE > 1) Log.i(LOG_TAG, "시연 모드: ${BuildConfig.DEMO_START_STAGE}단계로 시작")
        contentUpdater = RemoteContentUpdater(
            graph.content, BuildConfig.CONTENT_BASE_URL, BuildConfig.VERSION_CODE, contentFiles, HttpFetcher(), log = { Log.i(LOG_TAG, it) },
        )
        Log.i(LOG_TAG, "콘텐츠 v${graph.content.active.contentVersion}, AI ${if (BuildConfig.AI_RELAY_URL.isBlank()) "없음(규칙 문장)" else "중계 서버"}, 원격 콘텐츠 ${if (contentUpdater.enabled) "확인함" else "없음(내장만)"}")
        gps = GpsLocation(this, log = { Log.i(LOG_TAG, it) })
        // 위치 기록을 끄면 GPS 를 멈추고, 혹시 남은 위치도 붙이지 않는다
        roadNames = RoadNames(this)
        eventLocations = EventLocations({ if (appSettings.locationOn) gps.lastFix() else null })
        drives = DriveRecorder(graph, log = { Log.i(LOG_TAG, it) }, locate = eventLocations::of)
        home = HomeModel({ graph.profileId }, graph.profiles, graph.growth, content = { graph.content.active })
        navigator = Navigator(onTopLevelEntered = ::onTopLevelEntered)
        liveDrive = LiveDrive { graph.profileId }
        // 시연 모드(local.properties demo.start.stage > 1)에서는 출발 전 안전벨트를 맨 것으로 본다
        vehicleStatus = VehicleStatusMonitor(this, demoBeltBuckled = BuildConfig.DEMO_START_STAGE > 1)
        vehicleStatus.onSoc = { pct -> main.launch { onBatteryLevel(pct) } }
        host = EngineHost(
            sources = listOf(AaosCarPropertySource(this), PleosVehicleSdkSource(this)),
            // 음성 안내(TTS) 대신 캐릭터 효과음 (11/14). 설정의 캐릭터 음성으로 켜고 끈다
            voice = SwitchableVoicePlayer(SfxVoicePlayer(this)) { appSettings.voiceOn },
            onSessionEnded = { r -> onSessionEnded(r) },
            onLifecycle = ::onDriveLifecycle,
            onEvent = { e -> eventLocations.onEvent(e); liveDrive.event(e); if (liveDrive.record() != null) showLiveProgress() },
        )
        missionTab = MissionTabModel(
            { graph.profileId }, graph.missions, graph.missionActions,
            slotCount = { graph.content.active.missions.rules.slotCount },
            // [임시] 주행 규제 연동(#43) 전까지 엔진 세션이 열려 있으면 주행 중으로 본다
            driving = { host.driveStartMs.value != null },
            now = System::currentTimeMillis,
            onChanged = home::refresh,
            live = { liveDrive.record()?.let { graph.missions.liveProgress(it, vehicleStatus.snapshot()) } ?: emptyMap() },
        )
        shop = ShopModel(
            { graph.profileId }, graph.profiles, graph.wallet,
            content = { graph.content.active },
            // [임시] 주행 규제 연동(#43) 전까지 엔진 세션이 열려 있으면 주행 중으로 본다
            driving = { host.driveStartMs.value != null },
            onChanged = home::refresh,
            onLeave = { navigator.back() },
            onGoMissions = { navigator.go(Destination.MISSION) },
        )
        report = ReportModel({ graph.profileId }, graph.sessions)
        stats = StatsModel({ graph.profileId }, graph.sessions, graph.missionStore, graph.profiles, lineFor = graph.pipeline::fallbackComment)
        transform = TransformModel(exists = ::assetExists, flags = PrefsFlags(getSharedPreferences(TransformModel.PREFS, MODE_PRIVATE)))
        settings = SettingsModel(
            { graph.profileId }, aiConsent, appSettings,
            aiAvailable = BuildConfig.AI_RELAY_URL.isNotBlank(),
            driving = { host.driveStartMs.value != null },
            onAiChanged = ::onAiConsentChanged,
            onLocationChanged = { on -> Log.i(LOG_TAG, "위치 기록 ${if (on) "켬" else "끔"}"); if (on) gps.start() else gps.stop() },
            onOpenProfiles = ::openProfiles,
        )
        profile = ProfileModel(graph.profiles, characters = { graph.content.active.characters }, navigation = object : ProfileNavigation {
            override fun selectProfile(profileId: String) = this@SafetyApp.selectProfile(profileId)
            override fun addProfile() {
                // 화면을 바꾸기 전에 입력을 비워, 지난번 입력이 한 프레임도 보이지 않게 한다
                addProfile.enter()
                navigator.go(Destination.ADD_PROFILE)
            }
            override fun editProfile(profileId: String) {
                if (addProfile.enterEdit(profileId)) navigator.go(Destination.EDIT_PROFILE)
            }
        })
        // 프로필 추가·수정을 닫으면 프로필 선택으로 돌아가며 목록을 다시 읽는다
        addProfile = AddProfileModel(
            graph.profiles, characters = { graph.content.active.characters }, defaultAssetKey = AppConfig.DEFAULT_CHARACTER_ASSET,
            navigation = {
                profile.refresh()
                navigator.back()
            },
            deleteProfile = ::deleteProfile,
        )
        seedJudgeDemo()
        // 프로필 선택에 들어오는 때는 앱 시작 직후(온보딩 다음)와 프로필 추가·수정을 닫을 때뿐이라, 그때 목록을 읽는다
        profile.refresh()
        startMissionTicker()
        // 주행이 시작·끝나면 미션 탭의 도전·취소·비우기 막기를 바로 바꾼다
        main.launch { host.driveStartMs.collect { missionTab.refresh(); settings.refresh() } }
        driveRestriction.start()
        driveMode = DriveMode(host.driveStartMs, driveRestriction.restricted, main)
        main.launch { driveMode.driving.collect { if (it) navigator.lockToHome() else navigator.unlock() } }
        // 세션이 막 끝나 주행 종료 화면으로 간 직후 규제가 걸리면 driving 이 true 그대로라 위에서 다시 고정하지 않는다. 규제가 걸릴 때도 고정한다
        main.launch { driveRestriction.restricted.collect { if (it) navigator.lockToHome() } }
        gleo = GleoAnswers(
            driving = { driveMode.driving.value },
            pet = home::current,
            maxStage = graph.growth.maxStage,
            slot = missionTab::slotNow,
            offered = missionTab::offeredNow,
            lastDrive = { graph.sessions.list(graph.profileId).lastOrNull { it.valid } },
        )
        checkContentIfDue()
    }

    private val driving: Boolean get() = host.driveStartMs.value != null

    private fun onTopLevelEntered(dest: Destination) {
        // 받아 둔 콘텐츠는 주행 중이 아닐 때만 바꾼다 (#31 ContentUpdate 흐름 3). 새 묶음 확인도 비주행 상태에서만
        if (!driving) { graph.onTopLevelEntered(); checkContentIfDue() }
        when (dest) {
            Destination.HOME -> { home.refresh(); missionTab.refresh() }
            Destination.MISSION -> missionTab.refresh()
            // 다른 탭에서 들어오면 저장한 배치로 되돌리고, 하위 화면(디버그 등)에서 돌아오면 저장 전 배치를 그대로 둔다
            Destination.SHOP -> if (navigator.returned) shop.refresh() else shop.enter()
            Destination.REPORT -> { report.refresh(); stats.enter() }
            Destination.SETTINGS -> settings.refresh()
            else -> Unit
        }
    }

    /**
     * 설정의 프로필 선택 (#119): 앱을 켰을 때처럼 프로필 선택에서 다시 시작한다. 뒤로 가기 키는 앱을 숨긴다.
     * 카드의 캐릭터 단계는 주행으로도 바뀌므로 들어갈 때 목록을 다시 읽는다
     */
    private fun openProfiles() {
        if (driving) return
        profile.refresh()
        navigator.startOver(Destination.PROFILE)
    }

    /** 주행 시작 버튼: 주행 모드로 들어간다. 엔진 세션은 출발하면 열린다 */
    fun startDrive() = driveMode.start()

    /** 주행 종료 버튼(정차 중에만 보인다): 열린 세션이 있으면 닫아 주행 종료 화면으로, 없으면 주행 모드만 끝낸다 */
    fun endDrive() {
        if (host.driveStartMs.value != null) host.endSession() else driveMode.clear()
    }

    /** 세션 시작·인정: 주행 중 진행도를 모으기 시작하고, 인정되면 보인다. 끝은 [onSessionEnded] 에서 판정한 뒤 지운다 */
    private fun onDriveLifecycle(lc: SessionLifecycle) {
        when (lc) {
            is SessionLifecycle.Started -> { vehicleStatus.onDriveStarted(); liveDrive.start(lc.startMs) }
            is SessionLifecycle.Validated -> { liveDrive.validate(lc.eventsSoFar); showLiveProgress() }
            is SessionLifecycle.Ended -> Unit
        }
    }

    /** 주행 중 진행도를 미션 탭과 홈의 mission_panel 에 다시 그린다. 미션 탭 알림은 그대로 둔다 */
    private fun showLiveProgress() = missionTab.refresh(missionTab.state.value.notice)

    /** 끝난 주행: 저장·미션·보상 처리 뒤 주행 종료 화면으로. AI 리포트는 뒤에 와서 코멘트만 바꾼다 (#13) */
    private fun onSessionEnded(
        result: SessionResult, where: ((DrivingEvent) -> GeoFix?)? = null, origin: String? = null, destination: String? = null,
        /** 도착한 곳. 시연 주행은 시연 경로의 끝, 실제 주행은 GPS 마지막 위치 */
        arrival: GeoFix? = null,
    ) {
        val status = vehicleStatus.takeAtDriveEnd()
        val now = System.currentTimeMillis()
        val locate: (DrivingEvent) -> GeoFix? = if (appSettings.locationOn) where ?: eventLocations::of else { _ -> null }
        val ended = drives.onSessionEnded(result, status, now, locate, origin, destination)
        val summary = givePlaceGifts(ended, if (appSettings.locationOn) arrival ?: gps.lastFix() else null)
        nameRoads(summary.record)
        eventLocations.clear()
        liveDrive.end(result.startMs)
        home.refresh(); missionTab.refresh()
        summary.leveledUpTo?.let { transform.onLeveledUp(home.state.value.characterAssetKey, it) }
        // 세션이 끝났으니 홈 고정을 풀고 주행 종료 화면으로 간다. 진입 전 화면이었으면 그 화면에 둔다(driveStartMs 는 이 처리가 끝난 뒤 지워진다).
        // 주행 규제가 남아 있으면(D 단 정차) 홈 고정을 두고, 규제가 풀려 주행 모드가 끝날 때 간다
        driveMode.clear()
        navigator.showDriveEnd(Destination.DRIVE_END, restricted = driveRestriction.restricted.value)
        settings.refresh()
        requestAiReport(summary, status, now)
    }

    /**
     * 장소 선물 (11/15): 이번 주행의 사건 자리와 도착 자리 중 하나라도 장소 반경 안이면 그 배경 아이템을 준다.
     * 위치 비교는 기기 안에서만 한다. 위치 기록이 꺼져 있으면 주지 않는다
     */
    private fun givePlaceGifts(summary: SessionEndSummary, arrival: GeoFix?): SessionEndSummary {
        if (!appSettings.locationOn) return summary
        val points = summary.record.events.mapNotNull { e -> e.lat?.let { la -> e.lng?.let { la to it } } } +
            listOfNotNull(arrival?.let { it.lat to it.lng })
        if (points.isEmpty()) return summary
        val (state, got) = graph.wallet.visit(graph.profiles.state(graph.profileId), points)
        if (got.isEmpty()) return summary
        graph.profiles.saveState(state)
        val texts = graph.content.active.itemTexts
        val gifts = got.map { PlaceGift(texts[it.nameKey] ?: it.nameKey, it.place?.let { p -> texts[p.nameKey] ?: p.nameKey } ?: "", it.imageKey) }
        Log.i(LOG_TAG, "장소 선물: ${gifts.map { it.itemName }}")
        return summary.copy(profile = state, placeGifts = gifts).also { drives.update(it) }
    }

    /** 이벤트 좌표에 기기 지오코더로 도로명을 붙여 기록을 다시 저장한다(주행 상세 사건 줄). 기기 안에서만 쓴다 */
    private fun nameRoads(record: SessionRecord) {
        if (record.events.none { it.roadName == null && it.lat != null }) return
        main.launch {
            val named = withContext(Dispatchers.IO) { roadNames.fill(record) } ?: return@launch
            graph.sessions.save(named)
            stats.refresh()
        }
    }

    /** 끝난 주행의 AI 한마디를 받아 코멘트만 바꾼다. 동의가 꺼져 있으면 [graph.ai] 가 보내지 않는다 */
    private fun requestAiReport(summary: SessionEndSummary, status: VehicleStatus, now: Long) = main.launch {
        val withAi = graph.ai.run(summary, AiCharacters.of(graph.profiles, graph.profileId, graph.content.active), status, now)
        if (withAi !== summary) { drives.update(withAi); Log.i(LOG_TAG, "AI 리포트: ${withAi.comment}") }
        else Log.i(LOG_TAG, "AI 리포트 없음(${if (aiConsent.isOn(graph.profileId)) "호출 조건 밖이거나 실패" else "동의 안 함"}): 규칙 문장 사용")
    }

    /** 설정·주행 종료 화면에서 AI 리포트 동의가 바뀌었을 때. 켜면 주행 종료 화면에 떠 있는 주행의 한마디도 받아 온다 */
    private fun onAiConsentChanged(on: Boolean) {
        Log.i(LOG_TAG, "AI 리포트 동의 ${if (on) "켬" else "끔"}")
        if (on && navigator.current.value == Destination.DRIVE_END) drives.last.value?.let { requestAiReport(it, VehicleStatus(), System.currentTimeMillis()) }
    }

    /**
     * 원격 콘텐츠 manifest 확인 (#31). 비주행 상태에서, 마지막 확인 뒤 [AppConfig.CONTENT_CHECK_INTERVAL_MS] 가 지났을 때만.
     * 통신·파일 IO 라 IO 스레드에서 돈다. 받은 묶음은 다음 탭 전환 때 적용된다.
     */
    fun checkContentIfDue(force: Boolean = false) {
        if (!contentUpdater.enabled || driving) return
        val now = System.currentTimeMillis()
        if (!force && now - lastContentCheckMs < AppConfig.CONTENT_CHECK_INTERVAL_MS) return
        lastContentCheckMs = now
        main.launch { withContext(Dispatchers.IO) { contentUpdater.check() } }
    }

    /**
     * 심사용 demo 계정 (11/15): 처음 설치해 프로필도 기록도 없으면 "demo" 프로필에 코인 [JUDGE_DEMO_COINS] 개, 이번 달 지난 주행 기록, 도전 중인 미션을 넣어 둔다.
     * 심사위원이 바로 들어가 상점·통계·지도를 구경할 수 있게. 캐릭터 단계(4)와 산타 모자는 시연 모드가 이미 맞춘다. 시연 빌드(demo.start.stage > 1)에서만
     */
    private fun seedJudgeDemo() {
        if (BuildConfig.DEMO_START_STAGE <= 1 || graph.profiles.profiles().isNotEmpty() || graph.sessions.list(graph.profileId).isNotEmpty()) return
        val now = System.currentTimeMillis()
        // 프로필이 없을 때 지금 프로필은 기본 프로필이다. demo 프로필을 그 id 로 만들어 바로 쓰게 한다
        graph.profiles.upsert(Profile(id = graph.profileId, name = JUDGE_DEMO_NAME, createdMs = now))
        graph.profiles.saveState(graph.wallet.earn(graph.profiles.state(graph.profileId), JUDGE_DEMO_COINS))
        val n = DemoHistory.seed(graph.sessions, graph.profileId, AppConfig.engine, now)
        // 미션도 미리 걸어 둔다: 오늘 미션을 받고, 주간 하나 + 개인 미션으로 슬롯을 채운다
        graph.planner.offerToday(graph.profileId, now)
        graph.missionActions.refresh(graph.profileId, now, null)
        val offers = graph.missions.offers(graph.profileId, now)
        val (weekly, personal) = offers.partition { it.kind == MissionKind.WEEKLY }
        val picked = (weekly.take(1) + personal).take(graph.content.active.missions.rules.slotCount)
        picked.forEach { graph.missionActions.challenge(graph.profileId, it.uid, now, driving = false) }
        Log.i(LOG_TAG, "심사용 demo 계정: 코인 $JUDGE_DEMO_COINS, 지난 기록 ${n}건, 미션 ${picked.map { it.title }}")
    }

    /** 디버그 탭: 주행 없이 포인트를 준다 (상점 확인용) */
    fun grantDebugPoints() {
        graph.profiles.saveState(graph.wallet.earn(graph.profiles.state(graph.profileId), AppConfig.DEBUG_GRANT_POINTS))
        home.refresh(); shop.refresh()
    }

    /** 디버그 탭: 고른 캐릭터에 경험치를 준다. 단계가 오르면 변신 영상이 나온다 */
    fun grantDebugXp() {
        val r = Rewards(graph.profiles, graph.growth, graph.wallet).give(graph.profileId, coins = 0, xp = AppConfig.DEBUG_GRANT_XP)
        home.refresh()
        r.leveledUpTo?.let { transform.onLeveledUp(home.state.value.characterAssetKey, it) }
    }

    /** 디버그 탭 "시연 단계로": 시험 주행으로 오른 캐릭터를 시연 시작 단계(없으면 4단계) 맨 앞으로 되돌린다. 별칭은 그대로 (11/15) */
    fun resetDemoStage() {
        val sel = graph.profiles.state(graph.profileId).selected ?: return
        val stage = BuildConfig.DEMO_START_STAGE.takeIf { it > 1 } ?: DEMO_STAGE_FALLBACK
        val p = graph.profiles.progress(graph.profileId, sel)
        graph.profiles.saveProgress(p.copy(stage = stage, xp = graph.growth.minXp(stage)))
        home.refresh()
    }

    /** 디버그 탭을 보일지(디버그 빌드에서만 켤 수 있다) */
    val debugTabOn = MutableStateFlow(false)

    /** 홈 coin_bar 길게 누르기: 디버그 탭을 켜고 끈다. 끌 때 디버그 탭에 있었으면 홈으로 */
    fun toggleDebugTab() {
        if (!AppConfig.SHOW_DEBUG_TAB) return
        val on = !debugTabOn.value
        debugTabOn.value = on
        Log.i(LOG_TAG, "디버그 탭 ${if (on) "보임" else "숨김"}")
        if (!on && navigator.current.value == Destination.DEBUG) navigator.go(Destination.HOME)
    }

    /** 홈 캐릭터 누르기: 2단계부터 지금 단계까지. 볼 영상이 없으면 아무것도 하지 않는다 */
    fun replayTransform() = home.state.value.let { transform.replay(it.characterAssetKey, it.stage) }

    /** assets 안에 [path] 파일이 있는지. 폴더 목록은 한 번만 읽는다 */
    private val assetDirs = mutableMapOf<String, Set<String>>()
    private fun assetExists(path: String): Boolean {
        val dir = path.substringBeforeLast('/', "")
        val names = assetDirs.getOrPut(dir) { runCatching { assets.list(dir)?.toSet() }.getOrNull() ?: emptySet() }
        return path.substringAfterLast('/') in names
    }

    /** 권한을 받은 뒤 신호를 받기 시작한다: 차량 신호(엔진)와 GPS(반복 지점) */
    fun startSources() {
        if (appSettings.locationOn) gps.start()
        host.start()
        vehicleStatus.start()
    }

    /** 주행 종료 화면에서 AI 리포트를 켤지 물은 데 대한 답. 답하면 다시 묻지 않는다 */
    fun answerAiConsent(on: Boolean) = settings.setAi(on)

    /** 프로필 수정 화면의 삭제: 그 프로필의 데이터(프로필·상태·성장, 주행 기록, 받은 미션)와 AI 리포트 동의를 지운다 */
    private fun deleteProfile(profileId: String) {
        if (driving) return
        graph.deleteProfile(profileId)
        aiConsent.remove(profileId)
        saveCurrentProfile()
        Log.i(LOG_TAG, "프로필 삭제")
    }

    /**
     * 프로필 선택 화면에서 프로필을 골랐다: 그 프로필의 데이터로 바꾸고 홈으로 간다 (#105).
     * 홈·미션 탭은 홈에 들어갈 때, 상점·리포트·설정은 그 탭에 들어갈 때 새 프로필로 다시 읽는다.
     * 오늘 미션과 주행 끝 AI 동의 질문은 탭에 들어가지 않아도 쓰여 여기서 바로 맞춘다.
     * 엔진 세션이 열려 있으면 프로필을 바꾸지 않는다(한 주행의 기록·미션 판정이 두 프로필로 나뉘지 않게)
     */
    private fun selectProfile(profileId: String) {
        if (!driving && graph.selectProfile(profileId)) {
            saveCurrentProfile()
            tickMissions()
            settings.refresh()
            Log.i(LOG_TAG, "프로필 선택")
        }
        navigator.go(Destination.HOME)
    }

    /** 지금 쓰는 프로필을 저장해, 앱을 다시 켜도 그 프로필로 시작한다 */
    private fun saveCurrentProfile() {
        getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).edit().putString(KEY_CURRENT_PROFILE, graph.profileId).apply()
    }

    /**
     * 디버그 탭: 프로필 초기화. 프로필 목록, 프로필 상태(고른 캐릭터·코인·아이템·배치), 캐릭터 성장, AI 리포트 동의를 지운다.
     * 주행 기록과 받은 미션은 남긴다. 처음 켠 것처럼 프로필 선택 화면으로 간다. 주행 중이면 하지 않는다
     */
    fun resetProfiles() {
        if (driving) return
        graph.clearProfiles()
        saveCurrentProfile()
        aiConsent.clear()
        settings.refresh()
        home.refresh(); missionTab.refresh(); shop.enter(); profile.refresh()
        navigator.startOver(Destination.PROFILE)
        Log.i(LOG_TAG, "프로필 초기화")
    }

    /**
     * 디버그 탭: 데이터 전체 초기화. 앱 전용 폴더의 파일(프로필·세션·미션 저장, 받은 원격 콘텐츠), 캐시, 설정 값을 지우고 앱을 다시 켠다.
     * 새로 설치한 것과 같은 상태로 시작한다. 주행 중이면 하지 않는다
     */
    fun resetAllData() {
        if (driving) return
        Log.i(LOG_TAG, "데이터 전체 초기화. 앱을 다시 켭니다")
        host.stop()
        documents.close()   // 남은 쓰기가 지운 뒤에 파일을 다시 만들지 않게 먼저 멈춘다
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        File(dataDir, SHARED_PREFS_DIR).listFiles()?.forEach { deleteSharedPreferences(it.nameWithoutExtension) }
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
        exitProcess(0)
    }

    /** 디버그 탭: 지금 시작하는 시연 주행 한 번을 차량 없이 엔진에 흘려 끝난 주행으로 처리한다. 엔진 세션이 열려 있으면 하지 않는다 */
    /** 디버그 탭 "지난 기록 채우기": 시연 영상용으로 이번 달 지난 주행 기록을 넣는다(DemoHistory). 주행 중이면 하지 않는다 */
    fun seedDemoHistory() {
        if (host.driveStartMs.value != null) return
        val n = DemoHistory.seed(graph.sessions, graph.profileId, AppConfig.engine, System.currentTimeMillis())
        Log.i(LOG_TAG, "시연용 지난 기록 ${n}건 넣음")
        stats.refresh(); report.refresh()
    }

    /**
     * 디버그 탭 "오늘·어제 3개만" (11/15): 시험하느라 쌓인 오늘·어제 주행을 날마다 최근 [KEEP_PER_DAY] 개만 남긴다.
     * 저장소에 한 건씩 지우는 길이 없어 이 프로필 기록을 비우고 남길 것만 다시 넣는다. 주행 중이면 하지 않는다
     */
    fun trimTestDrives() {
        if (host.driveStartMs.value != null) return
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        fun day(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val all = graph.sessions.list(graph.profileId)
        val recent = all.filter { day(it.startWallMs) == today || day(it.startWallMs) == today.minusDays(1) }
        val drop = recent.groupBy { day(it.startWallMs) }.values.flatMap { it.sortedBy { r -> r.startWallMs }.dropLast(KEEP_PER_DAY) }.map { it.id }.toSet()
        if (drop.isEmpty()) return
        graph.sessions.deleteProfile(graph.profileId)
        all.filter { it.id !in drop }.forEach(graph.sessions::save)
        Log.i(LOG_TAG, "오늘·어제 시험 주행 ${drop.size}건 지움")
        stats.refresh(); report.refresh(); home.refresh()
    }

    /** 이번 주행에서 이미 꼬르륵한 잔량 기준 (주행 시작 시각 → 기준들) */
    private var hungryWarned: Pair<Long, Set<Int>>? = null

    /**
     * 배터리 꼬르륵 (11/15): 주행 중 잔량이 [LOW_BATTERY_STEPS] 아래로 처음 내려가면 캐릭터가 배고파한다(효과음 + 말풍선).
     * 기준마다 주행 한 번에 한 번만. 잔량이 처음부터 낮으면 첫 값에서 한 번
     */
    private fun onBatteryLevel(pct: Int) {
        val start = host.driveStartMs.value ?: return
        val done = hungryWarned?.takeIf { it.first == start }?.second ?: emptySet()
        val step = LOW_BATTERY_STEPS.filter { pct <= it && it !in done }.minOrNull() ?: return
        hungryWarned = start to (done + LOW_BATTERY_STEPS.filter { it >= step })
        Log.i(LOG_TAG, "배터리 ${pct}% → 꼬르륵")
        host.react(engine.FeedbackCue(engine.CueType.LOW_BATTERY, System.currentTimeMillis(), 0, null))
    }

    /** 디버그 탭 "꼬르륵": 주행 중이면 잔량 9% 가 들어온 것처럼 */
    fun debugLowBattery() { hungryWarned = null; onBatteryLevel(DEBUG_LOW_BATTERY_PCT) }

    /** [namsan] 이면 남산에 도착하는 경로로(장소 선물 시연, 11/15) */
    fun replayDemoDrive(long: Boolean, namsan: Boolean) {
        if (host.driveStartMs.value != null) return
        // 지금 시작한 주행으로 친다(미션은 도전한 뒤 시작한 주행만 센다). 그래서 도착 시각은 길이만큼 뒤로 보인다
        val result = DemoDriveReplay.run(AppConfig.engine, System.currentTimeMillis(), long) ?: return
        val route = if (namsan) DemoRoute.NAMSAN else DemoRoute.GANGNAM
        onSessionEnded(result, DemoRoute.locate(result, route), route.origin, route.destination, DemoRoute.arrival(route, result.endMs))
    }

    /** 미션 제공·00:00 갱신·유효시간 끝 처리를 1분마다 (주행 중이면 주행 보전). 개인 미션은 먼저 7일 습관으로 정해 둔다 (#13) */
    private fun startMissionTicker() = main.launch {
        while (isActive) {
            tickMissions()
            delay(AppConfig.MISSION_TICK_MS)
        }
    }

    /** 지금 프로필의 오늘 미션 제공, 날짜 갱신, 유효시간 끝 처리를 한 번 한다 */
    private fun tickMissions() {
        val now = System.currentTimeMillis()
        graph.planner.offerToday(graph.profileId, now).all.takeIf { it.isNotEmpty() }?.let { Log.i(LOG_TAG, "습관 개인 미션 ${it.map { m -> m.title }}") }
        val done = graph.missionActions.refresh(graph.profileId, now, host.driveStartMs.value)
        if (!done.isEmpty) home.refresh()
        missionTab.refresh()
    }

    companion object {
        /** 디버그 "오늘·어제 3개만": 날마다 남길 주행 수 */
        private const val KEEP_PER_DAY = 3
        private const val DEMO_STAGE_FALLBACK = 4
        private const val JUDGE_DEMO_NAME = "demo"
        private const val JUDGE_DEMO_COINS = 500
        /** 꼬르륵 하는 배터리 잔량 기준(%) */
        private val LOW_BATTERY_STEPS = listOf(20, 10)
        private const val DEBUG_LOW_BATTERY_PCT = 9
        const val LOG_TAG = "PleosPet"
        /** 앱 데이터 폴더(`dataDir`) 아래 SharedPreferences 파일 폴더 (Android 고정 이름) */
        private const val SHARED_PREFS_DIR = "shared_prefs"
        /** 지금 쓰는 프로필을 저장하는 SharedPreferences */
        private const val PROFILE_PREFS = "profile"
        private const val KEY_CURRENT_PROFILE = "current"
    }
}

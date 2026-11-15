package ai.safetypoc.app

import core.ai.AiClient
import core.ai.AiDriveEnd
import core.ai.AiPayloadBuilder
import core.ai.AiService
import core.ai.PersonalMissionPlanner
import core.character.CharacterSelector
import core.character.GrowthModule
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.ProfileStore
import core.character.Wallet
import core.content.BundledContent
import core.content.ContentState
import core.flow.MissionActions
import core.flow.Rewards
import core.flow.SessionEndPipeline
import core.item.StageItem
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.mission.MissionStore
import core.session.InMemorySessionStore
import core.session.SessionStore

/**
 * 화면 뒤 로직(:core) 객체를 한 번 만들어 앱 전체가 같이 쓴다. SafetyApp 이 갖는다.
 * 저장소 기본값은 메모리 구현이다. 앱은 기기에 저장하는 구현(`core.store.Document*Store`, #10)을 넘긴다.
 */
class AppGraph(
    val profiles: ProfileStore = InMemoryProfileStore(),
    val sessions: SessionStore = InMemorySessionStore(),
    val missionStore: MissionStore = InMemoryMissionStore(),
    val content: ContentState = ContentState(BundledContent.pack),
    /** AI 중계 서버 호출(:app `HttpAiClient`). null 이면 AI 없이 규칙 문장만 (#13) */
    aiClient: AiClient? = null,
    /** AI 를 부르는 주행 최소 시간(분). 앱은 AppConfig.AI_MIN_DRIVE_MINUTES */
    aiMinDriveMinutes: Int = core.ai.AiGate.MIN_MINUTES,
    /** 프로필이 AI 리포트에 동의했는지. 앱은 `AiConsent.isOn`(기본 꺼짐). 테스트 기본값은 동의함 */
    aiConsent: (profileId: String) -> Boolean = { true },
    /** 처음 실행할 때 고른 것으로 둘 차종(그림 폴더 이름). 없거나 콘텐츠에 없으면 첫 캐릭터. 앱은 AppConfig.DEFAULT_CHARACTER_ASSET */
    private val defaultCharacterAsset: String? = null,
    /** 시연 모드: 처음 고른 캐릭터를 이 단계로 시작한다. 1 이면 꺼짐. 앱은 BuildConfig.DEMO_START_STAGE */
    private val demoStartStage: Int = 1,
    /** 처음 고른 캐릭터의 별명. 별명 입력 화면이 생기기 전까지 쓰는 기본값. null 이면 차 이름. 앱은 AppConfig.DEFAULT_CHARACTER_ALIAS */
    private val defaultAlias: String? = null,
    /** 앱을 켤 때 쓸 프로필: 지난번에 고른 프로필. 저장된 프로필에 없으면 먼저 만든 프로필, 프로필이 없으면 기본 프로필 */
    lastProfileId: String? = null,
) {
    /**
     * 지금 쓰는 프로필. 홈·미션·상점·리포트·설정, 주행 기록, 미션 판정, AI 동의가 이 프로필의 데이터를 쓴다 (#105).
     * 프로필 선택 화면에서 [selectProfile] 로 바꾼다. 프로필이 하나도 없으면 [Profile.DEFAULT] 다
     */
    var profileId: String = startProfile(lastProfileId)
        private set
    val growth = GrowthModule()
    val wallet = Wallet { content.active.items }
    val characters = CharacterSelector { content.active.characters }
    val missions = MissionEngine(missionStore, sessions, missions = { content.active.missions })
    val missionActions = MissionActions(missions, Rewards(profiles, growth, wallet))
    val pipeline = SessionEndPipeline(sessions, profiles, missions, growth, content = { content.active }, wallet = wallet)
    /** 개인 미션(하루 2개)을 랜덤 대신 7일 습관으로 정해 제공한다 (#13). `missionActions.refresh` 바로 앞에 `offerToday` */
    val planner = PersonalMissionPlanner(missions, missionStore, sessions, { content.active.missions })
    /** 주행 끝 AI 리포트 (#13). 요약의 comment 만 바꾼다. 동의 안 함·실패·없음이면 규칙 문장 그대로 */
    val ai = AiDriveEnd(AiPayloadBuilder(sessions, missionStore), AiService(aiClient), aiMinDriveMinutes, aiConsent, latest = sessions::get)

    /** 앱을 켤 때 기본 프로필 데이터를 옮긴 프로필 id. 옮기지 않았으면 null. 앱 쪽 저장(AI 동의)도 이 프로필로 옮긴다 */
    val adoptedDefaultTo: String? = adoptDefaultData()

    init {
        ensureStarterCharacter()
    }

    /**
     * 프로필 목록에 없는 기본 프로필의 데이터(프로필 상태, 캐릭터 성장, 주행 기록, 받은 미션)를 먼저 만든 프로필로 옮긴다.
     * 프로필이 없거나, 기본 프로필 id 의 프로필이 있거나, 옮길 프로필에 주행 기록이 있으면 옮기지 않는다
     */
    private fun adoptDefaultData(): String? {
        val all = profiles.profiles()
        if (all.isEmpty() || all.any { it.id == Profile.DEFAULT }) return null
        val target = all.minBy { it.createdMs }.id
        if (sessions.list(target).isNotEmpty()) return null
        val state = profiles.state(Profile.DEFAULT)
        val progress = profiles.progresses(Profile.DEFAULT)
        val drives = sessions.list(Profile.DEFAULT)
        val received = missionStore.list(Profile.DEFAULT)
        if (state == ProfileState(Profile.DEFAULT) && progress.isEmpty() && drives.isEmpty() && received.isEmpty()) return null
        profiles.saveState(state.copy(profileId = target))
        progress.forEach { profiles.saveProgress(it.copy(profileId = target)) }
        received.forEach { missionStore.upsert(it.copy(profileId = target)) }
        // 주행 기록은 마지막에 옮긴다. 옮기는 도중 앱이 꺼지면 다음 실행에서 처음부터 다시 옮긴다(대상에 주행 기록이 생기기 전이면)
        drives.forEach { sessions.save(it.copy(profileId = target)) }
        profiles.delete(Profile.DEFAULT)
        sessions.deleteProfile(Profile.DEFAULT)
        missionStore.deleteProfile(Profile.DEFAULT)
        return target
    }

    /** [임시] 캐릭터 고르기 화면 전까지: 지금 프로필이 고른 캐릭터가 없으면(처음 실행, 프로필 초기화 뒤) 기본 차종(그랜저, 없으면 첫 캐릭터)을 고른 것으로 둔다. 시연 모드면 그 단계로 */
    fun ensureStarterCharacter() {
        val state = profiles.state(profileId)
        val all = content.active.characters
        if (state.selected == null) (all.firstOrNull { it.assetKey == defaultCharacterAsset } ?: all.firstOrNull())?.let { first ->
            characters.select(state, first.id)?.let(profiles::saveState)
        }
        if (demoStartStage > 1) profiles.state(profileId).selected?.let { id ->
            val p = profiles.progress(profileId, id)
            // 단계 시작점이면 경험치 막대가 비어 보여, 다음 단계까지 [DEMO_STAGE_PROGRESS] 만큼 찬 상태로 시작한다
            val lo = growth.minXp(demoStartStage)
            val xp = if (demoStartStage >= growth.maxStage) lo else lo + ((growth.minXp(demoStartStage + 1) - lo) * DEMO_STAGE_PROGRESS).toInt()
            if (p.xp < xp) profiles.saveProgress(p.copy(xp = xp, stage = growth.stageFor(xp)))
        }
        if (demoStartStage > 1) wearDemoHat()
        // 별명은 홈에 처음 들어올 때 이름 짓기 팝업에서 정한다(11/14). 기본 별명은 그 입력 칸에 미리 채우는 값이다
    }

    /** 시연 모드: 산타 모자를 가진 것으로 두고 캐릭터 머리에 씌운다(자리는 StageLayout.headWearPoint 가 단계에 맞춘다). 이미 가졌으면(사용자가 뺐을 수도 있다) 건드리지 않는다 */
    private fun wearDemoHat() {
        val hat = content.active.items.firstOrNull { it.imageKey == DEMO_HAT_IMAGE } ?: return
        val state = profiles.state(profileId)
        if (hat.id in state.owned) return
        profiles.saveState(state.copy(owned = state.owned + hat.id, placed = state.placed + StageItem(hat.id)))
    }

    /** 지금 쓰는 프로필을 [id] 로 바꾼다. 저장된 프로필이 아니면 바꾸지 않고 false */
    fun selectProfile(id: String): Boolean {
        if (profiles.profiles().none { it.id == id }) return false
        profileId = id
        ensureStarterCharacter()
        return true
    }

    /** 프로필 하나를 지운다: 프로필·상태·캐릭터 성장, 주행 기록, 받은 미션. 지금 쓰는 프로필이면 먼저 만든 프로필(없으면 기본 프로필)로 바꾼다 */
    fun deleteProfile(id: String) {
        profiles.delete(id)
        sessions.deleteProfile(id)
        missionStore.deleteProfile(id)
        if (id == profileId) profileId = firstProfile()
    }

    /** 프로필 목록과 프로필 상태·캐릭터 성장을 전부 지우고 기본 프로필로 돌아간다. 주행 기록과 받은 미션은 남는다 */
    fun clearProfiles() {
        profiles.clear()
        profileId = Profile.DEFAULT
        ensureStarterCharacter()
    }

    private fun startProfile(last: String?): String =
        last?.takeIf { id -> profiles.profiles().any { it.id == id } } ?: firstProfile()

    /** 먼저 만든 프로필. 프로필이 없으면 기본 프로필 */
    private fun firstProfile(): String = profiles.profiles().minByOrNull { it.createdMs }?.id ?: Profile.DEFAULT

    /** top-level destination 에 들어갈 때: 받아 둔 콘텐츠를 적용한다 (#31) */
    fun onTopLevelEntered() {
        content.applyStaged()
    }

    companion object {
        /** 시연 모드: 시작 단계에서 다음 단계까지 찬 비율 */
        const val DEMO_STAGE_PROGRESS = 0.6f
        /** 시연 모드에서 씌워 두는 소품(산타 모자)의 이미지 값 */
        const val DEMO_HAT_IMAGE = "Accessories/Santa_hat"
    }
}

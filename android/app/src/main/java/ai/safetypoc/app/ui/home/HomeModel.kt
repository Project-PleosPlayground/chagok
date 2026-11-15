package ai.safetypoc.app.ui.home

import ai.safetypoc.app.ui.stage.StageItemView
import ai.safetypoc.app.ui.stage.StageLayout
import core.character.GrowthModule
import core.character.ProfileStore
import core.content.ContentPack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 홈 화면이 보여 줄 값 */
data class HomeState(
    /** 고른 캐릭터 이름(별칭이 있으면 별칭). 고르지 않았으면 null */
    val characterName: String? = null,
    /** 캐릭터 별명을 아직 짓지 않았다. 홈에 들어오면 이름 짓기 팝업을 띄운다 */
    val needsName: Boolean = false,
    /** 캐릭터 그림 폴더 이름 */
    val characterAssetKey: String? = null,
    val stage: Int = 1,
    val xp: Int = 0,
    /** 다음 단계까지 0~1 */
    val stageProgress: Float = 0f,
    /** 이번 단계에 들어온 뒤 모은 경험치 */
    val xpInStage: Int = 0,
    /** 이번 단계에서 다음 단계까지 필요한 경험치. 최고 단계면 0 */
    val xpSpan: Int = 0,
    /** 가진 포인트 */
    val coins: Int = 0,
    /** 스테이지에 저장된 아이템. 그릴 순서(아래부터) */
    val placed: List<StageItemView> = emptyList(),
)

/** 홈 화면에서 하는 동작. 다른 탭으로 가는 것은 tab_bar 가 한다 */
interface HomeActions {
    /** mission_panel 빈 상태의 "미션 고르기" */
    fun openMissions()
    /** drive_start_button */
    fun startDrive()
    /** coin_bar 옆 설정 아이콘 */
    fun openSettings() = Unit
    /** coin_bar 길게 누르기: 디버그 탭 켜고 끄기(디버그 빌드만) */
    fun toggleDebugTab() = Unit
    /** 이름 짓기 팝업에서 캐릭터 별명을 정한다 */
    fun nameCharacter(name: String) = Unit
}

/** 프로필 상태와 콘텐츠로 [HomeState] 를 만든다. 화면에 들어올 때 [refresh] 를 부른다 */
class HomeModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val profiles: ProfileStore,
    private val growth: GrowthModule,
    private val content: () -> ContentPack,
) {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    /** 지금 캐릭터의 별명을 [name] 으로 저장한다(앞뒤 공백을 뺀다). 캐릭터가 없으면 아무것도 하지 않는다 */
    fun setAlias(name: String) {
        val id = profiles.state(profileId).selected ?: return
        profiles.saveProgress(profiles.progress(profileId, id).copy(alias = name.trim()))
        refresh()
    }

    fun refresh() {
        _state.value = current()
    }

    /** 저장된 프로필로 지금 [HomeState] 를 만든다. 화면 상태([state])는 바꾸지 않는다 */
    fun current(): HomeState {
        val pack = content()
        val profile = profiles.state(profileId)
        val character = profile.selected?.let { id -> pack.characters.firstOrNull { it.id == id } }
        val progress = profile.selected?.let { profiles.progress(profileId, it) }
        return HomeState(
            characterName = progress?.alias?.ifBlank { null } ?: character?.name,
            needsName = progress != null && progress.alias.isBlank(),
            characterAssetKey = character?.assetKey,
            stage = progress?.stage ?: HomeState().stage,
            xp = progress?.xp ?: 0,
            stageProgress = progress?.let { growth.progress(it.xp) } ?: 0f,
            xpInStage = progress?.let { it.xp - growth.minXp(it.stage) } ?: 0,
            xpSpan = progress?.let { if (it.stage >= growth.maxStage) 0 else growth.minXp(it.stage + 1) - growth.minXp(it.stage) }
                ?: (growth.minXp(2) - growth.minXp(1)),
            coins = profile.coins,
            placed = StageLayout.views(pack, profile.placed, characterStage = progress?.stage ?: 1),
        )
    }
}

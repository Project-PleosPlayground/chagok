package ai.safetypoc.app.ui.profile

import core.character.CharacterDef
import core.character.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 차 한 대(앱 설치 하나)에 둘 수 있는 프로필 수 (스펙_개발스택 8절) */
const val MAX_PROFILES = 3

/** profile_card 하나가 보여 줄 값 */
data class ProfileCardItem(
    val id: String,
    val name: String,
    /** 고른 캐릭터의 그림 폴더 이름. 고르지 않았거나 목록에 없으면 null */
    val characterAssetKey: String?,
    /** 고른 캐릭터의 진화 단계 */
    val stage: Int,
)

/** 프로필 화면이 보여 줄 값 */
data class ProfileScreenState(
    /** 왼쪽부터 놓는 순서(만든 순서) */
    val profiles: List<ProfileCardItem> = emptyList(),
    /** 수정 모드 */
    val editing: Boolean = false,
) {
    /** add_button을 보일지. 프로필이 3개면 없다 */
    val canAdd: Boolean get() = profiles.size < MAX_PROFILES
    /** 프로필 관리 버튼을 보일지. 프로필이 1개 이상이고 수정 모드가 아닐 때 */
    val showEditButton: Boolean get() = !editing && profiles.isNotEmpty()
}

/** 프로필 화면에서 하는 동작 */
interface ProfileActions {
    /** profile_card를 눌렀다. 수정 모드면 그 프로필 수정, 아니면 그 프로필 선택 */
    fun tapProfile(profileId: String)
    /** add_button을 눌렀다 */
    fun tapAdd()
    /** 프로필 관리 버튼을 눌렀다 */
    fun startEdit()
    /** 수정 모드의 취소를 눌렀다 */
    fun endEdit()
}

/** 프로필 화면에서 다른 화면으로 가는 길. 화면 이동을 맡은 쪽이 구현한다 */
interface ProfileNavigation {
    fun selectProfile(profileId: String)
    fun addProfile()
    fun editProfile(profileId: String)
}

/** 저장된 프로필과 캐릭터 목록으로 [ProfileScreenState]를 만든다. 화면에 들어올 때 [refresh]를 부른다 */
class ProfileModel(
    private val profiles: ProfileStore,
    private val characters: () -> List<CharacterDef>,
    private val navigation: ProfileNavigation,
) : ProfileActions {
    private val _state = MutableStateFlow(ProfileScreenState())
    val state: StateFlow<ProfileScreenState> = _state.asStateFlow()

    /** 저장된 프로필을 다시 읽고 수정 모드를 끝낸다 */
    fun refresh() {
        val defs = characters()
        val items = profiles.profiles().sortedBy { it.createdMs }.map { profile ->
            val selected = profiles.state(profile.id).selected
            ProfileCardItem(
                id = profile.id,
                name = profile.name,
                characterAssetKey = selected?.let { id -> defs.firstOrNull { it.id == id }?.assetKey },
                stage = selected?.let { profiles.progress(profile.id, it).stage } ?: FIRST_STAGE,
            )
        }
        _state.value = ProfileScreenState(profiles = items)
    }

    override fun tapProfile(profileId: String) {
        if (_state.value.editing) navigation.editProfile(profileId) else navigation.selectProfile(profileId)
    }

    override fun tapAdd() {
        if (_state.value.canAdd) navigation.addProfile()
    }

    override fun startEdit() {
        _state.update { if (it.profiles.isEmpty()) it else it.copy(editing = true) }
    }

    override fun endEdit() {
        _state.update { it.copy(editing = false) }
    }

    private companion object {
        /** 캐릭터를 고르지 않은 프로필의 단계 (CharacterProgress 기본값) */
        const val FIRST_STAGE = 1
    }
}

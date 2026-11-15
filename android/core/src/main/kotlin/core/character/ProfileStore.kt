package core.character

/**
 * 프로필·캐릭터 저장소. 앱은 [core.store.DocumentProfileStore], 테스트는 InMemory 를 쓴다.
 * 프로필을 하나만 쓰기로 하면 Profile.DEFAULT 를 넘기면 된다.
 */
interface ProfileStore {
    fun profiles(): List<Profile>
    fun upsert(profile: Profile)

    /** 프로필 상태. 저장된 것이 없으면 기본값(고른 캐릭터 없음, 코인 0) */
    fun state(profileId: String): ProfileState
    fun saveState(state: ProfileState)

    /** 캐릭터별 성장. 저장된 것이 없으면 기본값(별칭 없음, 경험치 0, 1단계). 읽기만으로는 저장하지 않는다 */
    fun progress(profileId: String, characterId: CharacterId): CharacterProgress
    /** 이 프로필에서 저장된 캐릭터별 성장 전부 (키워 본 캐릭터) */
    fun progresses(profileId: String): List<CharacterProgress>
    fun saveProgress(progress: CharacterProgress)

    /** 프로필 하나와 그 프로필의 상태, 캐릭터별 성장을 지운다. 주행 기록과 받은 미션은 각 저장소의 deleteProfile 로 지운다 */
    fun delete(profileId: String)

    /** 프로필, 프로필 상태, 캐릭터별 성장을 전부 지운다. 세션과 받은 미션은 남는다 */
    fun clear()
}

class InMemoryProfileStore : ProfileStore {
    private val profiles = LinkedHashMap<String, Profile>()
    private val states = HashMap<String, ProfileState>()
    private val progress = LinkedHashMap<Pair<String, CharacterId>, CharacterProgress>()
    override fun profiles() = profiles.values.toList()
    override fun upsert(profile: Profile) { profiles[profile.id] = profile }
    override fun state(profileId: String) = states[profileId] ?: ProfileState(profileId)
    override fun saveState(state: ProfileState) { states[state.profileId] = state }
    override fun progress(profileId: String, characterId: CharacterId) =
        progress[profileId to characterId] ?: CharacterProgress(profileId, characterId)
    override fun progresses(profileId: String) = progress.values.filter { it.profileId == profileId }
    override fun saveProgress(progress: CharacterProgress) { this.progress[progress.profileId to progress.characterId] = progress }
    override fun delete(profileId: String) {
        profiles.remove(profileId); states.remove(profileId)
        progress.keys.removeAll { it.first == profileId }
    }
    override fun clear() { profiles.clear(); states.clear(); progress.clear() }
}

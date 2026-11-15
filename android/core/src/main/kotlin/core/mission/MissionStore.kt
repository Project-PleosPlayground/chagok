package core.mission

/**
 * 받은 미션 저장소. 앱은 [core.store.DocumentMissionStore], 테스트는 InMemory 를 쓴다.
 * 한 건의 키는 [MissionInstance.uid]. 프로필을 하나만 쓰기로 하면 Profile.DEFAULT 를 넘기면 된다.
 * MissionInstance 전체(목표·보상·이름 사본)를 저장한다. 콘텐츠가 바뀌어도 받은 미션의 조건이 바뀌지 않게 (#31).
 */
interface MissionStore {
    /** 이 프로필이 받은 미션 전부. 제공 시각순 */
    fun list(profileId: String): List<MissionInstance>
    fun upsert(instance: MissionInstance)
    /** 이 프로필이 받은 미션을 전부 지운다 (프로필 삭제) */
    fun deleteProfile(profileId: String)
}

class InMemoryMissionStore : MissionStore {
    private val all = ArrayList<MissionInstance>()
    override fun list(profileId: String) = all.filter { it.profileId == profileId }.sortedBy { it.offeredMs }
    override fun upsert(instance: MissionInstance) {
        val i = all.indexOfFirst { it.uid == instance.uid }
        if (i >= 0) all[i] = instance else all += instance
    }
    override fun deleteProfile(profileId: String) { all.removeAll { it.profileId == profileId } }
}

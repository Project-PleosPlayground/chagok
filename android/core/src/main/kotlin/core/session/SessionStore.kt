package core.session

/**
 * 세션 저장소. 앱은 [core.store.DocumentSessionStore], 테스트는 InMemory 를 쓴다.
 * 프로필 키를 받는다. 프로필을 하나만 쓰기로 하면 Profile.DEFAULT 를 넘기면 된다.
 */
interface SessionStore {
    fun save(record: SessionRecord)
    fun get(id: String): SessionRecord?
    fun list(profileId: String, fromWallMs: Long = 0, toWallMs: Long = Long.MAX_VALUE): List<SessionRecord>   // 시간순
    fun latest(profileId: String): SessionRecord?
    /** 이 프로필의 세션을 전부 지운다 (프로필 삭제) */
    fun deleteProfile(profileId: String)
}

class InMemorySessionStore : SessionStore {
    private val map = LinkedHashMap<String, SessionRecord>()
    override fun save(record: SessionRecord) { map[record.id] = record }
    override fun get(id: String) = map[id]
    override fun list(profileId: String, fromWallMs: Long, toWallMs: Long) =
        map.values.filter { it.profileId == profileId && it.startWallMs in fromWallMs..toWallMs }.sortedBy { it.startWallMs }
    override fun latest(profileId: String) = list(profileId).lastOrNull()
    override fun deleteProfile(profileId: String) { map.values.removeAll { it.profileId == profileId } }
}

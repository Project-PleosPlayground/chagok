package core.store

import core.character.CharacterId
import core.character.CharacterProgress
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.ProfileStore
import core.mission.InMemoryMissionStore
import core.mission.MissionInstance
import core.mission.MissionStore
import core.session.InMemorySessionStore
import core.session.SessionRecord
import core.session.SessionStore

/*
 * :core 저장소 인터페이스를 [DocumentStore] 에 저장하는 구현.
 * 만들 때 문서를 전부 읽어 메모리에 두고, 읽기는 메모리에서 한다. 쓰기는 메모리와 [DocumentStore] 에 같이 한다.
 * 읽지 못한 문서는 건너뛰고 [onSkip] 에 모음과 키를 알린다. 건너뛴 문서는 지우지 않고, 같은 키로 새로 쓰기 전에 원본을
 * [Collections.skipped] 모음에 옮겨 둔다. 앱이 그 키를 기본값으로 다시 써도(예: 처음 캐릭터 고르기) 원본이 남는다.
 */

/** 모음의 문서를 키 순서로 읽는다. 읽지 못한 문서는 원본을 옆 모음에 옮겨 두고 [onSkip] */
private fun <T> loadAll(docs: DocumentStore, collection: String, decode: (String) -> T?, onSkip: (String) -> Unit): List<T> =
    docs.readAll(collection).toSortedMap().mapNotNull { (key, document) ->
        decode(document) ?: run {
            docs.write(Collections.skipped(collection), key, document)
            onSkip("$collection/$key")
            null
        }
    }

/** 문서를 읽어 [add] 에 넘긴다 */
private fun <T> load(docs: DocumentStore, collection: String, decode: (String) -> T?, onSkip: (String) -> Unit, add: (T) -> Unit) =
    loadAll(docs, collection, decode, onSkip).forEach(add)

class DocumentProfileStore(
    private val docs: DocumentStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    onSkip: (String) -> Unit = {},
) : ProfileStore {
    private val mem = InMemoryProfileStore()

    init {
        load(docs, Collections.PROFILES, StoreCodec::decodeProfile, onSkip, mem::upsert)
        load(docs, Collections.PROFILE_STATES, StoreCodec::decodeProfileState, onSkip, mem::saveState)
        load(docs, Collections.CHARACTER_PROGRESS, StoreCodec::decodeProgress, onSkip, mem::saveProgress)
    }

    override fun profiles(): List<Profile> = mem.profiles()
    override fun upsert(profile: Profile) {
        mem.upsert(profile)
        docs.write(Collections.PROFILES, profile.id, StoreCodec.encode(profile, nowMs()))
    }
    override fun state(profileId: String): ProfileState = mem.state(profileId)
    override fun saveState(state: ProfileState) {
        mem.saveState(state)
        docs.write(Collections.PROFILE_STATES, state.profileId, StoreCodec.encode(state, nowMs()))
    }
    override fun progress(profileId: String, characterId: CharacterId): CharacterProgress = mem.progress(profileId, characterId)
    override fun progresses(profileId: String): List<CharacterProgress> = mem.progresses(profileId)
    override fun saveProgress(progress: CharacterProgress) {
        mem.saveProgress(progress)
        docs.write(Collections.CHARACTER_PROGRESS, progressKey(progress.profileId, progress.characterId), StoreCodec.encode(progress, nowMs()))
    }
    override fun delete(profileId: String) {
        val characters = mem.progresses(profileId).map { it.characterId }
        mem.delete(profileId)
        docs.delete(Collections.PROFILES, profileId)
        docs.delete(Collections.PROFILE_STATES, profileId)
        characters.forEach { docs.delete(Collections.CHARACTER_PROGRESS, progressKey(profileId, it)) }
    }
    override fun clear() {
        mem.clear()
        docs.deleteAll(Collections.PROFILES)
        docs.deleteAll(Collections.PROFILE_STATES)
        docs.deleteAll(Collections.CHARACTER_PROGRESS)
    }

    companion object {
        /** 캐릭터 성장 문서의 키. 프로필 하나에 캐릭터마다 하나 */
        fun progressKey(profileId: String, characterId: CharacterId) = "${profileId}_${characterId.value}"
    }
}

class DocumentSessionStore(
    private val docs: DocumentStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    onSkip: (String) -> Unit = {},
) : SessionStore {
    private val mem = InMemorySessionStore()

    init {
        load(docs, Collections.SESSIONS, StoreCodec::decodeSession, onSkip, mem::save)
    }

    override fun save(record: SessionRecord) {
        mem.save(record)
        docs.write(Collections.SESSIONS, record.id, StoreCodec.encode(record, nowMs()))
    }
    override fun get(id: String): SessionRecord? = mem.get(id)
    override fun list(profileId: String, fromWallMs: Long, toWallMs: Long): List<SessionRecord> = mem.list(profileId, fromWallMs, toWallMs)
    override fun latest(profileId: String): SessionRecord? = mem.latest(profileId)
    override fun deleteProfile(profileId: String) {
        val ids = mem.list(profileId).map { it.id }
        mem.deleteProfile(profileId)
        ids.forEach { docs.delete(Collections.SESSIONS, it) }
    }
}

class DocumentMissionStore(
    private val docs: DocumentStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
    onSkip: (String) -> Unit = {},
) : MissionStore {
    private val mem = InMemoryMissionStore()
    /**
     * 미션마다 처음 넣은 순서. 같은 시각에 제공된 미션은 넣은 순서로 보인다(InMemoryMissionStore).
     * 문서에 같이 저장해, 다시 켠 뒤에도 그 순서로 메모리에 넣는다.
     */
    private val seqs = HashMap<String, Long>()
    private var nextSeq = 0L

    init {
        loadAll(docs, Collections.MISSIONS, StoreCodec::decodeMissionEntry, onSkip)
            .sortedBy { (_, seq) -> seq }
            .forEach { (instance, seq) ->
                mem.upsert(instance)
                seqs[instance.uid] = seq
                nextSeq = maxOf(nextSeq, seq + 1)
            }
    }

    override fun list(profileId: String): List<MissionInstance> = mem.list(profileId)
    override fun upsert(instance: MissionInstance) {
        mem.upsert(instance)
        val seq = seqs.getOrPut(instance.uid) { nextSeq++ }
        docs.write(Collections.MISSIONS, instance.uid, StoreCodec.encode(instance, nowMs(), seq))
    }
    override fun deleteProfile(profileId: String) {
        val uids = mem.list(profileId).map { it.uid }
        mem.deleteProfile(profileId)
        uids.forEach { seqs.remove(it); docs.delete(Collections.MISSIONS, it) }
    }
}

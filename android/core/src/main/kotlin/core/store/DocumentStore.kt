package core.store

/**
 * 저장 매체. 모음(collection) 안에 키 → JSON 문서를 둔다. 프로필·세션·미션 저장소([DocumentProfileStore] 등)가 이것을 쓴다.
 * :app 은 기기 파일로 구현한다(`FileDocumentStore`). 테스트는 [InMemoryDocumentStore].
 *
 * 문서 하나는 저장소의 레코드 하나다(프로필 하나, 세션 하나, 받은 미션 하나). 문서는 [StoreCodec] 의 봉투
 * (스키마 버전, 마지막 수정 시각)에 싸여 있다. 원격 저장을 더할 때는 이 인터페이스를 하나 더 구현하거나
 * 로컬 구현을 감싸 같은 (모음, 키, 문서)를 서버에도 보내면 된다. 저장소와 화면 코드는 바꾸지 않는다.
 */
interface DocumentStore {
    /** 모음의 문서 전부. 키 → 문서. 없으면 빈 맵 */
    fun readAll(collection: String): Map<String, String>
    /** 문서를 넣거나 바꾼다 */
    fun write(collection: String, key: String, document: String)
    fun delete(collection: String, key: String)
    /** 모음의 문서를 전부 지운다 */
    fun deleteAll(collection: String)
}

/** 메모리 구현. 앱을 끄면 사라진다 */
class InMemoryDocumentStore : DocumentStore {
    private val data = HashMap<String, LinkedHashMap<String, String>>()
    override fun readAll(collection: String): Map<String, String> = data[collection]?.toMap() ?: emptyMap()
    override fun write(collection: String, key: String, document: String) { data.getOrPut(collection) { LinkedHashMap() }[key] = document }
    override fun delete(collection: String, key: String) { data[collection]?.remove(key) }
    override fun deleteAll(collection: String) { data.remove(collection) }
}

/** 모음 이름. 원격 저장에서도 같은 이름을 쓴다. 이름을 바꾸면 저장된 데이터를 못 읽는다 */
object Collections {
    const val PROFILES = "profiles"
    const val PROFILE_STATES = "profile_states"
    const val CHARACTER_PROGRESS = "character_progress"
    const val SESSIONS = "sessions"
    const val MISSIONS = "missions"
    val ALL = listOf(PROFILES, PROFILE_STATES, CHARACTER_PROGRESS, SESSIONS, MISSIONS)

    /** 읽지 못한 문서의 원본을 옮겨 두는 모음. 저장소는 읽지 않는다 */
    fun skipped(collection: String) = "${collection}_skipped"
}

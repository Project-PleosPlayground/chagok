package core.store

import core.character.CharacterId
import core.character.CharacterProgress
import core.character.Profile
import core.character.ProfileState
import core.item.ItemId
import core.item.StageItem
import core.item.StagePoint
import core.mission.ConditionType
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.EventRecord
import core.session.SessionRecord
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 저장 문서의 JSON. 도메인 객체를 그대로 직렬화하지 않고 저장용 모양(`*Doc`)으로 옮긴다.
 * 도메인 클래스를 고쳐도 저장된 문서를 읽을 수 있게, 원격 저장에서도 같은 모양을 쓰게 하려는 것이다.
 *
 * 문서는 [Envelope] 에 싸인다. [Envelope.schema] 는 저장 모양의 버전, [Envelope.updatedMs] 는 마지막으로 쓴 시각이다.
 * 저장 모양을 바꾸면 [SCHEMA] 를 올리고 이전 버전을 옮기는 코드를 [migrate] 에 더한다.
 * 이 앱보다 새 버전이 쓴 문서([SCHEMA] 보다 큰 schema)는 읽지 않는다. 읽고 다시 쓰면 새 필드가 사라지기 때문이다.
 * 종류 enum 은 이름으로, 미션 조건은 조건ID 로, 캐릭터·아이템은 번호로 저장한다(스펙_개발스택 3절 `:core` 패키지).
 */
object StoreCodec {
    const val SCHEMA = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Envelope<T>(val schema: Int, val updatedMs: Long, val data: T)

    fun <T> encode(serializer: KSerializer<T>, value: T, nowMs: Long): String =
        json.encodeToString(Envelope.serializer(serializer), Envelope(SCHEMA, nowMs, value))

    /** 읽지 못하면(깨진 문서, 모르는 값, 새 버전 문서) null */
    fun <T> decode(serializer: KSerializer<T>, document: String): T? = runCatching {
        val envelope = json.parseToJsonElement(document).jsonObject
        val schema = envelope[SCHEMA_FIELD]?.jsonPrimitive?.int ?: return null
        val data = envelope[DATA_FIELD] ?: return null
        migrate(schema, data)?.let { json.decodeFromJsonElement(serializer, it) }
    }.getOrNull()

    /**
     * [schema] 버전의 data 를 지금 저장 모양으로 옮긴다. 옮길 수 없으면(새 버전, 모르는 버전) null.
     * 저장 모양을 바꿀 때 이전 버전마다 한 단계씩 옮기는 분기를 여기에 더한다.
     */
    private fun migrate(schema: Int, data: JsonElement): JsonElement? = when (schema) {
        SCHEMA -> data
        else -> null
    }

    private const val SCHEMA_FIELD = "schema"
    private const val DATA_FIELD = "data"

    // 프로필

    @Serializable
    data class ProfileDoc(val id: String, val name: String, val createdMs: Long)

    @Serializable
    data class StageItemDoc(val itemId: Int, val x: Float? = null, val y: Float? = null)

    @Serializable
    data class ProfileStateDoc(
        val profileId: String,
        val selected: Int? = null,
        val coins: Int = 0,
        val owned: List<Int> = emptyList(),
        val placed: List<StageItemDoc> = emptyList(),
    )

    @Serializable
    data class ProgressDoc(val profileId: String, val characterId: Int, val alias: String = "", val xp: Int = 0, val stage: Int = 1)

    fun doc(p: Profile) = ProfileDoc(p.id, p.name, p.createdMs)
    fun domain(d: ProfileDoc) = Profile(d.id, d.name, d.createdMs)

    fun doc(s: ProfileState) = ProfileStateDoc(
        s.profileId, s.selected?.value, s.coins, s.owned.map { it.value },
        s.placed.map { StageItemDoc(it.itemId.value, it.position?.x, it.position?.y) },
    )
    fun domain(d: ProfileStateDoc) = ProfileState(
        d.profileId, d.selected?.let(::CharacterId), d.coins, d.owned.map(::ItemId).toSet(),
        d.placed.map { StageItem(ItemId(it.itemId), if (it.x != null && it.y != null) StagePoint(it.x, it.y) else null) },
    )

    fun doc(p: CharacterProgress) = ProgressDoc(p.profileId, p.characterId.value, p.alias, p.xp, p.stage)
    fun domain(d: ProgressDoc) = CharacterProgress(d.profileId, CharacterId(d.characterId), d.alias, d.xp, d.stage)

    // 세션

    @Serializable
    data class EventDoc(
        val type: String, val severity: Int, val startMs: Long, val endMs: Long, val speedMps: Float,
        val fromStop: Boolean = false, val detail: String = "",
    )

    @Serializable
    data class EventRecordDoc(
        val event: EventDoc, val distanceFromStartM: Float? = null, val roadName: String? = null,
        val lat: Double? = null, val lng: Double? = null,
    )

    @Serializable
    data class ResultDoc(
        val startMs: Long, val endMs: Long, val distanceKm: Float, val events: List<EventDoc>,
        val score: Int, val accelScore: Int, val brakeScore: Int, val mannerScore: Int, val valid: Boolean, val paramsVersion: Int,
    )

    @Serializable
    data class SessionDoc(
        val id: String, val profileId: String, val result: ResultDoc, val events: List<EventRecordDoc>,
        val startWallMs: Long, val endWallMs: Long,
        val origin: String? = null, val destination: String? = null,
        val evSocStartPct: Int? = null, val evSocEndPct: Int? = null,
    )

    private fun doc(e: DrivingEvent) = EventDoc(e.type.name, e.severity, e.startMs, e.endMs, e.speedMps, e.fromStop, e.detail)
    private fun domain(d: EventDoc) = DrivingEvent(EventType.valueOf(d.type), d.severity, d.startMs, d.endMs, d.speedMps, d.fromStop, d.detail)

    fun doc(r: SessionRecord) = SessionDoc(
        r.id, r.profileId,
        r.result.let { ResultDoc(it.startMs, it.endMs, it.distanceKm, it.events.map(::doc), it.score, it.accelScore, it.brakeScore, it.mannerScore, it.valid, it.paramsVersion) },
        r.events.map { EventRecordDoc(doc(it.event), it.distanceFromStartM, it.roadName, it.lat, it.lng) },
        r.startWallMs, r.endWallMs, r.origin, r.destination, r.evSocStartPct, r.evSocEndPct,
    )
    /** 모르는 이벤트 종류가 있으면 예외. [decodeSession] 이 null 로 바꾼다 */
    fun domain(d: SessionDoc) = SessionRecord(
        d.id, d.profileId,
        d.result.let { SessionResult(it.startMs, it.endMs, it.distanceKm, it.events.map(::domain), it.score, it.accelScore, it.brakeScore, it.mannerScore, it.valid, it.paramsVersion) },
        d.events.map { EventRecord(domain(it.event), it.distanceFromStartM, it.roadName, it.lat, it.lng) },
        d.startWallMs, d.endWallMs, d.origin, d.destination, d.evSocStartPct, d.evSocEndPct,
    )

    // 미션

    @Serializable
    data class MissionDoc(
        val uid: String, val profileId: String, val kind: MissionKind, val conditionId: Int, val tier: Int, val target: Float,
        val rewardCoins: Int, val rewardXp: Int, val title: String, val description: String, val iconKey: String? = null,
        val offeredMs: Long, val validUntilMs: Long, val status: String,
        val challengedMs: Long? = null, val endedMs: Long? = null, val clearedMs: Long? = null,
        val counted: Float? = null, val drives: Int = 0,
        /** 이 저장소에 처음 넣은 순서. 같은 시각에 제공된 미션의 순서를 다시 켠 뒤에도 지킨다 */
        val seq: Long = 0,
    )

    fun doc(m: MissionInstance, seq: Long = 0) = MissionDoc(
        m.uid, m.profileId, m.kind, m.condition.id, m.tier, m.target, m.rewardCoins, m.rewardXp, m.title, m.description, m.iconKey,
        m.offeredMs, m.validUntilMs, m.status.name, m.challengedMs, m.endedMs, m.clearedMs, m.counted, m.drives, seq,
    )
    /** 모르는 조건ID·상태면 예외. [decodeMission] 이 null 로 바꾼다 */
    fun domain(d: MissionDoc) = MissionInstance(
        d.uid, d.profileId, d.kind, requireNotNull(ConditionType.byId(d.conditionId)) { "조건ID ${d.conditionId}" },
        d.tier, d.target, d.rewardCoins, d.rewardXp, d.title, d.description, d.iconKey,
        d.offeredMs, d.validUntilMs, MissionStatus.valueOf(d.status), d.challengedMs, d.endedMs, d.clearedMs, d.counted, d.drives,
    )

    // 문서 ↔ 도메인. 읽지 못하면 null

    fun encode(p: Profile, nowMs: Long) = encode(ProfileDoc.serializer(), doc(p), nowMs)
    fun encode(s: ProfileState, nowMs: Long) = encode(ProfileStateDoc.serializer(), doc(s), nowMs)
    fun encode(p: CharacterProgress, nowMs: Long) = encode(ProgressDoc.serializer(), doc(p), nowMs)
    fun encode(r: SessionRecord, nowMs: Long) = encode(SessionDoc.serializer(), doc(r), nowMs)
    fun encode(m: MissionInstance, nowMs: Long, seq: Long = 0) = encode(MissionDoc.serializer(), doc(m, seq), nowMs)

    fun decodeProfile(document: String): Profile? = decode(ProfileDoc.serializer(), document)?.let(::domain)
    fun decodeProfileState(document: String): ProfileState? = decode(ProfileStateDoc.serializer(), document)?.let(::domain)
    fun decodeProgress(document: String): CharacterProgress? = decode(ProgressDoc.serializer(), document)?.let(::domain)
    fun decodeSession(document: String): SessionRecord? =
        decode(SessionDoc.serializer(), document)?.let { runCatching { domain(it) }.getOrNull() }
    fun decodeMission(document: String): MissionInstance? =
        decode(MissionDoc.serializer(), document)?.let { runCatching { domain(it) }.getOrNull() }
    /** 미션과 넣은 순서([MissionDoc.seq]) */
    fun decodeMissionEntry(document: String): Pair<MissionInstance, Long>? =
        decode(MissionDoc.serializer(), document)?.let { d -> runCatching { domain(d) to d.seq }.getOrNull() }
}

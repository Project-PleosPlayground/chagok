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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.int
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 문서 저장소 기반 스토어 블랙박스 테스트. 계약(약속한 동작)만 보고 쓴다 */
class StoreBlackBoxTest {

    // ---- 도우미 ----

    private class Clock(var now: Long = 1_000L) { val fn: () -> Long = { now } }

    private fun profileState() = ProfileState(
        profileId = "p1",
        selected = CharacterId(3),
        coins = 1234,
        owned = setOf(ItemId(1), ItemId(7), ItemId(42)),
        placed = listOf(
            StageItem(ItemId(1)),                              // 위치 없음
            StageItem(ItemId(7), StagePoint(12.5f, -3.25f)),   // 위치 있음
            StageItem(ItemId(42), StagePoint(0f, 0.1f)),
        ),
    )

    private fun ev(type: EventType, at: Long, sev: Int = 1, fromStop: Boolean = false, detail: String = "") =
        DrivingEvent(type, sev, at, at + 700, 13.37f, fromStop, detail)

    private fun session(
        id: String, start: Long, profile: String = "p1", full: Boolean = true,
    ): SessionRecord {
        val events = listOf(
            ev(EventType.HARSH_BRAKE, 5_000, sev = 2, detail = "max a=-2.4m/s², pedal=true"),
            ev(EventType.HARSH_ACCEL, 9_000, fromStop = true),
            ev(EventType.LANE_CHANGE_SIGNALED, 12_000),
        )
        val result = SessionResult(
            startMs = 100, endMs = 600_100, distanceKm = 12.345f, events = events,
            score = 87, accelScore = 90, brakeScore = 80, mannerScore = 95, valid = true, paramsVersion = 7,
        )
        val recs = if (full) listOf(
            EventRecord(events[0], 1234.5f, "테헤란로", 37.5012345678, 127.0398765432),
            EventRecord(events[1]),                                      // 메타 전부 null
            EventRecord(events[2], 0f, null, -33.9, 151.2),
        ) else events.map { EventRecord(it) }
        return SessionRecord(
            id = id, profileId = profile, result = result, events = recs,
            startWallMs = start, endWallMs = start + 600_000,
            origin = if (full) "서울역" else null,
            destination = if (full) "강남역" else null,
            evSocStartPct = if (full) 80 else null,
            evSocEndPct = if (full) 72 else null,
        )
    }

    private fun mission(
        uid: String, offered: Long, profile: String = "p1",
        kind: MissionKind = MissionKind.PERSONAL,
        condition: ConditionType = ConditionType.HARSH_BRAKE_MAX,
        status: MissionStatus = MissionStatus.OFFERED,
        full: Boolean = false,
    ) = MissionInstance(
        uid = uid, profileId = profile, kind = kind, condition = condition, tier = 2, target = 1.5f,
        rewardCoins = 30, rewardXp = 15, title = "급제동 {n}회 이하", description = "설명 \"따옴표\"\n줄바꿈",
        iconKey = if (full) "brake_icon" else null,
        offeredMs = offered, validUntilMs = offered + 86_400_000L, status = status,
        challengedMs = if (full) offered + 10 else null,
        endedMs = if (full) offered + 20 else null,
        clearedMs = if (full) offered + 30 else null,
        counted = if (full) 0.75f else null,
        drives = if (full) 3 else 0,
    )

    private fun envelope(doc: String): JsonObject = Json.parseToJsonElement(doc).jsonObject

    /** data 안에서 조건을 만족하는 값을 바꾼다 */
    private fun rewrite(el: JsonElement, f: (key: String?, v: JsonElement) -> JsonElement?, key: String? = null): JsonElement {
        f(key, el)?.let { return it }
        return when (el) {
            is JsonObject -> JsonObject(el.mapValues { (k, v) -> rewrite(v, f, k) })
            is JsonArray -> JsonArray(el.map { rewrite(it, f, null) })
            else -> el
        }
    }

    private fun rewriteDoc(doc: String, f: (key: String?, v: JsonElement) -> JsonElement?): String {
        val env = envelope(doc)
        val changed = JsonObject(env.mapValues { (k, v) -> if (k == "data") rewrite(v, f) else v })
        assertNotEquals(env, changed, "바꿀 값을 문서에서 찾지 못했다")
        return changed.toString()
    }

    // ---- 상수 ----

    @Test fun `컬렉션 이름과 ALL`() {
        assertEquals("profiles", Collections.PROFILES)
        assertEquals("profile_states", Collections.PROFILE_STATES)
        assertEquals("character_progress", Collections.CHARACTER_PROGRESS)
        assertEquals("sessions", Collections.SESSIONS)
        assertEquals("missions", Collections.MISSIONS)
        assertEquals(
            setOf("profiles", "profile_states", "character_progress", "sessions", "missions"),
            Collections.ALL.toSet(),
        )
        assertEquals(5, Collections.ALL.size)
    }

    @Test fun `스키마 번호는 1`() {
        assertEquals(1, StoreCodec.SCHEMA)
    }

    // ---- InMemoryDocumentStore ----

    @Test fun `메모리 문서 저장소 쓰기 읽기 지우기`() {
        val d = InMemoryDocumentStore()
        assertTrue(d.readAll("x").isEmpty())
        d.write("x", "a", "1")
        d.write("x", "b", "2")
        d.write("y", "a", "9")
        d.write("x", "a", "1b")   // 덮어쓰기
        assertEquals(mapOf("a" to "1b", "b" to "2"), d.readAll("x"))
        d.delete("x", "a")
        d.delete("x", "없는키")   // 없는 키를 지워도 예외 없음
        assertEquals(mapOf("b" to "2"), d.readAll("x"))
        d.deleteAll("x")
        assertTrue(d.readAll("x").isEmpty())
        assertEquals(mapOf("a" to "9"), d.readAll("y"))
        d.deleteAll("없는컬렉션")
    }

    // ---- 빈 저장소 ----

    @Test fun `빈 문서 저장소면 빈 스토어`() {
        val d = InMemoryDocumentStore()
        val p = DocumentProfileStore(d)
        assertTrue(p.profiles().isEmpty())
        assertEquals(ProfileState("p1"), p.state("p1"))
        assertEquals(CharacterProgress("p1", CharacterId(1)), p.progress("p1", CharacterId(1)))
        assertTrue(p.progresses("p1").isEmpty())
        val s = DocumentSessionStore(d)
        assertNull(s.get("s1"))
        assertTrue(s.list("p1").isEmpty())
        assertNull(s.latest("p1"))
        assertTrue(DocumentMissionStore(d).list("p1").isEmpty())
        Collections.ALL.forEach { assertTrue(d.readAll(it).isEmpty(), it) }
    }

    // ---- 프로필 왕복 ----

    @Test fun `프로필 상태 성장 왕복`() {
        val d = InMemoryDocumentStore()
        val a = DocumentProfileStore(d)
        val profiles = listOf(Profile("p1", "첫째", 111L), Profile("p2", "둘째 \"이름\"", 222L))
        profiles.forEach(a::upsert)
        val st1 = profileState()
        val st2 = ProfileState("p2")   // 고른 캐릭터 null, 빈 집합
        a.saveState(st1); a.saveState(st2)
        val pr = listOf(
            CharacterProgress("p1", CharacterId(1), "뭉치", 250, 3),
            CharacterProgress("p1", CharacterId(2), "", 0, 1),
            CharacterProgress("p2", CharacterId(1), "한글 별칭 ✓", 9999, 5),
        )
        pr.forEach(a::saveProgress)

        val b = DocumentProfileStore(d)
        assertEquals(profiles.toSet(), b.profiles().toSet())
        assertEquals(2, b.profiles().size)
        assertEquals(st1, b.state("p1"))
        assertEquals(st1.placed, b.state("p1").placed)   // 순서 유지
        assertEquals(st2, b.state("p2"))
        assertNull(b.state("p2").selected)
        assertEquals(pr[0], b.progress("p1", CharacterId(1)))
        assertEquals(pr[1], b.progress("p1", CharacterId(2)))
        assertEquals(pr[2], b.progress("p2", CharacterId(1)))
        assertEquals(setOf(pr[0], pr[1]), b.progresses("p1").toSet())
        assertEquals(listOf(pr[2]), b.progresses("p2"))
        // 저장 안 된 건 기본값
        assertEquals(ProfileState("p3"), b.state("p3"))
        assertEquals(CharacterProgress("p2", CharacterId(2)), b.progress("p2", CharacterId(2)))
    }

    @Test fun `프로필 문서는 레코드마다 하나`() {
        val d = InMemoryDocumentStore()
        val a = DocumentProfileStore(d)
        a.upsert(Profile("p1", "a", 1)); a.upsert(Profile("p1", "b", 2)); a.upsert(Profile("p2", "c", 3))
        a.saveState(ProfileState("p1", coins = 1)); a.saveState(ProfileState("p1", coins = 2))
        a.saveProgress(CharacterProgress("p1", CharacterId(1), xp = 1))
        a.saveProgress(CharacterProgress("p1", CharacterId(1), xp = 2))
        a.saveProgress(CharacterProgress("p1", CharacterId(2)))
        a.saveProgress(CharacterProgress("p2", CharacterId(1)))
        assertEquals(setOf("p1", "p2"), d.readAll(Collections.PROFILES).keys)
        assertEquals(setOf("p1"), d.readAll(Collections.PROFILE_STATES).keys)
        assertEquals(3, d.readAll(Collections.CHARACTER_PROGRESS).size)
        // 메모리와 새 인스턴스 모두 마지막 값
        assertEquals(Profile("p1", "b", 2), a.profiles().first { it.id == "p1" })
        val b = DocumentProfileStore(d)
        assertEquals(Profile("p1", "b", 2), b.profiles().first { it.id == "p1" })
        assertEquals(2, b.state("p1").coins)
        assertEquals(2, b.progress("p1", CharacterId(1)).xp)
    }

    @Test fun `성장을 읽기만 하면 문서를 쓰지 않는다`() {
        val d = InMemoryDocumentStore()
        val a = DocumentProfileStore(d)
        a.progress("p1", CharacterId(1)); a.state("p1"); a.profiles(); a.progresses("p1")
        Collections.ALL.forEach { assertTrue(d.readAll(it).isEmpty(), it) }
    }

    @Test fun `쓰기는 메모리에도 바로 반영`() {
        val d = InMemoryDocumentStore()
        val a = DocumentProfileStore(d)
        a.saveState(profileState())
        assertEquals(profileState(), a.state("p1"))
        val s = DocumentSessionStore(d)
        s.save(session("s1", 10))
        assertEquals(session("s1", 10), s.get("s1"))
        val m = DocumentMissionStore(d)
        m.upsert(mission("m1", 10))
        assertEquals(listOf(mission("m1", 10)), m.list("p1"))
    }

    @Test fun `다시 연 스토어에 이어 쓰면 기존 것도 남는다`() {
        val d = InMemoryDocumentStore()
        DocumentProfileStore(d).upsert(Profile("p1", "a", 1))
        DocumentProfileStore(d).upsert(Profile("p2", "b", 2))
        assertEquals(setOf("p1", "p2"), DocumentProfileStore(d).profiles().map { it.id }.toSet())
    }

    // ---- clear ----

    @Test fun `clear 는 프로필 세 컬렉션만 비운다`() {
        val d = InMemoryDocumentStore()
        val p = DocumentProfileStore(d)
        p.upsert(Profile("p1", "a", 1))
        p.saveState(profileState())
        p.saveProgress(CharacterProgress("p1", CharacterId(1), xp = 5))
        DocumentSessionStore(d).save(session("s1", 10))
        DocumentMissionStore(d).upsert(mission("m1", 10))

        p.clear()

        assertTrue(p.profiles().isEmpty())
        assertEquals(ProfileState("p1"), p.state("p1"))
        assertEquals(CharacterProgress("p1", CharacterId(1)), p.progress("p1", CharacterId(1)))
        assertTrue(p.progresses("p1").isEmpty())
        assertTrue(d.readAll(Collections.PROFILES).isEmpty())
        assertTrue(d.readAll(Collections.PROFILE_STATES).isEmpty())
        assertTrue(d.readAll(Collections.CHARACTER_PROGRESS).isEmpty())
        assertEquals(1, d.readAll(Collections.SESSIONS).size)
        assertEquals(1, d.readAll(Collections.MISSIONS).size)

        val again = DocumentProfileStore(d)
        assertTrue(again.profiles().isEmpty())
        assertEquals(ProfileState("p1"), again.state("p1"))
        assertEquals(session("s1", 10), DocumentSessionStore(d).get("s1"))
        assertEquals(listOf(mission("m1", 10)), DocumentMissionStore(d).list("p1"))
    }

    @Test fun `clear 뒤에 다시 쓸 수 있다`() {
        val d = InMemoryDocumentStore()
        val p = DocumentProfileStore(d)
        p.upsert(Profile("p1", "a", 1)); p.clear()
        p.upsert(Profile("p2", "b", 2))
        assertEquals(listOf(Profile("p2", "b", 2)), DocumentProfileStore(d).profiles())
    }

    // ---- 세션 왕복 ----

    @Test fun `세션 왕복 모든 필드`() {
        val d = InMemoryDocumentStore()
        val a = DocumentSessionStore(d)
        val full = session("s1", 5_000)
        val bare = session("s2", 6_000, full = false)
        a.save(full); a.save(bare)
        val b = DocumentSessionStore(d)
        assertEquals(full, b.get("s1"))
        assertEquals(bare, b.get("s2"))
        // 위치 메타 세부
        val e0 = b.get("s1")!!.events[0]
        assertEquals(37.5012345678, e0.lat!!, 0.0)
        assertEquals(127.0398765432, e0.lng!!, 0.0)
        assertEquals("테헤란로", e0.roadName)
        assertEquals(1234.5f, e0.distanceFromStartM)
        assertNull(b.get("s1")!!.events[1].lat)
        assertEquals(7, b.get("s1")!!.result.paramsVersion)
        assertTrue(b.get("s1")!!.result.events[1].fromStop)
        assertEquals("max a=-2.4m/s², pedal=true", b.get("s1")!!.result.events[0].detail)
    }

    @Test fun `세션 시간순 정렬, 기간, 최신, 프로필 구분`() {
        val d = InMemoryDocumentStore()
        val a = DocumentSessionStore(d)
        a.save(session("c", 3_000))
        a.save(session("a", 1_000))
        a.save(session("x", 2_500, profile = "p2"))
        a.save(session("b", 2_000))
        for (s in listOf(a, DocumentSessionStore(d))) {
            assertEquals(listOf("a", "b", "c"), s.list("p1").map { it.id })
            assertEquals(listOf("b", "c"), s.list("p1", 2_000, 3_000).map { it.id })
            assertEquals(listOf("b"), s.list("p1", 1_500, 2_999).map { it.id })
            assertEquals("c", s.latest("p1")!!.id)
            assertEquals(listOf("x"), s.list("p2").map { it.id })
            assertNull(s.latest("p3"))
        }
    }

    @Test fun `세션 같은 id 저장은 덮어쓰고 문서 하나`() {
        val d = InMemoryDocumentStore()
        val a = DocumentSessionStore(d)
        a.save(session("s1", 1_000))
        a.save(session("s1", 9_000, full = false))
        a.save(session("s2", 2_000))
        assertEquals(setOf("s1", "s2"), d.readAll(Collections.SESSIONS).keys)
        assertEquals(session("s1", 9_000, full = false), DocumentSessionStore(d).get("s1"))
        assertEquals(listOf("s2", "s1"), DocumentSessionStore(d).list("p1").map { it.id })
    }

    // ---- 미션 왕복 ----

    @Test fun `미션 왕복 모든 상태 조건 종류 선택 필드`() {
        val d = InMemoryDocumentStore()
        val a = DocumentMissionStore(d)
        val all = ArrayList<MissionInstance>()
        var t = 1_000L
        MissionStatus.entries.forEachIndexed { i, st ->
            all += mission("st-$i", t++, status = st, full = i % 2 == 0)
        }
        ConditionType.entries.forEachIndexed { i, c ->
            all += mission("c-$i", t++, condition = c, kind = if (i % 2 == 0) MissionKind.WEEKLY else MissionKind.PERSONAL, full = i % 2 == 1)
        }
        all.shuffled(java.util.Random(1)).forEach(a::upsert)
        val b = DocumentMissionStore(d)
        assertEquals(all, b.list("p1"))   // 제공 시각순
        assertEquals(all.size, d.readAll(Collections.MISSIONS).size)
        assertEquals(all.map { it.uid }.toSet(), d.readAll(Collections.MISSIONS).keys)
    }

    @Test fun `미션 upsert 는 uid 로 바꾸고 프로필로 나눈다`() {
        val d = InMemoryDocumentStore()
        val a = DocumentMissionStore(d)
        a.upsert(mission("m2", 2_000))
        a.upsert(mission("m1", 1_000))
        a.upsert(mission("m3", 1_500, profile = "p2"))
        val done = mission("m2", 2_000, status = MissionStatus.SUCCEEDED, full = true)
        a.upsert(done)
        for (s in listOf(a, DocumentMissionStore(d))) {
            assertEquals(listOf("m1", "m2"), s.list("p1").map { it.uid })
            assertEquals(done, s.list("p1")[1])
            assertEquals(listOf("m3"), s.list("p2").map { it.uid })
        }
        assertEquals(3, d.readAll(Collections.MISSIONS).size)
    }

    // ---- 봉투 ----

    @Test fun `문서 봉투에 schema, updatedMs, data`() {
        val d = InMemoryDocumentStore()
        val clock = Clock(1_000)
        val p = DocumentProfileStore(d, clock.fn)
        val s = DocumentSessionStore(d, clock.fn)
        val m = DocumentMissionStore(d, clock.fn)
        clock.now = 11; p.upsert(Profile("p1", "a", 1))
        clock.now = 22; p.saveState(ProfileState("p1"))
        clock.now = 33; p.saveProgress(CharacterProgress("p1", CharacterId(1)))
        clock.now = 44; s.save(session("s1", 5))
        clock.now = 55; m.upsert(mission("m1", 5))
        val expected = mapOf(
            Collections.PROFILES to 11L, Collections.PROFILE_STATES to 22L, Collections.CHARACTER_PROGRESS to 33L,
            Collections.SESSIONS to 44L, Collections.MISSIONS to 55L,
        )
        for ((col, ms) in expected) {
            val docs = d.readAll(col)
            assertEquals(1, docs.size, col)
            val env = envelope(docs.values.single())
            assertEquals(1, env["schema"]!!.jsonPrimitive.int, col)
            assertEquals(ms, env["updatedMs"]!!.jsonPrimitive.long, col)
            assertTrue(env["data"] is JsonObject, col)
        }
        // 다시 쓰면 updatedMs 가 바뀐다
        clock.now = 99; p.upsert(Profile("p1", "b", 1))
        assertEquals(99L, envelope(d.readAll(Collections.PROFILES)["p1"]!!)["updatedMs"]!!.jsonPrimitive.long)
    }

    // ---- 못 읽는 문서 ----

    @Test fun `깨진 문서는 건너뛰고 알리고 지우지 않는다`() {
        val d = InMemoryDocumentStore()
        DocumentProfileStore(d).apply {
            upsert(Profile("p1", "a", 1)); saveState(profileState()); saveProgress(CharacterProgress("p1", CharacterId(1), xp = 3))
        }
        DocumentSessionStore(d).save(session("s1", 10))
        DocumentMissionStore(d).upsert(mission("m1", 10))
        val garbage = "이건 JSON 이 아니다 {"
        Collections.ALL.forEach { d.write(it, "bad-$it", garbage) }

        val skipped = ArrayList<String>()
        val p = DocumentProfileStore(d, onSkip = { skipped += it })
        val s = DocumentSessionStore(d, onSkip = { skipped += it })
        val m = DocumentMissionStore(d, onSkip = { skipped += it })

        assertEquals(listOf(Profile("p1", "a", 1)), p.profiles())
        assertEquals(profileState(), p.state("p1"))
        assertEquals(3, p.progress("p1", CharacterId(1)).xp)
        assertEquals(1, p.progresses("p1").size)
        assertEquals(session("s1", 10), s.get("s1"))
        assertEquals(listOf("s1"), s.list("p1").map { it.id })
        assertEquals(listOf(mission("m1", 10)), m.list("p1"))

        for (col in Collections.ALL) {
            assertTrue(skipped.any { it.contains(col) && it.contains("bad-$col") }, "onSkip 에 $col / bad-$col 없음: $skipped")
            assertEquals(garbage, d.readAll(col)["bad-$col"], "깨진 문서가 지워졌다: $col")
        }
        assertEquals(Collections.ALL.size, skipped.size)
    }

    @Test fun `data 없는 봉투도 건너뛴다`() {
        val d = InMemoryDocumentStore()
        DocumentProfileStore(d).upsert(Profile("p1", "a", 1))
        d.write(Collections.PROFILES, "p9", "{}")
        val skipped = ArrayList<String>()
        val p = DocumentProfileStore(d, onSkip = { skipped += it })
        assertEquals(listOf(Profile("p1", "a", 1)), p.profiles())
        assertTrue(skipped.any { it.contains(Collections.PROFILES) && it.contains("p9") }, "$skipped")
        assertEquals("{}", d.readAll(Collections.PROFILES)["p9"])
    }

    @Test fun `모르는 미션 상태 값은 건너뛴다`() {
        val d = InMemoryDocumentStore()
        val a = DocumentMissionStore(d)
        a.upsert(mission("ok", 1_000))
        a.upsert(mission("bad", 2_000, status = MissionStatus.IN_PROGRESS))
        val bad = rewriteDoc(d.readAll(Collections.MISSIONS)["bad"]!!) { _, v ->
            if (v is JsonPrimitive && v.isString && v.content == "IN_PROGRESS") JsonPrimitive("NO_SUCH_STATUS") else null
        }
        d.write(Collections.MISSIONS, "bad", bad)
        val skipped = ArrayList<String>()
        val b = DocumentMissionStore(d, onSkip = { skipped += it })
        assertEquals(listOf(mission("ok", 1_000)), b.list("p1"))
        assertTrue(skipped.any { it.contains(Collections.MISSIONS) && it.contains("bad") }, "$skipped")
        assertEquals(bad, d.readAll(Collections.MISSIONS)["bad"])
    }

    @Test fun `모르는 미션 종류 값은 건너뛴다`() {
        val d = InMemoryDocumentStore()
        val a = DocumentMissionStore(d)
        a.upsert(mission("ok", 1_000))
        a.upsert(mission("bad", 2_000, kind = MissionKind.WEEKLY))
        val bad = rewriteDoc(d.readAll(Collections.MISSIONS)["bad"]!!) { _, v ->
            if (v is JsonPrimitive && v.isString && v.content == "WEEKLY") JsonPrimitive("MONTHLY") else null
        }
        d.write(Collections.MISSIONS, "bad", bad)
        val skipped = ArrayList<String>()
        val b = DocumentMissionStore(d, onSkip = { skipped += it })
        assertEquals(listOf("ok"), b.list("p1").map { it.uid })
        assertTrue(skipped.any { it.contains(Collections.MISSIONS) && it.contains("bad") }, "$skipped")
        assertEquals(bad, d.readAll(Collections.MISSIONS)["bad"])
    }

    @Test fun `모르는 조건 번호는 건너뛴다`() {
        val d = InMemoryDocumentStore()
        val a = DocumentMissionStore(d)
        a.upsert(mission("ok", 1_000))
        a.upsert(mission("bad", 2_000, condition = ConditionType.CHARGE_STOP_AT_80))
        // 조건 필드(이름에 condition 이 든 키)를 앱이 모르는 값으로 바꾼다
        val bad = rewriteDoc(d.readAll(Collections.MISSIONS)["bad"]!!) { k, v ->
            if (k != null && k.contains("condition", ignoreCase = true) && v is JsonPrimitive)
                if (v.isString) JsonPrimitive("NO_SUCH_CONDITION") else JsonPrimitive(999)
            else null
        }
        d.write(Collections.MISSIONS, "bad", bad)
        val skipped = ArrayList<String>()
        val b = DocumentMissionStore(d, onSkip = { skipped += it })
        assertEquals(listOf("ok"), b.list("p1").map { it.uid })
        assertTrue(skipped.any { it.contains(Collections.MISSIONS) && it.contains("bad") }, "$skipped")
        assertEquals(bad, d.readAll(Collections.MISSIONS)["bad"])
    }

    @Test fun `건너뛴 문서는 같은 키로 다시 쓰면 고쳐진다`() {
        val d = InMemoryDocumentStore()
        d.write(Collections.PROFILES, "p1", "garbage")
        val p = DocumentProfileStore(d)
        assertTrue(p.profiles().isEmpty())
        p.upsert(Profile("p1", "a", 1))
        assertEquals(listOf(Profile("p1", "a", 1)), DocumentProfileStore(d).profiles())
    }

    @Test fun `건너뛸 것이 없으면 onSkip 을 부르지 않는다`() {
        val d = InMemoryDocumentStore()
        DocumentProfileStore(d).apply { upsert(Profile("p1", "a", 1)); saveState(profileState()); saveProgress(CharacterProgress("p1", CharacterId(1))) }
        DocumentSessionStore(d).save(session("s1", 1))
        DocumentMissionStore(d).upsert(mission("m1", 1, full = true))
        val skipped = ArrayList<String>()
        DocumentProfileStore(d, onSkip = { skipped += it })
        DocumentSessionStore(d, onSkip = { skipped += it })
        DocumentMissionStore(d, onSkip = { skipped += it })
        assertTrue(skipped.isEmpty(), "$skipped")
    }
}

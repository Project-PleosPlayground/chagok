package core.spec48

import core.challengeSeatbelt
import core.Fx
import core.character.CharacterId
import core.character.CharacterProgress
import core.character.CharacterSelector
import core.character.GrowthModule
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.ProfileStore
import core.character.Wallet
import core.content.BundledContent
import core.content.ContentCodec
import core.content.ContentManifest
import core.content.ContentPack
import core.content.ContentUpdate
import core.content.ContentValidator
import core.flow.SessionEndPipeline
import core.flow.SessionEndSummary
import core.item.ItemCategory
import core.item.ItemId
import core.item.StageItem
import core.item.StagePoint
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 2단계: 개발자 주의사항(사실)을 보고 더한 경계값·사이드 이펙트 테스트 */
class SideEffectSpecTest {

    /** 저장 호출 횟수를 세는 저장소 */
    private class SpyStore(val inner: InMemoryProfileStore = InMemoryProfileStore()) : ProfileStore by inner {
        var stateSaves = 0
        var progressSaves = 0
        override fun saveState(state: ProfileState) { stateSaves++; inner.saveState(state) }
        override fun saveProgress(progress: CharacterProgress) { progressSaves++; inner.saveProgress(progress) }
        fun reset() { stateSaves = 0; progressSaves = 0 }
    }

    private val pid = Profile.DEFAULT
    private val sessions = InMemorySessionStore()
    private val store = SpyStore()
    private val missions = MissionEngine(store = InMemoryMissionStore(), sessions = sessions, missions = { BundledContent.pack.missions })
    // 유효 주행 경험치(07)를 0 으로 둔다. 이 파일은 보상이 0 일 때의 저장 동작을 본다
    private val pipeline = SessionEndPipeline(sessions, store, missions, content = { BundledContent.pack.copy(missions = core.Mx.catalog(driveXp = 0)) })
    private val start = Fx.MON + 9 * 3_600_000L
    private val ok = VehicleStatus(seatBeltBuckledAtStart = true)

    /** issue=false 면 미션을 발급하지 않아 보상이 0 인 유효 세션이 된다 */
    private fun end(id: String, valid: Boolean = true, issue: Boolean = true, startMs: Long = start): SessionEndSummary {
        if (issue) missions.challengeSeatbelt(pid, startMs - 1)
        val rec = Fx.record(id, startMs, km = 12f, valid = valid)
        store.reset()
        return pipeline.onSessionEnd(rec, ok, startMs + 30 * 60_000L)
    }

    private fun setState(s: ProfileState) = store.inner.saveState(s)

    // ---- 사실 1, 2: 유효 세션의 저장 ----
    @Test fun `유효 세션 - xp 0 이어도 고른 캐릭터 진행이 처음 저장된다`() {
        setState(ProfileState(pid, selected = CharacterId(2)))
        val sum = end("s1", issue = false)
        assertEquals(0, sum.xpGained)
        assertNull(sum.leveledUpTo)
        assertEquals(1, store.progressSaves)
        assertEquals(listOf(CharacterProgress(pid, CharacterId(2))), store.progresses(pid))
        assertEquals(CharacterProgress(pid, CharacterId(2)), sum.character)
    }

    @Test fun `유효 세션 - 코인 0 이어도 상태를 저장한다`() {
        setState(ProfileState(pid, selected = CharacterId(1), coins = 3))
        val sum = end("s1", issue = false)
        assertEquals(0, sum.coinsGained)
        assertTrue(store.stateSaves >= 1)
        assertEquals(3, store.state(pid).coins)
    }

    @Test fun `유효 세션 - 고른 캐릭터 없으면 진행 저장 없음, 상태는 저장`() {
        val sum = end("s1")
        assertEquals(0, store.progressSaves)
        assertTrue(store.stateSaves >= 1)
        assertNull(sum.character)
    }

    @Test fun `유효 세션 - 고른 캐릭터 없어도 미션 완료는 일어난다`() {
        val sum = end("s1")
        assertTrue(sum.completed.isNotEmpty())
        assertEquals(0, sum.xpGained)
    }

    // ---- 사실 2, 3: 무효 세션 ----
    @Test fun `무효 세션 - saveState saveProgress 를 부르지 않는다`() {
        setState(ProfileState(pid, selected = CharacterId(1), coins = 9))
        end("bad", valid = false)
        assertEquals(0, store.stateSaves)
        assertEquals(0, store.progressSaves)
        assertTrue(store.progresses(pid).isEmpty())
    }

    @Test fun `무효 세션 - summary 는 저장된 상태와 기본 진행값`() {
        val st = ProfileState(pid, selected = CharacterId(4), coins = 12)
        setState(st)
        val sum = end("bad", valid = false)
        assertEquals(st, sum.profile)
        assertEquals(CharacterProgress(pid, CharacterId(4)), sum.character)
        assertEquals(0, sum.coinsGained)
    }

    @Test fun `무효 세션 - 고른 캐릭터 없으면 character null`() {
        val sum = end("bad", valid = false)
        assertNull(sum.character)
        assertEquals(ProfileState(pid), sum.profile)
    }

    @Test fun `무효 세션 - 저장된 진행이 있으면 summary character 는 그 값`() {
        setState(ProfileState(pid, selected = CharacterId(1)))
        val prog = CharacterProgress(pid, CharacterId(1), "별명", 250, 2)
        store.inner.saveProgress(prog)
        val sum = end("bad", valid = false)
        assertEquals(prog, sum.character)
    }

    // ---- 사실 4: 콘텐츠에 없는 선택 ----
    @Test fun `콘텐츠에 없는 캐릭터가 골라져 있어도 세션 종료는 그 id 로 처리`() {
        setState(ProfileState(pid, selected = CharacterId(99)))
        val sum = end("s1")
        assertTrue(sum.xpGained > 0)
        assertEquals(CharacterId(99), sum.character?.characterId)
        assertEquals(sum.xpGained, store.progress(pid, CharacterId(99)).xp)
    }

    // ---- 사실 5: 넘침 ----
    @Test fun `xp 더하기는 Int MAX 에서 멈춘다`() {
        val r = GrowthModule().addXp(CharacterProgress(pid, CharacterId(1), xp = Int.MAX_VALUE - 5, stage = 5), 10)
        assertEquals(Int.MAX_VALUE, r.state.xp)
        assertEquals(5, r.state.stage)
        assertNull(r.leveledUpTo)
    }

    @Test fun `xp 결과가 음수면 0`() {
        val r = GrowthModule().addXp(CharacterProgress(pid, CharacterId(1), xp = 10), -50)
        assertEquals(0, r.state.xp)
        assertEquals(1, r.state.stage)
    }

    @Test fun `xp 를 빼서 단계가 내려가도 leveledUpTo 는 null`() {
        val r = GrowthModule().addXp(CharacterProgress(pid, CharacterId(1), xp = 150, stage = 2), -100)
        assertEquals(50, r.state.xp)
        assertNull(r.leveledUpTo)
    }

    @Test fun `xp 0 더하기는 아무것도 안 바뀐다`() {
        val before = CharacterProgress(pid, CharacterId(1), "a", 100, 2)
        val r = GrowthModule().addXp(before, 0)
        assertEquals(before, r.state)
        assertNull(r.leveledUpTo)
    }

    @Test fun `코인 earn 은 Int MAX 에서 멈춘다`() {
        val w = Wallet(BundledContent.pack.items)
        assertEquals(Int.MAX_VALUE, w.earn(ProfileState(pid, coins = Int.MAX_VALUE - 1), 10).coins)
    }

    @Test fun `세션 보상도 코인과 xp 가 넘치지 않는다`() {
        setState(ProfileState(pid, selected = CharacterId(1), coins = Int.MAX_VALUE - 1))
        store.inner.saveProgress(CharacterProgress(pid, CharacterId(1), xp = Int.MAX_VALUE - 1, stage = 5))
        val sum = end("s1")
        assertTrue(sum.coinsGained > 0 && sum.xpGained > 0)
        assertEquals(Int.MAX_VALUE, store.state(pid).coins)
        assertEquals(Int.MAX_VALUE, store.progress(pid, CharacterId(1)).xp)
        assertEquals(5, store.progress(pid, CharacterId(1)).stage)
        assertNull(sum.leveledUpTo)
    }

    // ---- 사실 6: 검증 경계 ----
    private val base: ContentPack = BundledContent.pack

    @Test fun `id 1 은 통과 (하한)`() {
        val p = base.copy(characters = listOf(base.characters.first().copy(id = CharacterId(1))))
        assertEquals(emptyList<String>(), ContentValidator.problems(p))
    }

    @Test fun `캐릭터와 아이템 id 가 같아도 서로 중복이 아니다`() {
        // 내장 묶음은 캐릭터 1~5, 아이템 1~10 이 겹친다
        assertEquals(emptyList<String>(), ContentValidator.problems(base))
    }

    @Test fun `assetKey 는 전체 일치 - 앞뒤에 다른 글자가 붙으면 문제`() {
        for (bad in listOf(" avante", "avante ", "avante\n", "avante!", "a.b")) {
            val p = base.copy(characters = listOf(base.characters.first().copy(assetKey = bad)))
            assertTrue(ContentValidator.problems(p).isNotEmpty(), "assetKey='$bad'")
        }
    }

    @Test fun `assetKey 밑줄 하나만도 통과`() {
        val p = base.copy(characters = listOf(base.characters.first().copy(assetKey = "_")))
        assertEquals(emptyList<String>(), ContentValidator.problems(p))
    }

    @Test fun `여러 문제가 한 번에 모두 보고된다`() {
        val c = base.characters.first()
        val p = base.copy(characters = listOf(c.copy(id = CharacterId(0), name = "", assetKey = "BAD")))
        assertTrue(ContentValidator.problems(p).size >= 3, ContentValidator.problems(p).toString())
    }

    // ---- 사실 7: JSON ----
    private fun baseJson(): JsonObject = Json.parseToJsonElement(ContentCodec.encode(base)).jsonObject

    private fun withCharacters(root: JsonObject, chars: JsonArray) = JsonObject(root + ("characters" to chars))

    @Test fun `characters 키가 없으면 decode 는 예외`() {
        val root = JsonObject(baseJson() - "characters")
        assertThrows(Exception::class.java) { ContentCodec.decode(root.toString()) }
    }

    @Test fun `따옴표로 감싼 캐릭터 id 는 거절`() {
        val root = baseJson()
        val chars = root.getValue("characters").jsonArray
        val first = JsonObject(chars[0].jsonObject + ("id" to JsonPrimitive("1")))
        val text = withCharacters(root, JsonArray(listOf(first) + chars.drop(1))).toString()
        assertThrows(Exception::class.java) { ContentCodec.decode(text) }
    }

    @Test fun `따옴표로 감싼 아이템 id 는 거절`() {
        val root = baseJson()
        val items = root.getValue("items").jsonArray
        val first = JsonObject(items[0].jsonObject + ("id" to JsonPrimitive("1")))
        val text = JsonObject(root + ("items" to JsonArray(listOf(first) + items.drop(1)))).toString()
        assertThrows(Exception::class.java) { ContentCodec.decode(text) }
    }

    @Test fun `캐릭터의 모르는 필드는 무시`() {
        val root = baseJson()
        val chars = root.getValue("characters").jsonArray
        val first = JsonObject(chars[0].jsonObject + ("extra" to JsonPrimitive("x")))
        val text = withCharacters(root, JsonArray(listOf(first) + chars.drop(1))).toString()
        assertEquals(base, ContentCodec.decode(text))
    }

    @Test fun `빈 characters 배열은 읽히지만 검증에서 걸린다`() {
        val text = withCharacters(baseJson(), JsonArray(emptyList())).toString()
        val p = ContentCodec.decode(text)
        assertTrue(p.characters.isEmpty())
        assertTrue(ContentValidator.problems(p).isNotEmpty())
    }

    // ---- 사실 8: 원격 콘텐츠 ----
    private val appVersion = base.minAppVersion + 100

    private fun newer(p: ContentPack) = p.copy(contentVersion = base.contentVersion + 1)

    @Test fun `시작 때 캐시가 검증 실패면 내장 묶음`() {
        val bads = listOf(
            newer(base).copy(characters = emptyList()),
            newer(base).copy(characters = listOf(base.characters[0], base.characters[0])),
            newer(base).copy(items = base.items + base.items.first()),
            newer(base).copy(characters = listOf(base.characters[0].copy(id = CharacterId(0)))),
        )
        bads.forEachIndexed { i, bad ->
            assertSame(base, ContentUpdate.pickAtStartup(base, ContentCodec.encode(bad), appVersion), "case $i")
        }
    }

    @Test fun `시작 때 캐시에 characters 키가 없으면 예외 없이 내장 묶음`() {
        val text = JsonObject(Json.parseToJsonElement(ContentCodec.encode(newer(base))).jsonObject - "characters").toString()
        assertSame(base, ContentUpdate.pickAtStartup(base, text, appVersion))
    }

    @Test fun `시작 때 정상 캐시는 쓴다 (대조군)`() {
        val good = newer(base)
        assertEquals(good, ContentUpdate.pickAtStartup(base, ContentCodec.encode(good), appVersion))
    }

    private fun verdictName(v: Any) = v::class.simpleName ?: v.toString()

    @Test fun `내려받은 묶음이 검증 실패면 Reject`() {
        val bytes = ContentCodec.encode(newer(base).copy(characters = emptyList())).toByteArray()
        val m = ContentManifest(base.contentVersion + 1, base.minAppVersion, "content.json", ContentUpdate.sha256Hex(bytes))
        assertEquals("Reject", verdictName(ContentUpdate.verify(bytes, m, base.contentVersion, appVersion)))
    }

    @Test fun `내려받은 묶음에 characters 키가 없으면 Reject`() {
        val text = JsonObject(Json.parseToJsonElement(ContentCodec.encode(newer(base))).jsonObject - "characters").toString()
        val bytes = text.toByteArray()
        val m = ContentManifest(base.contentVersion + 1, base.minAppVersion, "content.json", ContentUpdate.sha256Hex(bytes))
        assertEquals("Reject", verdictName(ContentUpdate.verify(bytes, m, base.contentVersion, appVersion)))
    }

    @Test fun `정상 묶음은 Reject 가 아니다 (대조군)`() {
        val bytes = ContentCodec.encode(newer(base)).toByteArray()
        val m = ContentManifest(base.contentVersion + 1, base.minAppVersion, "content.json", ContentUpdate.sha256Hex(bytes))
        assertNotEquals("Reject", verdictName(ContentUpdate.verify(bytes, m, base.contentVersion, appVersion)))
    }

    // ---- 사실 9: 선택은 진행을 만들지 않는다 ----
    @Test fun `선택은 진행을 만들거나 저장하지 않는다`() {
        val sel = CharacterSelector { base.characters }
        val s = sel.select(ProfileState("없는프로필"), CharacterId(3))
        assertEquals(CharacterId(3), s?.selected)
        assertEquals("없는프로필", s?.profileId)
        assertEquals(0, store.progressSaves)
        assertTrue(store.progresses("없는프로필").isEmpty())
    }

    @Test fun `같은 캐릭터를 다시 선택해도 상태 그대로`() {
        val sel = CharacterSelector { base.characters }
        val s = ProfileState(pid, selected = CharacterId(6), coins = 5)
        assertEquals(s, sel.select(s, CharacterId(6)))
    }

    // ---- 사실 10, 14: 스테이지 배치 저장 (08 "스테이지 배치") ----
    @Test fun `같은 아이템이 두 번 있으면 맨 위 하나만 저장한다`() {
        val w = Wallet(base.items)
        val pet = base.items.first { it.category == ItemCategory.PET }
        val s = ProfileState(pid, owned = setOf(pet.id))
        val r = w.saveStage(s, listOf(StageItem(pet.id, StagePoint(1f, 1f)), StageItem(pet.id, StagePoint(2f, 2f))))
        assertEquals(listOf(StageItem(pet.id, StagePoint(2f, 2f))), r.placed)
    }

    @Test fun `펫은 여러 개 함께 놓는다`() {
        val w = Wallet(base.items)
        val pets = base.items.filter { it.category == ItemCategory.PET }
        assertTrue(pets.size >= 2)
        val s = ProfileState(pid, owned = pets.map { it.id }.toSet())
        val stage = listOf(StageItem(pets[0].id), StageItem(pets[1].id))
        assertEquals(stage, w.saveStage(s, stage).placed)
    }

    @Test fun `가진 아이템이 콘텐츠에서 빠지면 buy Unknown, 스테이지에 저장하지 않는다`() {
        val w = Wallet(base.items)
        val s = ProfileState(pid, coins = 1000, owned = setOf(ItemId(99)))
        assertEquals(Wallet.BuyResult.Unknown, w.buy(s, ItemId(99)))
        assertTrue(w.saveStage(s, listOf(StageItem(ItemId(99)))).placed.isEmpty())
    }

    @Test fun `가지지 않은 아이템은 스테이지에 저장하지 않는다`() {
        val w = Wallet(base.items)
        assertTrue(w.saveStage(ProfileState(pid), listOf(StageItem(base.items.first().id))).placed.isEmpty())
    }

    @Test fun `저장은 배치만 바꾸고 코인 보유는 그대로`() {
        val w = Wallet(base.items)
        val pet = base.items.first { it.category == ItemCategory.PET }
        val s = ProfileState(pid, coins = 30, owned = setOf(pet.id), placed = listOf(StageItem(pet.id)))
        assertEquals(s.copy(placed = emptyList()), w.saveStage(s, emptyList()))
    }

    // ---- 사실 11: 순서 ----
    @Test fun `progresses 는 처음 저장한 순서를 유지한다`() {
        val st = InMemoryProfileStore()
        st.saveProgress(CharacterProgress(pid, CharacterId(3)))
        st.saveProgress(CharacterProgress(pid, CharacterId(1)))
        st.saveProgress(CharacterProgress(pid, CharacterId(2)))
        st.saveProgress(CharacterProgress(pid, CharacterId(1), xp = 40))
        assertEquals(listOf(3, 1, 2), st.progresses(pid).map { it.characterId.value })
        assertEquals(40, st.progresses(pid)[1].xp)
    }

    // ---- 사실 1 + 결정 2: 여러 세션 ----
    @Test fun `캐릭터를 오가도 각자 성장이 쌓인다`() {
        setState(ProfileState(pid, selected = CharacterId(1)))
        val a = end("s1")
        setState(store.state(pid).copy(selected = CharacterId(2)))
        val b = end("s2", startMs = start + Fx.DAY)
        setState(store.state(pid).copy(selected = CharacterId(1)))
        val c = end("s3", startMs = start + 2 * Fx.DAY)
        assertEquals(a.xpGained + c.xpGained, store.progress(pid, CharacterId(1)).xp)
        assertEquals(b.xpGained, store.progress(pid, CharacterId(2)).xp)
        assertEquals(listOf(1, 2), store.progresses(pid).map { it.characterId.value })
    }
}

package core.content

import core.Fx
import core.character.CharacterId
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.Wallet
import core.flow.SessionEndPipeline
import core.item.ItemCategory
import core.item.ItemDef
import core.item.ItemId
import core.item.StageItem
import core.mission.ConditionType
import core.mission.ConditionRow
import core.mission.InMemoryMissionStore
import core.mission.MissionCatalog
import core.mission.MissionEngine
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.EndAnimation
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * 원격 콘텐츠 업데이트(#31) 독립 테스트. 기존 ContentUpdateTest/ContentEdgeCaseTest 를 보지 않고 작성했다.
 * 이름 앞 글자: A manifest, B verify, C 값 규칙 공백, D 시작 시 선택, E ContentState, F 진행 중 미션, G 아이템, H 파이프라인·내장.
 */
class ContentIndependentTest {
    private val bundled = BundledContent.pack
    private val P = Profile.DEFAULT
    private val H = 3_600_000L

    // ---------- 도우미 ----------
    private fun pack(v: Int, minApp: Int = 1) = bundled.copy(contentVersion = v, minAppVersion = minApp)
    private fun json(p: ContentPack) = ContentCodec.encode(p)
    private fun bytes(p: ContentPack) = json(p).toByteArray(Charsets.UTF_8)
    private fun manifestFor(b: ByteArray, v: Int, minApp: Int = 1, path: String = "packs/v$v.json") =
        ContentManifest(v, minApp, path, ContentUpdate.sha256Hex(b))
    private fun manifestJson(v: Int, minApp: Int, path: String, sha: String) =
        """{"contentVersion":$v,"minAppVersion":$minApp,"path":"$path","sha256":"$sha"}"""
    private fun reason(v: ContentVerdict) = (v as? ContentVerdict.Reject)?.reason
    private fun accepted(v: ContentVerdict): ContentPack = (v as? ContentVerdict.Accept)?.pack ?: throw AssertionError("Accept 기대, 실제 $v")
    /** 텍스트를 그대로 해시해서 v2 로 검증 */
    private fun verifyText(text: String, v: Int = 2, minApp: Int = 1, active: Int = 1, app: Int = 1): ContentVerdict {
        val b = text.toByteArray(Charsets.UTF_8)
        return ContentUpdate.verify(b, manifestFor(b, v, minApp), active, app)
    }
    private fun verifyPack(p: ContentPack, active: Int = 1, app: Int = 1): ContentVerdict {
        val b = bytes(p); return ContentUpdate.verify(b, manifestFor(b, p.contentVersion, p.minAppVersion), active, app)
    }
    /** 내장 조건 표의 한 행에 단계 목표치만 바꾼 것 */
    private fun row(c: ConditionType, vararg targets: Float): ConditionRow = bundled.missions.row(c)!!.copy(tierTargets = targets.toList())
    private fun withConditions(v: Int, vararg rows: ConditionRow) = pack(v).copy(missions = bundled.missions.copy(conditions = rows.toList()))

    private class World(catalog: () -> MissionCatalog = { BundledContent.pack.missions }) {
        val missions = InMemoryMissionStore(); val sessions = InMemorySessionStore(); val profiles = InMemoryProfileStore()
        val engine = MissionEngine(missions, sessions, missions = catalog)
        init { profiles.saveState(ProfileState(Profile.DEFAULT, selected = CharacterId(1))) }   // 캐릭터를 고른 프로필 (#48)
        /** 미션 하나를 제공하고 도전한다 */
        fun take(kind: MissionKind, c: ConditionType, tier: Int, at: Long, profile: String = Profile.DEFAULT): MissionInstance {
            val m = engine.offer(profile, kind, c, tier, at)!!
            engine.challenge(profile, m.uid, at, driving = false); return m
        }
        fun get(uid: String, profile: String = Profile.DEFAULT) = missions.list(profile).single { it.uid == uid }
    }

    // ================= A. manifest =================

    @Test fun `A1 manifest 가 없거나 비었으면 null`() {
        assertNull(ContentUpdate.readManifest(null))
        assertNull(ContentUpdate.readManifest(""))
        assertNull(ContentUpdate.readManifest("   \n"))
        assertNull(ContentUpdate.readManifest("{}"))
        assertNull(ContentUpdate.readManifest("null"))
        assertNull(ContentUpdate.readManifest("[]"))
    }

    @Test fun `A2 캡티브 포털 HTML 은 null`() {
        assertNull(ContentUpdate.readManifest("<!DOCTYPE html><html><head><title>Wi-Fi 로그인</title></head><body>약관 동의</body></html>"))
    }

    @Test fun `A3 BOM 과 모르는 필드가 있어도 읽는다`() {
        val sha = "a".repeat(64)
        val m = ContentUpdate.readManifest("\uFEFF{\"contentVersion\":3,\"minAppVersion\":1,\"path\":\"p/v3.json\",\"sha256\":\"$sha\",\"releaseNote\":\"x\",\"extra\":{\"a\":[1]}}")
        assertNotNull(m); assertEquals(3, m!!.contentVersion)
    }

    @Test fun `A4 필드가 빠지면 null`() {
        val sha = "a".repeat(64)
        assertNull(ContentUpdate.readManifest("""{"contentVersion":3,"minAppVersion":1,"path":"p"}"""))
        assertNull(ContentUpdate.readManifest("""{"contentVersion":3,"path":"p","sha256":"$sha"}"""))
        assertNull(ContentUpdate.readManifest("""{"minAppVersion":1,"path":"p","sha256":"$sha"}"""))
    }

    @Test fun `A5 타입이 틀리거나 범위를 넘으면 null`() {
        val sha = "a".repeat(64)
        for (v in listOf("\"abc\"", "2.5", "2147483648", "true", "null", "[]"))
            assertNull(ContentUpdate.readManifest("""{"contentVersion":$v,"minAppVersion":1,"path":"p","sha256":"$sha"}"""), "contentVersion=$v")
        assertNull(ContentUpdate.readManifest("""{"contentVersion":2,"minAppVersion":1,"path":"p","sha256":123}"""))
    }

    @Test fun `A6 해시가 대문자여도 읽고 검증이 통과한다`() {
        val b = bytes(pack(2)); val sha = ContentUpdate.sha256Hex(b).uppercase()
        val m = ContentUpdate.readManifest(manifestJson(2, 1, "p/v2.json", sha))
        assertNotNull(m)
        accepted(ContentUpdate.verify(b, m, 1, 1))
    }

    @Test fun `A7 해시 길이나 문자가 틀리면 null`() {
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "a".repeat(63))))
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "a".repeat(65))))
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "g".repeat(64))))
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "")))
    }

    @Test fun `A8 해시 뒤에 줄바꿈이 붙어도 예외 없이 거절하거나 올바른 파일을 받는다`() {
        val b = bytes(pack(2)); val sha = ContentUpdate.sha256Hex(b)
        for (bad in listOf("$sha\\n", " $sha", "$sha ")) {
            val m = assertDoesNotThrow<ContentManifest?> { ContentUpdate.readManifest(manifestJson(2, 1, "p", bad)) }
            if (m != null) accepted(ContentUpdate.verify(b, m, 1, 1))
        }
    }

    @Test fun `A9 유니코드 숫자로 된 해시는 null`() {
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "١".repeat(64))), "아라비아-인도 숫자")
        assertNull(ContentUpdate.readManifest(manifestJson(2, 1, "p", "１".repeat(64))), "전각 숫자")
    }

    @Test fun `A10 상위 폴더·절대 경로·다른 주소는 null`() {
        val sha = "a".repeat(64)
        for (p in listOf("../x.json", "packs/../../x.json", "/etc/x", "\\\\host\\x", "https://evil.example/x.json", "", " ", "packs\\\\..\\\\x"))
            assertNull(ContentUpdate.readManifest(manifestJson(2, 1, p, sha)), "path=$p")
        assertNotNull(ContentUpdate.readManifest(manifestJson(2, 1, "packs/v2.json", sha)))
    }

    @Test fun `A11 슬래시 없는 스킴 경로도 다른 주소라 null`() {
        val sha = "a".repeat(64)
        // OkHttp HttpUrl.resolve 는 기준과 스킴이 다르면 슬래시가 없어도 host 로 읽는다 → http://evil.example/pack.json
        for (p in listOf("http:evil.example/pack.json", "file:/data/local/tmp/x.json"))
            assertNull(ContentUpdate.readManifest(manifestJson(2, 1, p, sha)), "path=$p")
    }

    @Test fun `A12 퍼센트 인코딩된 상위 폴더 경로는 null`() {
        val sha = "a".repeat(64)
        for (p in listOf("%2e%2e/secret.json", "packs/%2E%2E/%2e%2e/x.json", "packs/.%2e/x"))
            assertNull(ContentUpdate.readManifest(manifestJson(2, 1, p, sha)), "path=$p")
    }

    @Test fun `A13 응답이 두 번 이어 붙으면 null`() {
        val one = manifestJson(2, 1, "p", "a".repeat(64))
        assertNull(ContentUpdate.readManifest(one + one))
        assertNull(ContentUpdate.readManifest(one.substring(0, one.length / 2)))
    }

    @Test fun `A14 받을지 판단`() {
        val m = ContentManifest(5, 3, "p", "a".repeat(64))
        assertNull(ContentUpdate.shouldDownload(m, 4, 3))
        assertNull(ContentUpdate.shouldDownload(m, 4, 99))
        assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(m, 5, 3))
        assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(m, 7, 3), "서버가 버전을 되돌림")
        assertEquals(ContentRejection.APP_TOO_OLD, ContentUpdate.shouldDownload(m, 4, 2))
        assertEquals(ContentRejection.BAD_MANIFEST, ContentUpdate.shouldDownload(null, 4, 3))
        assertNotNull(ContentUpdate.shouldDownload(m, 9, 1), "낡았고 앱도 낮음 → 어쨌든 거절")
    }

    @Test fun `A15 0 이나 음수 버전 manifest 는 받지 않는다`() {
        assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(ContentManifest(0, 1, "p", "a".repeat(64)), 1, 1))
        assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(ContentManifest(-5, 1, "p", "a".repeat(64)), 1, 1))
    }

    // ================= B. verify =================

    @Test fun `B1 정상 묶음은 Accept 이고 내용이 같다`() {
        val p = pack(2); assertEquals(p, accepted(verifyPack(p)))
    }

    @Test fun `B2 통신 실패로 바이트가 없으면 HASH_MISMATCH`() {
        val b = bytes(pack(2))
        assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(null, manifestFor(b, 2), 1, 1)))
        assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(ByteArray(0), manifestFor(b, 2), 1, 1)))
    }

    @Test fun `B3 덜 받은 파일은 HASH_MISMATCH`() {
        val b = bytes(pack(2)); val m = manifestFor(b, 2)
        for (cut in listOf(1, b.size / 3, b.size / 2, b.size - 1))
            assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(b.copyOf(cut), m, 1, 1)), "cut=$cut")
    }

    @Test fun `B4 캡티브 포털 HTML 이 오면 HASH_MISMATCH`() {
        val m = manifestFor(bytes(pack(2)), 2)
        assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify("<html>login</html>".toByteArray(), m, 1, 1)))
    }

    @Test fun `B5 응답이 두 번 붙어 오면 거절`() {
        val b = bytes(pack(2)); val doubled = b + b
        assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(doubled, manifestFor(b, 2), 1, 1)))
        // 운영자가 두 번 붙은 파일을 올리고 그 해시를 manifest 에 넣은 경우
        assertEquals(ContentRejection.PARSE_ERROR, reason(ContentUpdate.verify(doubled, manifestFor(doubled, 2), 1, 1)))
    }

    @Test fun `B6 잘린 파일을 해시까지 맞춰 올리면 PARSE_ERROR`() {
        val text = json(pack(2))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.substring(0, text.length - 1))))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.substring(0, text.length / 2))))
    }

    @Test fun `B7 UTF-8 BOM 과 앞뒤 공백은 받는다`() {
        val p = pack(2)
        val b = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + bytes(p)
        assertEquals(p, accepted(ContentUpdate.verify(b, manifestFor(b, 2), 1, 1)))
        assertEquals(p, accepted(verifyText("\r\n  " + json(p) + "\r\n\r\n")))
    }

    @Test fun `B8 UTF-16 으로 저장한 파일은 거절`() {
        val b = json(pack(2)).toByteArray(Charsets.UTF_16)
        val v = ContentUpdate.verify(b, manifestFor(b, 2), 1, 1)
        assertTrue(v is ContentVerdict.Reject, "$v")
    }

    @Test fun `B9 CP949(ANSI) 로 저장한 한글 파일은 거절한다 - 깨진 제목을 받지 않는다`() {
        assumeTrue(Charset.isSupported("MS949"))
        val b = json(pack(2)).toByteArray(Charset.forName("MS949"))
        val v = ContentUpdate.verify(b, manifestFor(b, 2), 1, 1)
        if (v is ContentVerdict.Accept) {
            val broken = v.pack.missions.texts["mission_brake_name"]
            throw AssertionError("CP949 파일을 Accept 함. 제목이 깨짐: '$broken' (원래 '${bundled.missions.texts["mission_brake_name"]}')")
        }
    }

    @Test fun `B10 모르는 조건ID 는 INVALID_CONTENT, 틀린 enum 은 PARSE_ERROR`() {
        val text = json(pack(2))
        val newCond = text.replaceFirst(Regex("\"conditionId\":\\d+"), "\"conditionId\":99")
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyText(newCond)))
        val newAnim = text.replaceFirst("\"animation\":\"DANCE\"", "\"animation\":\"JUMP\"")
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(newAnim)))
        val lower = text.replaceFirst("\"DANCE\"", "\"dance\"")
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(lower)))
    }

    @Test fun `B11 모르는 아이템 칸은 PARSE_ERROR`() {
        val text = json(pack(2)).replaceFirst(Regex("\"category\":\"[A-Z]+\""), "\"category\":\"STICKER\"")
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text)))
    }

    @Test fun `B12 새 enum 을 minAppVersion 으로 막으면 받기 전에 APP_TOO_OLD`() {
        val text = json(pack(2, minApp = 5)).replaceFirst(Regex("\"category\":\"[A-Z]+\""), "\"category\":\"STICKER\"")
        val b = text.toByteArray()
        val m = manifestFor(b, 2, minApp = 5)
        assertEquals(ContentRejection.APP_TOO_OLD, ContentUpdate.shouldDownload(m, 1, 3))
        assertEquals(ContentRejection.APP_TOO_OLD, reason(ContentUpdate.verify(b, m, 1, 3)))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, text, 3))
    }

    @Test fun `B13 모든 단계의 모르는 필드는 무시하고 받는다`() {
        val p = pack(2)
        val text = json(p)
            .replaceFirst("{", "{\"_note\":\"x\",\"future\":{\"a\":[1,2,{\"b\":null}]},")
            .replace("{\"id\":", "{\"extra\":true,\"id\":")
            .replace("\"missions\":{", "\"missions\":{\"seasonal\":[],")
            .replace("{\"minScore\":", "{\"sound\":\"x.mp3\",\"minScore\":")
        assertEquals(p, accepted(verifyText(text)))
    }

    @Test fun `B14 파일 안 버전이 manifest 와 다르면 VERSION_MISMATCH`() {
        val b = bytes(pack(3))
        assertEquals(ContentRejection.VERSION_MISMATCH, reason(ContentUpdate.verify(b, manifestFor(b, 4), 1, 1)))
        val b2 = bytes(pack(4, minApp = 2))
        assertEquals(ContentRejection.VERSION_MISMATCH, reason(ContentUpdate.verify(b2, manifestFor(b2, 4, minApp = 1), 1, 5)))
    }

    @Test fun `B15 값의 타입이 틀리면 PARSE_ERROR`() {
        val text = json(pack(2))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.replaceFirst(Regex("\"tierTargets\":\\[[^,\\]]+"), "\"tierTargets\":[\"abc\""))))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.replaceFirst(Regex("\"price\":\\d+"), "\"price\":12.5"))))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.replaceFirst(Regex("\"coins\":\\d+"), "\"coins\":99999999999"))))
        assertEquals(ContentRejection.PARSE_ERROR, reason(verifyText(text.replaceFirst("\"items\":[", "\"items\":{").replaceFirst("],\"scoreBands\"", "},\"scoreBands\""))))
    }

    @Test fun `B16 NaN·무한대·너무 큰 목표치는 받지 않는다`() {
        val text = json(pack(2))
        for (bad in listOf("NaN", "Infinity", "-Infinity", "1e40", "-1e40", "\"NaN\""))
            assertFalse(verifyText(text.replaceFirst(Regex("\"tierTargets\":\\[[^,\\]]+"), "\"tierTargets\":[$bad")) is ContentVerdict.Accept, "target=$bad")
    }

    @Test fun `B17 필수 값에 null 이 오면 예외 없이 거절`() {
        val text = json(pack(2))
        for (field in listOf("nameKey", "conditionId", "tierTargets", "coins", "price", "missions", "items", "scoreBands")) {
            val t = text.replaceFirst(Regex("\"$field\":(\"[^\"]*\"|[^,}\\[{]+|\\{[^}]*\\}|\\[)"), "\"$field\":null,\"_x\":")
            val v = assertDoesNotThrow<ContentVerdict> { verifyText(t) }
            assertTrue(v is ContentVerdict.Reject, "field=$field → $v")
        }
    }

    @Test fun `B18 빈 목록은 INVALID_CONTENT`() {
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2))))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(missions = bundled.missions.copy(tierRewards = emptyList())))))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(items = emptyList()))))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(scoreBands = emptyList()))))
    }

    @Test fun `B19 같은 조건이 두 번 있으면 INVALID_CONTENT`() {
        val v = verifyPack(withConditions(2, row(ConditionType.HARSH_BRAKE_MAX, 3f, 2f, 1f, 0f), row(ConditionType.HARSH_BRAKE_MAX, 4f, 3f, 2f, 1f)))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(v))
        assertTrue((v as ContentVerdict.Reject).detail.any { "조건ID 중복: 1" in it })
    }

    @Test fun `B20 아이템 id 중복은 INVALID_CONTENT`() {
        val items = bundled.items + bundled.items.first().copy(imageKey = "copy")
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(items = items))))
    }

    @Test fun `B21 음수 값은 모두 모아서 알려 준다`() {
        val c = bundled.missions
        val missions = c.copy(
            conditions = listOf(row(ConditionType.HARSH_BRAKE_MAX, 3f, 2f, 1f, -1f)) + c.conditions.drop(1),
            tierRewards = c.tierRewards.map { if (it.tier == 1) it.copy(coins = -5, xp = -7) else it },
        )
        val items = listOf(bundled.items.first().copy(price = -100)) + bundled.items.drop(1)
        val v = verifyPack(pack(2).copy(missions = missions, items = items))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(v))
        assertTrue((v as ContentVerdict.Reject).detail.size >= 4, "detail=${v.detail}")
    }

    @Test fun `B22 공백뿐인 문구 키·아이템 이름은 INVALID_CONTENT`() {
        val blank = bundled.missions.row(ConditionType.HARSH_BRAKE_MAX)!!.copy(nameKey = "\t ")
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, blank))))
        val items = listOf(bundled.items.first().copy(nameKey = "  ")) + bundled.items.drop(1)
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(items = items))))
    }

    @Test fun `B23 개인 미션을 줘야 하는데 만들 수 있는 조건이 없으면 INVALID_CONTENT`() {
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, row(ConditionType.STREAK_DAYS, 2f, 3f, 4f, 5f)))))
    }

    @Test fun `B24 단계 목표치가 4칸보다 많으면 INVALID_CONTENT`() {
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, row(ConditionType.HARSH_BRAKE_MAX, 4f, 3f, 2f, 1f, 0f)))))
    }

    @Test fun `B25 0점 구간이 없거나 하한이 겹치면 INVALID_CONTENT`() {
        val noZero = listOf(ScoreBand(90, EndAnimation.DANCE), ScoreBand(10, EndAnimation.OTL))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(scoreBands = noZero))))
        val dupBand = listOf(ScoreBand(0, EndAnimation.DANCE), ScoreBand(0, EndAnimation.OTL))
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(pack(2).copy(scoreBands = dupBand))))
    }

    @Test fun `B26 아주 깊게 중첩된 모르는 필드에도 예외가 없다`() {
        val depth = 50_000
        val text = json(pack(2)).replaceFirst("{", "{\"_deep\":" + "[".repeat(depth) + "]".repeat(depth) + ",")
        assertDoesNotThrow<ContentVerdict> { verifyText(text) }
        assertDoesNotThrow<ContentPack> { ContentUpdate.pickAtStartup(bundled, text, 1) }
    }

    @Test fun `B27 아주 큰 묶음(아이템 5000)도 10초 안에 받는다`() {
        val items = (1..5000).map { ItemDef(ItemId(it), ItemCategory.PROP, "item_${it}_name", "prop", it) }
        val p = pack(2).copy(items = items, itemTexts = items.associate { it.nameKey to "아이템" })
        val t0 = System.nanoTime()
        val got = accepted(verifyPack(p))
        val sec = (System.nanoTime() - t0) / 1e9
        assertEquals(5000, got.items.size)
        assertTrue(sec < 10, "걸린 시간 ${sec}s")
    }

    @Test fun `B28 여러 스레드에서 동시에 검증해도 결과가 같다`() {
        val b = bytes(pack(2)); val m = manifestFor(b, 2)
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..200).map { pool.submit<ContentVerdict> { ContentUpdate.verify(b, m, 1, 1) } }
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(results.all { it is ContentVerdict.Accept })
    }

    @Test fun `B29 무작위 바이트(해시 일치)는 예외 없이 거절`() {
        val r = Random(31)
        repeat(500) {
            val b = r.nextBytes(r.nextInt(0, 2000))
            val v = assertDoesNotThrow<ContentVerdict> { ContentUpdate.verify(b, manifestFor(b, 2), 1, 1) }
            assertTrue(v is ContentVerdict.Reject)
            assertDoesNotThrow<ContentPack> { ContentUpdate.pickAtStartup(bundled, String(b, Charsets.ISO_8859_1), 1) }
        }
    }

    @Test fun `B30 정상 JSON 을 조금씩 망가뜨려도 예외가 없고 받은 것은 규칙을 지킨다`() {
        val base = json(pack(2)); val r = Random(14)
        val alphabet = "{}[]\",:0123456789-.eE aZ\n\\nul"
        repeat(400) {
            val sb = StringBuilder(base)
            repeat(r.nextInt(1, 4)) {
                val i = r.nextInt(sb.length)
                when (r.nextInt(3)) {
                    0 -> sb.deleteCharAt(i)
                    1 -> sb.insert(i, alphabet[r.nextInt(alphabet.length)])
                    else -> sb.setCharAt(i, alphabet[r.nextInt(alphabet.length)])
                }
            }
            val t = sb.toString()
            val v = assertDoesNotThrow<ContentVerdict> { verifyText(t) }
            if (v is ContentVerdict.Accept) {
                assertTrue(ContentValidator.problems(v.pack).isEmpty())
                assertEquals(2, v.pack.contentVersion)
            }
            assertDoesNotThrow<ContentPack> { ContentUpdate.pickAtStartup(bundled, t, 1) }
        }
    }

    // ================= C. 값 규칙 공백 (검증을 통과한 묶음이 앱을 깨는가) =================

    @Test fun `C1 퍼센트 목표가 0~100 밖이면 INVALID_CONTENT`() {
        val charge = row(ConditionType.CHARGE_STOP_AT_80, 150f, 90f, 80f, 70f)
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, row(ConditionType.HARSH_BRAKE_MAX, 3f, 2f, 1f, 0f), charge))), "충전 멈춤 150%")
        val soc = row(ConditionType.NO_BELOW_10_PCT, 10f, 20f, 30f, 200f)
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, soc))), "잔량 200% 밑으로 안 내려가기")
    }

    @Test fun `C2 깜빡이 비율 목표 0 미션은 차선변경 없는 주행에도 예외 없이 미판정으로 남는다`() {
        val p = withConditions(2, row(ConditionType.SIGNALED_RATE_MIN, 0f, 50f, 80f, 100f))
        val v = verifyPack(p)
        if (v is ContentVerdict.Reject) return   // 검증에서 막으면 그것도 맞다
        val cat = accepted(v).missions
        val world = World { cat }; val now = Fx.MON + 9 * H
        val m = world.take(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, now)
        val rec = Fx.record("s1", now + H)   // 차선변경 없음
        world.sessions.save(rec)
        world.engine.onDriveEnd(rec, VehicleStatus(), rec.endWallMs)
        assertEquals(MissionStatus.IN_PROGRESS, world.get(m.uid).status)
        assertNull(world.get(m.uid).counted)
    }

    @Test fun `C3 내장 깜빡이 비율 미션의 센 값은 0~100 퍼센트다`() {
        val world = World(); val now = Fx.MON + 9 * H
        val m = world.take(MissionKind.WEEKLY, ConditionType.SIGNALED_RATE_MIN, 2, now)
        val rec = Fx.record("s1", now + H, events = listOf(Fx.ev(EventType.LANE_CHANGE_SIGNALED), Fx.ev(EventType.LANE_CHANGE_SIGNALED, 20_000)))
        world.sessions.save(rec)
        world.engine.onDriveEnd(rec, VehicleStatus(), rec.endWallMs)
        val counted = world.get(m.uid).counted!!
        assertTrue(counted in 0f..100f, "counted=$counted")
    }

    @Test fun `C4 검증을 통과한 큰 보상이 코인·경험치를 음수나 0 으로 만들지 않는다`() {
        val big = 1_500_000_000
        val c = bundled.missions
        val v = verifyPack(pack(2).copy(missions = c.copy(tierRewards = c.tierRewards.map { it.copy(coins = big, xp = big) })))
        if (v is ContentVerdict.Reject) return   // 상한을 두어 막으면 그것도 맞다
        val p = accepted(v)
        val world = World { p.missions }; val now = Fx.MON + 9 * H
        world.take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now)
        world.take(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 4, now)
        val pipe = SessionEndPipeline(world.sessions, world.profiles, world.engine, content = { p })
        val rec = Fx.record("s1", now + H)
        val sum = pipe.onSessionEnd(rec, VehicleStatus(), rec.endWallMs)
        assertEquals(2, sum.completed.size)
        assertTrue(sum.profile.coins >= 0, "coins=${sum.profile.coins}")
        assertTrue(sum.character!!.xp > 0, "xp=${sum.character!!.xp} (보상을 받았는데 0 이 됨)")
    }

    @Test fun `C5 단계가 오를수록 쉬워지는 목표치는 INVALID_CONTENT`() {
        assertEquals(ContentRejection.INVALID_CONTENT, reason(verifyPack(withConditions(2, row(ConditionType.HARSH_BRAKE_MAX, 0f, 1f, 2f, 3f)))))
    }

    // ================= D. 앱 시작 때 선택 =================

    @Test fun `D1 저장한 것이 없으면 내장`() {
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, null, 1))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, "", 1))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, "\uFEFF", 1))
    }

    @Test fun `D2 새 저장본을 쓰고, 여러 번 다시 켜도 같다`() {
        val t = json(pack(3))
        val a = ContentUpdate.pickAtStartup(bundled, t, 1); val b = ContentUpdate.pickAtStartup(bundled, t, 1)
        assertEquals(3, a.contentVersion); assertEquals(a, b)
    }

    @Test fun `D3 스토어 업데이트로 내장이 같거나 더 새것이면 내장`() {
        val newBundled = pack(5)
        assertSame(newBundled, ContentUpdate.pickAtStartup(newBundled, json(pack(5)), 1))
        assertSame(newBundled, ContentUpdate.pickAtStartup(newBundled, json(pack(4)), 1))
    }

    @Test fun `D4 앱을 낮은 버전으로 다시 깔면 저장본을 쓰지 않는다`() {
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, json(pack(9, minApp = 7)), 6))
        assertEquals(9, ContentUpdate.pickAtStartup(bundled, json(pack(9, minApp = 7)), 7).contentVersion)
    }

    @Test fun `D5 새 앱이 저장한 새 조건ID 묶음은 낮은 앱에서 내장으로`() {
        val t = json(pack(9, minApp = 1)).replaceFirst(Regex("\"conditionId\":\\d+"), "\"conditionId\":99")
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, t, 1))
    }

    @Test fun `D6 저장 중 강제 종료로 잘린 파일은 어디서 잘려도 내장`() {
        val t = json(pack(3))
        for (cut in 1 until t.length step 37)
            assertSame(bundled, ContentUpdate.pickAtStartup(bundled, t.substring(0, cut), 1), "cut=$cut")
    }

    @Test fun `D7 공간만 잡히고 0 으로 채워진 파일은 내장`() {
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, "\u0000".repeat(4096), 1))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, json(pack(3)).substring(0, 100) + "\u0000".repeat(500), 1))
    }

    @Test fun `D8 읽히지만 규칙을 어긴 저장본은 내장`() {
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, json(pack(3).copy(items = emptyList())), 1))
    }

    @Test fun `D9 BOM 이 붙은 저장본도 쓴다`() {
        assertEquals(3, ContentUpdate.pickAtStartup(bundled, "\uFEFF" + json(pack(3)), 1).contentVersion)
    }

    // ================= E. ContentState =================

    @Test fun `E1 올리고 적용하면 한 번만 바뀐다`() {
        val s = ContentState(pack(1)); val p2 = pack(2)
        assertTrue(s.stage(p2)); assertEquals(1, s.active.contentVersion)
        assertSame(p2, s.applyStaged()); assertSame(p2, s.active); assertNull(s.staged)
        assertNull(s.applyStaged())
    }

    @Test fun `E2 같거나 낮은 버전은 올리지 않는다`() {
        val s = ContentState(pack(3))
        assertFalse(s.stage(pack(3))); assertFalse(s.stage(pack(2))); assertNull(s.staged)
    }

    @Test fun `E3 기다리는 것보다 새것만 바꿔 올린다`() {
        val s = ContentState(pack(1))
        assertTrue(s.stage(pack(3))); assertFalse(s.stage(pack(2))); assertEquals(3, s.staged!!.contentVersion)
        assertTrue(s.stage(pack(4))); assertEquals(4, s.applyStaged()!!.contentVersion)
    }

    @Test fun `E4 올린 것 없이 적용하면 아무것도 바뀌지 않는다`() {
        val p = pack(1); val s = ContentState(p)
        repeat(5) { assertNull(s.applyStaged()) }
        assertSame(p, s.active)
    }

    @Test fun `E5 탭을 빠르게 1000번 바꿔도 한 번만 적용`() {
        val s = ContentState(pack(1)); s.stage(pack(2))
        assertEquals(1, (1..1000).count { s.applyStaged() != null })
    }

    @Test fun `E6 다운로드 스레드와 UI 스레드가 겹쳐도 버전이 거꾸로 가지 않고 가장 새것이 남는다`() {
        val s = ContentState(pack(1))
        val packs = (2..2001).associateWith { pack(it) }
        val order = (2..2001).shuffled(Random(7)).chunked(500)
        val pool = Executors.newFixedThreadPool(6)
        val gate = CountDownLatch(1); val done = AtomicBoolean(false); val errors = ConcurrentLinkedQueue<String>()
        val stagers = order.map { chunk -> pool.submit { gate.await(); chunk.forEach { s.stage(packs.getValue(it)) } } }
        val appliers = (1..2).map {
            pool.submit {
                gate.await(); var last = 1
                while (!done.get()) {
                    s.applyStaged()?.let { if (it.contentVersion <= last) errors += "적용 버전이 거꾸로: $last → ${it.contentVersion}"; last = it.contentVersion }
                    val a = s.active.contentVersion
                    if (a < last) errors += "active 가 거꾸로: $last → $a"
                    last = maxOf(last, a)
                }
            }
        }
        gate.countDown()
        stagers.forEach { it.get(30, TimeUnit.SECONDS) }
        done.set(true)
        appliers.forEach { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        s.applyStaged()
        assertTrue(errors.isEmpty(), errors.take(5).toString())
        assertEquals(2001, s.active.contentVersion); assertNull(s.staged)
    }

    // ================= F. 진행 중 미션 =================

    /** 급제동 조건의 단계 목표치·보상을 바꾼 v2 묶음 */
    private fun v2ChangingBrake(): ContentPack = pack(2).copy(missions = bundled.missions.copy(
        conditions = bundled.missions.conditions.map { if (it.condition == ConditionType.HARSH_BRAKE_MAX) it.copy(tierTargets = listOf(8f, 7f, 6f, 5f)) else it },
        tierRewards = bundled.missions.tierRewards.map { if (it.tier == 4) it.copy(coins = 999) else it },
        texts = bundled.missions.texts + ("mission_brake_name" to "바뀐 {n}회")))

    @Test fun `F1 받은 미션은 묶음이 바뀌어도 받을 때 조건과 보상을 지킨다`() {
        val state = ContentState(bundled); val world = World { state.active.missions }; val now = Fx.MON + 9 * H
        val m = world.take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now)          // 0회 이하, 80코인
        val m2 = world.take(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 4, now)         // 0회 이하, 80코인
        assertTrue(state.stage(accepted(verifyPack(v2ChangingBrake())))); state.applyStaged()   // 4단계 보상 999
        val brakes = listOf(Fx.ev(EventType.HARSH_BRAKE), Fx.ev(EventType.HARSH_BRAKE, 20_000), Fx.ev(EventType.HARSH_BRAKE, 30_000))
        val r1 = Fx.record("s1", now + 4 * H, events = brakes); world.sessions.save(r1)
        val done = world.engine.onDriveEnd(r1, VehicleStatus(), r1.endWallMs).completed
        assertEquals(MissionStatus.FAILED, world.get(m.uid).status, "새 기준(5회)이 아니라 받을 때 기준(0회)으로 판정")
        assertEquals(0f, world.get(m.uid).target); assertEquals("급제동 없이", world.get(m.uid).title)
        assertEquals(80, done.single { it.instance.uid == m2.uid }.coins, "보상도 받을 때 값")
    }

    @Test fun `F2 다음 날 제공부터 새 묶음 기준이고 지난 기록은 옛 기준을 지킨다`() {
        var cat = bundled.missions
        val world = World { cat }; val now = Fx.MON + 9 * H
        val old = world.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now)!!
        cat = v2ChangingBrake().missions
        world.engine.refresh(P, now + Fx.DAY, null)
        val next = world.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now + Fx.DAY)!!
        assertEquals(5f, next.target); assertEquals(999, next.rewardCoins)
        assertEquals(0f, world.get(old.uid).target); assertEquals(MissionStatus.EXPIRED, world.get(old.uid).status)
    }

    @Test fun `F3 새 묶음에서 빠진 조건도 오늘 도전한 것은 끝까지 판정하고 다음 날 제공하지 않는다`() {
        var cat = core.Mx.catalog(personal = 30)
        val world = World { cat }; val now = Fx.MON + 9 * H
        val m = world.take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, now)                // 10km
        cat = cat.copy(conditions = cat.conditions.filter { it.condition != ConditionType.CLEAN_KM })
        accepted(verifyPack(pack(2).copy(missions = cat)))
        val rec = Fx.record("s1", now + 2 * H, km = 12f); world.sessions.save(rec)
        assertTrue(world.engine.onDriveEnd(rec, VehicleStatus(), rec.endWallMs).completed.any { it.instance.uid == m.uid })
        world.engine.refresh(P, now + Fx.DAY, null)
        assertTrue(world.engine.offers(P, now + Fx.DAY).none { it.condition == ConditionType.CLEAN_KM })
    }

    @Test fun `F4 프로필마다 받은 시점의 기준을 따로 지킨다`() {
        var cat = bundled.missions
        val world = World { cat }; val now = Fx.MON + 9 * H
        val a = world.engine.offer("A", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now)!!
        cat = v2ChangingBrake().missions
        val b = world.engine.offer("B", MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, now + H)!!
        assertEquals(0f, world.get(a.uid, "A").target)
        assertEquals(5f, world.get(b.uid, "B").target)
    }

    @Test fun `F5 주 중간에 묶음이 바뀌어도 이번 주 주간 미션은 다시 제공하지 않는다`() {
        var cat = core.Mx.catalog(weekly = 3)
        val world = World { cat }; val mon = Fx.MON + 9 * H
        world.engine.refresh(P, mon, null)
        val before = world.engine.offers(P, mon).map { it.uid }.toSet()
        cat = v2ChangingBrake().missions.copy(rules = cat.rules)
        world.engine.refresh(P, mon + Fx.DAY, null)
        assertEquals(before, world.engine.offers(P, mon + Fx.DAY).filter { it.kind == MissionKind.WEEKLY }.map { it.uid }.toSet())
    }

    @Test fun `F6 다음 날 개인 미션은 새 묶음의 보상으로 나온다`() {
        var cat = core.Mx.catalog(personal = 2)
        val world = World { cat }; val now = Fx.MON + 9 * H
        world.engine.refresh(P, now, null)
        cat = cat.copy(tierRewards = cat.tierRewards.map { it.copy(coins = 1) })
        world.engine.refresh(P, now + Fx.DAY, null)
        val tue = world.engine.offers(P, now + Fx.DAY)
        assertEquals(2, tue.size); assertTrue(tue.all { it.rewardCoins == 1 })
    }

    // ================= G. 아이템 =================

    @Test fun `G1 가진 아이템이 새 묶음에서 빠져도 예외가 없다`() {
        val w1 = Wallet(bundled.items)
        var st = ProfileState(P, coins = 1000)
        st = (w1.buy(st, ItemId(6)) as Wallet.BuyResult.Ok).state
        st = w1.saveStage(st, listOf(StageItem(ItemId(6))))
        val w2 = Wallet(bundled.items.filter { it.id != ItemId(6) })
        assertDoesNotThrow { w2.buy(st, ItemId(6)); w2.saveStage(st, st.placed); Unit }
        assertTrue(w2.saveStage(st, st.placed).placed.isEmpty(), "빠진 아이템은 다시 저장하면 스테이지에서 빠진다")
        assertEquals(Wallet.BuyResult.Unknown, w2.buy(st, ItemId(6)))
    }

    @Test fun `G2 아이템 종류가 바뀐 뒤 다시 놓아도 스테이지에 한 번만 있다`() {
        val w1 = Wallet(bundled.items)
        var st = ProfileState(P, coins = 1000)
        st = (w1.buy(st, ItemId(6)) as Wallet.BuyResult.Ok).state
        st = w1.saveStage(st, listOf(StageItem(ItemId(6))))
        val w2 = Wallet(bundled.items.map { if (it.id == ItemId(6)) it.copy(category = ItemCategory.PROP) else it })
        st = w2.saveStage(st, st.placed + StageItem(ItemId(6)))
        assertEquals(1, st.placed.count { it.itemId == ItemId(6) }, "placed=${st.placed}")
    }

    @Test fun `G3 가격이 바뀌면 새 가격으로 산다`() {
        val w2 = Wallet(bundled.items.map { if (it.id == ItemId(7)) it.copy(price = 10) else it })
        val r = w2.buy(ProfileState(P, coins = 50), ItemId(7)) as Wallet.BuyResult.Ok
        assertEquals(40, r.state.coins)
    }

    // ================= H. 파이프라인·내장 =================

    @Test fun `H1 종료 애니메이션은 지금 적용된 묶음의 점수 구간을 따른다`() {
        val world = World(); val state = ContentState(bundled)
        val pipe = SessionEndPipeline(world.sessions, world.profiles, world.engine, content = { state.active })
        val events = (1..10).map { Fx.ev(EventType.HARSH_ACCEL, 10_000L + it * 1000) }   // 50점
        val r1 = Fx.record("s1", Fx.MON + 9 * H, events = events)
        assertEquals(EndAnimation.OTL, pipe.onSessionEnd(r1, VehicleStatus(), r1.endWallMs).animation)
        state.stage(pack(2).copy(scoreBands = listOf(ScoreBand(0, EndAnimation.DANCE)))); state.applyStaged()
        val r2 = Fx.record("s2", Fx.MON + 11 * H, events = events)
        assertEquals(EndAnimation.DANCE, pipe.onSessionEnd(r2, VehicleStatus(), r2.endWallMs).animation)
        val r3 = Fx.record("s3", Fx.MON + 12 * H, valid = false)
        assertEquals(EndAnimation.NONE, pipe.onSessionEnd(r3, VehicleStatus(), r3.endWallMs).animation)
    }

    @Test fun `H2 내장 묶음은 규칙을 지키고 0~100 점 모두에 애니메이션이 있다`() {
        assertTrue(ContentValidator.problems(bundled).isEmpty(), ContentValidator.problems(bundled).toString())
        assertTrue(bundled.contentVersion >= 1 && bundled.minAppVersion >= 1)
        for (s in 0..100) assertTrue(bundled.animationFor(s) != EndAnimation.NONE, "score=$s")
        assertEquals(bundled, ContentCodec.decode(ContentCodec.encode(bundled)), "쓰고 다시 읽으면 같다")
    }
}

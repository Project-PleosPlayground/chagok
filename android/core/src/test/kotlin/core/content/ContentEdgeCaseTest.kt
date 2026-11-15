package core.content

import core.Fx
import core.Time
import core.character.Profile
import core.character.ProfileState
import core.character.Wallet
import core.item.ItemId
import core.item.StageItem
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.mission.MissionKind
import core.session.EndAnimation
import core.session.InMemorySessionStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** 원격 콘텐츠 업데이트(#31)의 특수 상황. 네트워크·저장소·운영 실수·앱 버전·사용자 동작·동시성. */
class ContentEdgeCaseTest {
    private val bundled = BundledContent.pack
    private val appVersion = 5
    private val v = bundled.contentVersion

    private fun pack(version: Int, minApp: Int = 1) = bundled.copy(contentVersion = version, minAppVersion = minApp)
    private fun bytes(p: ContentPack) = ContentCodec.encode(p).toByteArray(Charsets.UTF_8)
    private fun manifestFor(p: ContentPack, b: ByteArray = bytes(p)) =
        ContentManifest(p.contentVersion, p.minAppVersion, "packs/${p.contentVersion}.json", ContentUpdate.sha256Hex(b))
    private fun verify(p: ContentPack) = bytes(p).let { ContentUpdate.verify(it, manifestFor(p, it), v, appVersion) }
    private fun reason(verdict: ContentVerdict) = (verdict as? ContentVerdict.Reject)?.reason

    @Nested inner class 네트워크 {
        @Test fun `덜 받은 파일은 해시가 달라 버린다`() {
            val p = pack(v + 1); val b = bytes(p)
            val half = b.copyOf(b.size / 2)
            assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(half, manifestFor(p, b), v, appVersion)))
        }

        @Test fun `빈 응답이나 받지 못한 파일은 버린다`() {
            val p = pack(v + 1); val m = manifestFor(p)
            assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(ByteArray(0), m, v, appVersion)))
            assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(null, m, v, appVersion)))
        }

        @Test fun `에러 페이지(HTML)가 와도 버린다`() {
            val html = "<html><body>502 Bad Gateway</body></html>"
            assertNull(ContentUpdate.readManifest(html))
            val b = html.toByteArray()
            val m = ContentManifest(v + 1, 1, "x.json", ContentUpdate.sha256Hex(b))
            assertEquals(ContentRejection.PARSE_ERROR, reason(ContentUpdate.verify(b, m, v, appVersion)))
        }

        @Test fun `manifest 를 못 받으면 받지 않는다`() {
            assertNull(ContentUpdate.readManifest(null))
            assertNull(ContentUpdate.readManifest(""))
            assertEquals(ContentRejection.BAD_MANIFEST, ContentUpdate.shouldDownload(null, v, appVersion))
            assertEquals(ContentRejection.BAD_MANIFEST, reason(ContentUpdate.verify(bytes(pack(v + 1)), null, v, appVersion)))
        }

        @Test fun `받는 사이 서버 파일이 바뀌면(manifest 와 파일 불일치) 버린다`() {
            val p1 = pack(v + 1); val p2 = pack(v + 2)
            assertEquals(ContentRejection.HASH_MISMATCH, reason(ContentUpdate.verify(bytes(p2), manifestFor(p1), v, appVersion)))
        }
    }

    @Nested inner class 저장소와_기기 {
        @Test fun `저장 공간 부족 등으로 덜 써진 캐시는 무시하고 내장을 쓴다`() {
            val text = ContentCodec.encode(pack(v + 1))
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, text.substring(0, text.length / 3), appVersion))
        }

        @Test fun `캐시가 비었거나 다른 파일로 덮였으면 내장을 쓴다`() {
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, "", appVersion))
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, "\u0000\u0000\u0000", appVersion))
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, """{"contentVersion": 99}""", appVersion))
        }

        @Test fun `캐시가 값 규칙을 어기면 내장을 쓴다`() {
            val bad = pack(v + 1).copy(scoreBands = emptyList())
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, ContentCodec.encode(bad), appVersion))
        }

        @Test fun `앱을 낮은 버전으로 다시 깔면 그 버전이 못 읽는 캐시는 무시한다`() {
            val forNewApp = ContentCodec.encode(pack(v + 1, minApp = appVersion + 1))
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, forNewApp, appVersion))
        }

        @Test fun `스토어 업데이트로 내장이 더 새것이 되면 오래된 캐시는 무시한다`() {
            val newBundled = pack(v + 5)
            assertEquals(newBundled, ContentUpdate.pickAtStartup(newBundled, ContentCodec.encode(pack(v + 1)), appVersion))
        }
    }

    @Nested inner class 서버_데이터_실수 {
        @Test fun `앱이 모르는 enum 값이 있으면 묶음 전체를 버린다`() {
            val text = ContentCodec.encode(pack(v + 1)).replaceFirst("\"PROP\"", "\"HAT\"")
            val b = text.toByteArray()
            val m = ContentManifest(v + 1, 1, "x.json", ContentUpdate.sha256Hex(b))
            assertEquals(ContentRejection.PARSE_ERROR, reason(ContentUpdate.verify(b, m, v, appVersion)))
            assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, text, appVersion))
        }

        @Test fun `새 종류는 minAppVersion 이 높으면 구버전 앱이 받지도 않는다`() {
            val m = manifestFor(pack(v + 1, minApp = appVersion + 1))
            assertEquals(ContentRejection.APP_TOO_OLD, ContentUpdate.shouldDownload(m, v, appVersion))
        }

        @Test fun `필수 필드가 빠지거나 타입이 틀리면 버린다`() {
            val noItems = ContentCodec.encode(pack(v + 1)).replace("\"items\"", "\"itemz\"")
            val wrongType = ContentCodec.encode(pack(v + 1)).replace("\"contentVersion\":${v + 1}", "\"contentVersion\":\"two\"")
            for (t in listOf(noItems, wrongType)) {
                val b = t.toByteArray(); val m = ContentManifest(v + 1, 1, "x.json", ContentUpdate.sha256Hex(b))
                assertEquals(ContentRejection.PARSE_ERROR, reason(ContentUpdate.verify(b, m, v, appVersion)), t.take(40))
            }
        }

        @Test fun `선택 필드가 빠진 형식도 읽는다`() {
            val text = ContentCodec.encode(pack(v + 1)).replace(Regex(",\"iconKey\":\"[a-z_]+\""), "").replace(Regex(",\"itemTexts\":[{][^}]*[}]"), "")
            assertFalse("iconKey" in text); assertFalse("itemTexts" in text)
            val p = ContentCodec.decode(text)
            assertTrue(p.missions.conditions.all { it.iconKey == null }); assertTrue(p.itemTexts.isEmpty())
        }

        @Test fun `BOM 이 붙은 파일도 읽는다`() {
            val b = ("\uFEFF" + ContentCodec.encode(pack(v + 1))).toByteArray(Charsets.UTF_8)
            val m = ContentManifest(v + 1, 1, "x.json", ContentUpdate.sha256Hex(b))
            assertTrue(ContentUpdate.verify(b, m, v, appVersion) is ContentVerdict.Accept)
            assertNotNull(ContentUpdate.readManifest("\uFEFF" + """{"contentVersion":2,"minAppVersion":1,"path":"a.json","sha256":"${"a".repeat(64)}"}"""))
        }

        @Test fun `값 규칙을 어기면 이유를 모아 버린다`() {
            val c = bundled.missions
            val brake = c.row(ConditionType.HARSH_BRAKE_MAX)!!
            val rate = c.row(ConditionType.SIGNALED_RATE_MIN)!!
            val bad = pack(v + 1).copy(
                missions = c.copy(
                    conditions = listOf(brake, brake.copy(tierTargets = listOf(3f, 2f, 1f, -1f)), rate.copy(tierTargets = listOf(80f, 90f, 100f, 110f))),
                    tierRewards = c.tierRewards.map { if (it.tier == 1) it.copy(coins = -100) else it },
                ),
                items = bundled.items + bundled.items[0].copy(price = -1),
                scoreBands = listOf(ScoreBand(90, EndAnimation.DANCE), ScoreBand(90, EndAnimation.OK)),
            )
            val verdict = verify(bad) as ContentVerdict.Reject
            assertEquals(ContentRejection.INVALID_CONTENT, verdict.reason)
            val d = verdict.detail.joinToString("\n")
            listOf("코인 보상이 음수", "조건ID 중복", "100 을 넘는다", "목표치가 음수", "아이템 id 중복", "가격이 음수", "0점을 받는 구간이 없다", "점수 구간 하한 중복")
                .forEach { assertTrue(it in d, "검사 누락: $it\n$d") }
        }

        @Test fun `미션이나 아이템이 하나도 없으면 버린다`() {
            val empty = pack(v + 1).copy(missions = bundled.missions.copy(conditions = emptyList()), items = emptyList())
            val d = (verify(empty) as ContentVerdict.Reject).detail
            assertTrue(d.any { "미션 조건이 하나도 없다" in it }); assertTrue(d.any { "아이템이 하나도 없다" in it })
        }

        @Test fun `버전이 0 이하면 버린다`() {
            assertTrue(ContentValidator.problems(bundled.copy(contentVersion = 0, minAppVersion = 0)).size >= 2)
        }

        @Test fun `manifest 의 해시·경로가 이상하면 읽지 않는다`() {
            fun mf(path: String, sha: String) = """{"contentVersion":2,"minAppVersion":1,"path":"$path","sha256":"$sha"}"""
            val ok = "0".repeat(64)
            assertNotNull(ContentUpdate.readManifest(mf("packs/2.json", ok)))
            assertNotNull(ContentUpdate.readManifest(mf("packs/2.json", "AB".repeat(32))))   // 대문자 해시 허용
            listOf(mf("packs/2.json", "123"), mf("packs/2.json", "z".repeat(64)), mf("", ok), mf("   ", ok),
                mf("/etc/passwd", ok), mf("../other/2.json", ok), mf("packs/../../x", ok), mf("https://evil.example/x.json", ok),
                """{"contentVersion":2}""", "[]", "null")
                .forEach { assertNull(ContentUpdate.readManifest(it), it) }
        }

        @Test fun `되돌린다고 버전을 낮춰 올리면 받지 않는다(더 높은 버전으로 다시 배포해야 함)`() {
            assertEquals(ContentRejection.NOT_NEWER, ContentUpdate.shouldDownload(manifestFor(pack(v + 1)), v + 3, appVersion))
        }

        @Test fun `아주 큰 묶음도 읽고 검사한다`() {
            val many = (1..5_000).map { bundled.items[0].copy(id = ItemId(it)) }
            val big = pack(v + 1).copy(items = many)
            assertTrue(verify(big) is ContentVerdict.Accept)
        }
    }

    @Nested inner class 사용자_동작과_진행_중_데이터 {
        @Test fun `탭을 여러 번 빠르게 전환해도 한 번만 적용된다`() {
            val s = ContentState(bundled); s.stage(pack(v + 1))
            assertNotNull(s.applyStaged())
            repeat(5) { assertNull(s.applyStaged()) }
            assertEquals(v + 1, s.active.contentVersion)
        }

        @Test fun `적용 전에 더 새 묶음이 오면 그것으로 바뀐다`() {
            val s = ContentState(bundled)
            assertTrue(s.stage(pack(v + 1))); assertTrue(s.stage(pack(v + 2)))
            assertEquals(v + 2, s.applyStaged()?.contentVersion)
        }

        @Test fun `받은 미션은 제공할 때의 목표를 유지하고, 새 묶음은 다음 제공부터 쓴다`() {
            val store = InMemoryMissionStore(); val sessions = InMemorySessionStore()
            var cat = bundled.missions
            val engine = MissionEngine(store, sessions, missions = { cat })
            val day0 = Fx.MON + 9 * 3_600_000L
            val before = engine.offer(Profile.DEFAULT, MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, day0)!!
            cat = cat.copy(conditions = cat.conditions.map { if (it.condition == ConditionType.CLEAN_KM) it.copy(tierTargets = listOf(5f, 999f, 1000f, 1001f)) else it })
            assertEquals(before.target, store.list(Profile.DEFAULT).single().target)
            val next = engine.offer(Profile.DEFAULT, MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, day0 + Time.DAY_MS)!!
            assertEquals(999f, next.target)
        }

        @Test fun `묶음에서 빠진 아이템은 스테이지에 저장되지 않는다(보유 기록은 남는다)`() {
            val owned = ProfileState(Profile.DEFAULT, coins = 0, owned = setOf(ItemId(1)))
            val wallet = Wallet(bundled.items.filter { it.id != ItemId(1) })
            val saved = wallet.saveStage(owned, listOf(StageItem(ItemId(1))))
            assertTrue(saved.placed.isEmpty()); assertEquals(setOf(ItemId(1)), saved.owned)
            assertEquals(Wallet.BuyResult.Unknown, wallet.buy(owned, ItemId(1)))
        }
    }

    @Nested inner class 동시성 {
        @Test fun `다운로드 스레드 stage 와 UI 스레드 apply 가 겹쳐도 묶음을 잃지 않는다`() {
            repeat(200) { round ->
                val s = ContentState(bundled)
                val pool = Executors.newFixedThreadPool(4)
                val start = CountDownLatch(1)
                val applied = AtomicInteger(0)
                val versions = (v + 1)..(v + 50)
                val jobs = versions.map { ver -> pool.submit { start.await(); s.stage(pack(ver)) } } +
                    (1..50).map { pool.submit { start.await(); if (s.applyStaged() != null) applied.incrementAndGet() } }
                start.countDown(); jobs.forEach { it.get(5, TimeUnit.SECONDS) }
                pool.shutdown()
                s.applyStaged()
                assertEquals(versions.last, s.active.contentVersion, "round $round")   // 가장 새 묶음이 결국 적용된다
                assertNull(s.staged)
            }
        }

        @Test fun `판단 함수는 여러 스레드에서 동시에 불러도 같은 결과`() {
            val p = pack(v + 1); val b = bytes(p); val m = manifestFor(p, b)
            val pool = Executors.newFixedThreadPool(8)
            val results = (1..64).map { pool.submit<ContentVerdict> { ContentUpdate.verify(b, m, v, appVersion) } }.map { it.get(5, TimeUnit.SECONDS) }
            pool.shutdown()
            assertTrue(results.all { it is ContentVerdict.Accept })
        }
    }
}

package ai.safetypoc.app.content

import core.content.BundledContent
import core.content.ContentCodec
import core.content.ContentRejection
import core.content.ContentState
import core.content.ContentUpdate
import core.content.UpdateFailure
import core.content.UpdateRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RemoteContentUpdaterTest {
    private val dir: File = Files.createTempDirectory("content-test").toFile()
    private val files = ContentFiles(dir)
    private val state = ContentState(BundledContent.pack)   // v1
    private val base = "https://x.supabase.co/storage/v1/object/public/content"
    private var now = 1_793_000_000_000L
    private val served = HashMap<String, Fetched?>()
    private val fetched = ArrayList<String>()
    private val fetcher = Fetcher { url, _ -> fetched += url; served[url] }
    private val updater = RemoteContentUpdater(state, "$base/", appVersion = 1, files = files, fetch = fetcher, now = { now }, random = { 0.5 })

    private val pack2 = BundledContent.pack.copy(contentVersion = 2)
    private val pack2Bytes = ContentCodec.encode(pack2).toByteArray()
    private fun manifest(version: Int = 2, minApp: Int = 1, path: String = "packs/content-v2.json", sha: String = ContentUpdate.sha256Hex(pack2Bytes)) =
        Fetched("""{"contentVersion":$version,"minAppVersion":$minApp,"path":"$path","sha256":"$sha"}""".toByteArray(), null)

    @Test fun `manifest 주소는 앱 버전별, 끝 슬래시는 정리한다`() {
        assertEquals("$base/manifest/1.json", updater.manifestUrl())
        assertEquals("$base/packs/content-v2.json", updater.fileUrl("packs/content-v2.json"))
        assertTrue(updater.enabled)
        assertFalse(RemoteContentUpdater(state, "  ", 1, files, fetcher).enabled)
    }

    @Test fun `새 묶음을 받아 검증하면 파일에 저장하고 적용 대기에 올린다`() {
        served["$base/manifest/1.json"] = manifest()
        served["$base/packs/content-v2.json"] = Fetched(pack2Bytes, pack2Bytes.size.toLong())
        assertEquals(RemoteContentUpdater.Outcome.Staged(2), updater.check())
        assertEquals("탭 전환 전엔 그대로", 1, state.active.contentVersion)
        assertEquals(2, state.applyStaged()!!.contentVersion)
        assertTrue(File(dir, ContentFiles.PACK).isFile); assertFalse(File(dir, "${ContentFiles.PACK}.tmp").exists())
        assertEquals("다음 실행에 바로 쓴다", 2, ContentUpdate.pickAtStartupFromBytes(BundledContent.pack, files.readPack(), 1).contentVersion)
        assertEquals(core.content.RetryState(), files.readRetry())
        // 같은 manifest 를 다시 보면 새것 아님
        assertEquals(RemoteContentUpdater.Outcome.NoUpdate(ContentRejection.NOT_NEWER), updater.check())
    }

    @Test fun `manifest 를 못 받으면 통신 실패로 기록하고 간격 안에는 다시 확인하지 않는다`() {
        assertEquals(RemoteContentUpdater.Outcome.Failed(UpdateFailure.NETWORK), updater.check())
        val r = files.readRetry()
        assertEquals(1, r.networkFailures); assertFalse(UpdateRetry.canCheck(r, now))
        assertEquals(RemoteContentUpdater.Outcome.Waiting, updater.check())
        now += 2 * 60_000L   // 1분 ±20% 뒤
        served["$base/manifest/1.json"] = manifest()
        served["$base/packs/content-v2.json"] = Fetched(pack2Bytes, null)
        assertEquals(RemoteContentUpdater.Outcome.Staged(2), updater.check())
    }

    @Test fun `해시가 다르면 검증 실패로 기록하고 이전 콘텐츠를 그대로 쓴다`() {
        served["$base/manifest/1.json"] = manifest(sha = "0".repeat(64))
        served["$base/packs/content-v2.json"] = Fetched(pack2Bytes, null)
        assertEquals(RemoteContentUpdater.Outcome.Failed(UpdateFailure.INTEGRITY, ContentRejection.HASH_MISMATCH), updater.check())
        assertNull(state.staged); assertNull(files.readPack())
        val r = files.readRetry(); assertEquals(2, r.integrityRelease); assertEquals(1, r.integrityFailures)
        assertFalse(UpdateRetry.canDownload(r, 2, now))
        assertEquals("같은 릴리스는 간격을 지킨다", RemoteContentUpdater.Outcome.Waiting, updater.check())
        // 고쳐 올린 새 릴리스(v3)는 바로 받는다
        val pack3 = ContentCodec.encode(BundledContent.pack.copy(contentVersion = 3)).toByteArray()
        served["$base/manifest/1.json"] = manifest(version = 3, path = "packs/content-v3.json", sha = ContentUpdate.sha256Hex(pack3))
        served["$base/packs/content-v3.json"] = Fetched(pack3, null)
        assertEquals(RemoteContentUpdater.Outcome.Staged(3), updater.check())
    }

    @Test fun `덜 받은 묶음은 통신 실패로 본다 (해시 실패 아님)`() {
        served["$base/manifest/1.json"] = manifest()
        served["$base/packs/content-v2.json"] = Fetched(pack2Bytes.copyOf(pack2Bytes.size / 2), pack2Bytes.size.toLong())
        assertEquals(RemoteContentUpdater.Outcome.Failed(UpdateFailure.NETWORK), updater.check())
        assertEquals(1, files.readRetry().networkFailures); assertEquals(0, files.readRetry().integrityFailures)
    }

    @Test fun `이 앱 버전으로 못 읽는 묶음과 깨진 manifest`() {
        served["$base/manifest/1.json"] = manifest(minApp = 9)
        assertEquals(RemoteContentUpdater.Outcome.NoUpdate(ContentRejection.APP_TOO_OLD), updater.check())
        assertTrue("묶음은 받지 않는다", fetched.none { it.contains("packs/") })
        served["$base/manifest/1.json"] = Fetched("{not json".toByteArray(), 9L)
        assertEquals(RemoteContentUpdater.Outcome.Failed(UpdateFailure.INTEGRITY), updater.check())
        assertEquals(1, files.readRetry().manifestFailures)
        served["$base/manifest/1.json"] = Fetched("{not".toByteArray(), 100L)   // Content-Length 보다 적게 옴 → 받다 끊김
        now += 60 * 60_000L
        assertEquals(RemoteContentUpdater.Outcome.Failed(UpdateFailure.NETWORK), updater.check())
    }

    @Test fun `파일 저장소 - 임시 파일 뒤 교체, 재시도 상태 저장·복원`() {
        assertNull(files.readPack())
        assertTrue(files.writePack("a".toByteArray())); assertEquals("a", files.readPack()!!.toString(Charsets.UTF_8))
        assertTrue(files.writePack("bb".toByteArray())); assertEquals("bb", files.readPack()!!.toString(Charsets.UTF_8))
        val s = core.content.RetryState(networkFailures = 3, networkRetryAtMs = 42)
        files.writeRetry(s); assertEquals(s, files.readRetry())
        File(dir, ContentFiles.RETRY).writeText("garbage"); assertEquals(core.content.RetryState(), files.readRetry())
        assertNotNull(files)
    }
}

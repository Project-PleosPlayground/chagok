package ai.safetypoc.app.data.store

import core.character.Profile
import core.session.EventRecord
import core.session.SessionRecord
import core.store.DocumentProfileStore
import core.store.DocumentSessionStore
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 파일 문서 저장소 블랙박스 테스트. 계약만 보고 쓴다 */
class FileDocumentStoreBlackBoxTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun root(): File = File(tmp.root, "docs")

    private fun jsonFiles(dir: File): List<File> = dir.listFiles()?.filter { it.isFile }.orEmpty()

    @Test fun `쓴 것을 바로 읽는다`() {
        val s = FileDocumentStore(root())
        s.write("c", "k1", "{\"a\":1}")
        s.write("c", "k2", "두번째")
        assertEquals(mapOf("k1" to "{\"a\":1}", "k2" to "두번째"), s.readAll("c"))
        s.close()
    }

    @Test fun `없는 컬렉션은 빈 맵`() {
        val s = FileDocumentStore(root())
        assertTrue(s.readAll("none").isEmpty())
        s.close()
    }

    @Test fun `새 인스턴스에서 다시 읽는다`() {
        val a = FileDocumentStore(root())
        a.write("c", "k", "v")
        a.write("d", "k", "w")
        a.close()
        val b = FileDocumentStore(root())
        assertEquals(mapOf("k" to "v"), b.readAll("c"))
        assertEquals(mapOf("k" to "w"), b.readAll("d"))
        b.close()
    }

    @Test fun `파일 위치는 루트-컬렉션-키 json`() {
        val s = FileDocumentStore(root())
        s.write("profiles", "p1", "v")
        s.flush()
        assertTrue(File(root(), "profiles/p1.json").isFile)
        assertEquals("v", File(root(), "profiles/p1.json").readText(Charsets.UTF_8))
        s.close()
    }

    @Test fun `특수 문자 키 왕복`() {
        val keys = listOf("a/b c_1", "한글 키", "x%y+z", "..", "?:<>|\"","a\\b", "점.점.json")
        val a = FileDocumentStore(root())
        keys.forEachIndexed { i, k -> a.write("c", k, "v$i") }
        assertEquals(keys.mapIndexed { i, k -> k to "v$i" }.toMap(), a.readAll("c"))
        a.close()
        // 파일은 컬렉션 폴더 바로 아래에만 있고, 이름을 풀면 키다
        val dir = File(root(), "c")
        val files = dir.listFiles()!!.toList()
        assertTrue("하위 폴더가 생겼다: $files", files.all { it.isFile })
        assertEquals(keys.size, files.size)
        assertTrue(files.all { it.name.endsWith(".json") })
        assertEquals(keys.toSet(), files.map { URLDecoder.decode(it.name.removeSuffix(".json"), "UTF-8") }.toSet())
        // 다시 열어도 같다
        val b = FileDocumentStore(root())
        assertEquals(keys.mapIndexed { i, k -> k to "v$i" }.toMap(), b.readAll("c"))
        b.close()
    }

    @Test fun `내용의 유니코드와 줄바꿈 보존`() {
        val doc = "{\"t\":\"한글 ✓ \\\"q\\\"\"}\n둘째 줄\r\n\t탭"
        val a = FileDocumentStore(root())
        a.write("c", "k", doc)
        a.close()
        val b = FileDocumentStore(root())
        assertEquals(doc, b.readAll("c")["k"])
        b.close()
    }

    @Test fun `호출 순서대로 반영된다`() {
        val s = FileDocumentStore(root())
        s.write("c", "k", "v1")
        s.write("c", "k", "v2")
        s.delete("c", "k")
        s.write("c", "k", "v3")
        s.write("c", "gone", "x")
        s.delete("c", "gone")
        for (i in 0 until 200) s.write("c", "many", "n$i")
        assertEquals(mapOf("k" to "v3", "many" to "n199"), s.readAll("c"))
        s.close()
        val b = FileDocumentStore(root())
        assertEquals(mapOf("k" to "v3", "many" to "n199"), b.readAll("c"))
        b.close()
    }

    @Test fun `delete 는 파일을 지우고 없는 키도 예외 없음`() {
        val s = FileDocumentStore(root())
        s.write("c", "k", "v")
        s.write("c", "j", "w")
        s.delete("c", "k")
        s.delete("c", "없음")
        s.delete("없는컬렉션", "k")
        s.flush()
        assertEquals(mapOf("j" to "w"), s.readAll("c"))
        assertFalse(File(root(), "c/k.json").exists())
        s.close()
    }

    @Test fun `deleteAll 은 컬렉션을 없앤다`() {
        val s = FileDocumentStore(root())
        s.write("c", "k", "v")
        s.write("c", "j", "w")
        s.write("other", "k", "o")
        s.deleteAll("c")
        s.deleteAll("없는컬렉션")
        assertTrue(s.readAll("c").isEmpty())
        assertEquals(mapOf("k" to "o"), s.readAll("other"))
        s.flush()
        assertFalse(File(root(), "c").exists())
        // 지운 뒤 다시 쓸 수 있다
        s.write("c", "n", "new")
        assertEquals(mapOf("n" to "new"), s.readAll("c"))
        s.close()
    }

    @Test fun `flush 뒤 임시 파일이 남지 않는다`() {
        val s = FileDocumentStore(root())
        for (i in 0 until 50) s.write("c", "k${i % 5}", "v$i")
        s.flush()
        val dir = File(root(), "c")
        val names = jsonFiles(dir).map { it.name }.sorted()
        assertEquals((0 until 5).map { "k$it.json" }, names)
        assertTrue(dir.listFiles()!!.all { it.isFile && it.name.endsWith(".json") })
        s.close()
    }

    @Test fun `close 는 남은 쓰기를 마치고 이후 쓰기는 무시`() {
        val a = FileDocumentStore(root())
        for (i in 0 until 100) a.write("c", "k$i", "v$i")
        a.close()
        // 닫은 뒤 쓰기·지우기는 예외 없이 무시
        a.write("c", "after", "x")
        a.delete("c", "k0")
        a.deleteAll("c")
        a.close()   // 두 번 닫아도 예외 없음
        val b = FileDocumentStore(root())
        val all = b.readAll("c")
        assertEquals(100, all.size)
        assertEquals("v0", all["k0"])
        assertEquals("v99", all["k99"])
        assertFalse(all.containsKey("after"))
        b.close()
    }

    @Test fun `여러 스레드에서 쓴 것이 모두 남는다`() {
        val s = FileDocumentStore(root())
        val pool = Executors.newFixedThreadPool(4)
        val done = CountDownLatch(4)
        for (t in 0 until 4) pool.execute {
            try { for (i in 0 until 25) s.write("c", "t$t-$i", "v$t-$i") } finally { done.countDown() }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()
        val all = s.readAll("c")
        assertEquals(100, all.size)
        assertEquals("v3-24", all["t3-24"])
        s.close()
    }

    // ---- :core 스토어와 연결 ----

    @Test fun `프로필 스토어가 파일 저장소로 왕복한다`() {
        val a = FileDocumentStore(root())
        val p = DocumentProfileStore(a)
        p.upsert(Profile("p/1 한글", "이름", 123L))
        a.close()
        val b = FileDocumentStore(root())
        assertEquals(listOf(Profile("p/1 한글", "이름", 123L)), DocumentProfileStore(b).profiles())
        b.close()
    }

    @Test fun `세션 스토어가 파일 저장소로 왕복한다`() {
        val e = DrivingEvent(EventType.HARSH_BRAKE, 2, 1_000, 1_700, 12.5f, false, "detail")
        val rec = SessionRecord(
            "s1", "p1", SessionResult(0, 600_000, 5.5f, listOf(e), 90, 95, 85, 100, true, 3),
            listOf(EventRecord(e, 321.5f, "테헤란로", 37.5, 127.0)), 10_000, 610_000, "출발", null, 80, null,
        )
        val a = FileDocumentStore(root())
        DocumentSessionStore(a).save(rec)
        a.close()
        val b = FileDocumentStore(root())
        assertEquals(rec, DocumentSessionStore(b).get("s1"))
        b.close()
    }

    @Test fun `깨진 파일은 스토어가 건너뛰고 지우지 않는다`() {
        val dir = File(root(), "profiles").apply { mkdirs() }
        File(dir, "bad.json").writeText("not json", Charsets.UTF_8)
        val a = FileDocumentStore(root())
        val skipped = ArrayList<String>()
        val store = DocumentProfileStore(a, onSkip = { skipped += it })
        store.upsert(Profile("p1", "a", 1))
        a.close()
        assertTrue(File(dir, "bad.json").isFile)
        val b = FileDocumentStore(root())
        assertEquals(listOf(Profile("p1", "a", 1)), DocumentProfileStore(b).profiles())
        b.close()
        assertTrue("$skipped", skipped.any { it.contains("bad") })
    }
}

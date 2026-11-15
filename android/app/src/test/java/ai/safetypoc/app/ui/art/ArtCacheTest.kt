package ai.safetypoc.app.ui.art

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** 이미지 값 → assets 경로, 없을 때 null, 캐시 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtCacheTest {
    /** 가짜 assets: 경로 → 파일 내용. 내용이 "broken" 이면 디코딩 실패, "throw" 면 읽다가 IOException, "oom" 이면 OutOfMemoryError */
    private class FakeAssets(val files: Map<String, String>) {
        val opened = mutableListOf<String>()
        fun open(path: String): InputStream? {
            opened += path
            return files[path]?.let { ByteArrayInputStream(it.toByteArray()) }
        }
    }

    private fun decode(input: InputStream): String? = when (val s = input.readBytes().decodeToString()) {
        "broken" -> null
        "throw" -> throw IOException("읽기 실패")
        "oom" -> throw OutOfMemoryError("메모리 부족")
        else -> s
    }

    private fun TestScope.cache(assets: FakeAssets, maxBytes: Long = Long.MAX_VALUE) =
        ArtCache(assets::open, ::decode, sizeOf = { it.length.toLong() }, maxBytes = maxBytes, io = StandardTestDispatcher(testScheduler))

    @Test
    fun `이미지 값으로 assets 의 item 폴더 PNG를 연다`() = runTest {
        val assets = FakeAssets(mapOf("item/Pet/Haechi/Haechi_Lv1.png" to "haechi1", "item/Accessories/Fortune_pouch.png" to "pouch"))
        val c = cache(assets)
        assertEquals("haechi1", c.load("Pet/Haechi/Haechi_Lv1"))
        assertEquals("pouch", c.load("Accessories/Fortune_pouch"))
        assertEquals(listOf("item/Pet/Haechi/Haechi_Lv1.png", "item/Accessories/Fortune_pouch.png"), assets.opened)
    }

    @Test
    fun `그림이 없으면 null 이고 다시 열지 않는다`() = runTest {
        val assets = FakeAssets(emptyMap())
        val c = cache(assets)
        assertNull(c.load("Accessories/Cloud"))
        assertTrue(c.isMissing("Accessories/Cloud"))
        assertNull(c.load("Accessories/Cloud"))
        assertEquals(1, assets.opened.size)
    }

    @Test
    fun `대소문자가 다르면 다른 그림이다`() = runTest {
        val assets = FakeAssets(mapOf("item/Accessories/Moon_jar.png" to "jar"))
        assertNull(cache(assets).load("Accessories/Moon_Jar"))
    }

    @Test
    fun `디코딩하지 못하거나 읽다가 실패하면 null`() = runTest {
        val assets = FakeAssets(mapOf("item/A/Broken.png" to "broken", "item/A/Throw.png" to "throw"))
        val c = cache(assets)
        assertNull(c.load("A/Broken"))
        assertNull(c.load("A/Throw"))
        assertTrue(c.isMissing("A/Broken") && c.isMissing("A/Throw"))
    }

    @Test
    fun `메모리가 모자라 못 읽으면 null 이고 캐시를 비우며 다음에 다시 읽는다`() = runTest {
        val assets = FakeAssets(mapOf("item/A/Kept.png" to "kept", "item/A/Big.png" to "oom"))
        val c = cache(assets)
        c.load("A/Kept")
        assertNull(c.load("A/Big"))
        assertFalse(c.isMissing("A/Big"))
        assertNull("캐시를 비운다", c.cached("A/Kept"))
        c.load("A/Big")
        assertEquals(2, assets.opened.count { it == "item/A/Big.png" })
    }

    @Test
    fun `규칙에 맞지 않는 이미지 값은 파일을 열지 않고 null`() = runTest {
        val assets = FakeAssets(mapOf("item/../secret.png" to "x"))
        val c = cache(assets)
        for (bad in listOf("../secret", "Pet/Bird.png", "/Pet/Bird", "Pet Bird", "")) assertNull(bad, c.load(bad))
        assertEquals(emptyList<String>(), assets.opened)
    }

    @Test
    fun `한 번 읽은 그림은 캐시에서 준다`() = runTest {
        val assets = FakeAssets(mapOf("item/Pet/Bird/Bird_Lv3.png" to "bird3"))
        val c = cache(assets)
        assertNull(c.cached("Pet/Bird/Bird_Lv3"))
        c.load("Pet/Bird/Bird_Lv3")
        assertEquals("bird3", c.cached("Pet/Bird/Bird_Lv3"))
        assertEquals("bird3", c.load("Pet/Bird/Bird_Lv3"))
        assertEquals(1, assets.opened.size)
    }

    @Test
    fun `캐시가 넘치면 오래 안 쓴 그림부터 버린다`() = runTest {
        val assets = FakeAssets(mapOf("item/A/One.png" to "1111", "item/A/Two.png" to "2222", "item/A/Three.png" to "3333"))
        val c = cache(assets, maxBytes = 8)
        c.load("A/One"); c.load("A/Two")
        c.load("A/One")              // One 을 최근에 씀
        c.load("A/Three")            // 8 바이트를 넘으므로 Two 를 버린다
        assertEquals("1111", c.cached("A/One"))
        assertNull(c.cached("A/Two"))
        assertEquals("3333", c.cached("A/Three"))
        assertFalse("버린 그림은 없는 그림이 아니다", c.isMissing("A/Two"))
        assertEquals("2222", c.load("A/Two"))
    }

    @Test
    fun `캐시보다 큰 그림은 주되 담지 않는다`() = runTest {
        val assets = FakeAssets(mapOf("item/A/Big.png" to "123456789"))
        val c = cache(assets, maxBytes = 8)
        assertEquals("123456789", c.load("A/Big"))
        assertNull(c.cached("A/Big"))
    }

    @Test
    fun `비우면 캐시만 비우고 없는 그림 목록은 남긴다`() = runTest {
        val assets = FakeAssets(mapOf("item/A/One.png" to "1"))
        val c = cache(assets)
        c.load("A/One"); c.load("A/None")
        c.clear()
        assertNull(c.cached("A/One"))
        assertTrue(c.isMissing("A/None"))
    }
}

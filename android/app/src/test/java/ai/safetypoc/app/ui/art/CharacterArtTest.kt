package ai.safetypoc.app.ui.art

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/** 캐릭터 그림 경로 후보와, 후보 가운데 있는 그림 고르기 */
@OptIn(ExperimentalCoroutinesApi::class)
class CharacterArtTest {
    private class FakeAssets(val files: Map<String, String>) {
        val opened = mutableListOf<String>()
        fun open(path: String): InputStream? {
            opened += path
            return files[path]?.let { ByteArrayInputStream(it.toByteArray()) }
        }
    }

    private fun TestScope.cache(assets: FakeAssets) = ArtCache(
        assets::open, { it.readBytes().decodeToString() }, sizeOf = { it.length.toLong() }, maxBytes = Long.MAX_VALUE,
        io = StandardTestDispatcher(testScheduler), assetPath = { it },
    )

    @Test fun `정차 중에는 단계별 정지 그림, 없으면 프로필 그림`() {
        assertEquals(
            listOf("character/grandeur/still_lv3.png", "character/grandeur/profile.png"),
            CharacterArt.candidates("grandeur", 3, driving = false),
        )
    }

    @Test fun `주행 중에는 주행 자세를 먼저 찾는다`() {
        assertEquals(
            listOf("character/grandeur/drive_lv2.png", "character/grandeur/still_lv2.png", "character/grandeur/profile.png"),
            CharacterArt.candidates("grandeur", 2, driving = true),
        )
    }

    @Test fun `규칙에 맞지 않는 차종 이름이면 후보가 없다`() {
        for (bad in listOf("", "../x", "Grandeur", "santa fe", "a/b")) {
            assertTrue(bad, CharacterArt.candidates(bad, 1, driving = false).isEmpty())
            assertTrue(bad, CharacterArt.profileCandidates(bad).isEmpty())
        }
    }

    @Test fun `단계별 그림이 없는 차종은 프로필 그림을 쓴다`() = runTest {
        val assets = FakeAssets(mapOf("character/ioniq5/profile.png" to "profile"))
        val c = cache(assets)
        assertEquals("profile", c.loadFirst(CharacterArt.candidates("ioniq5", 4, driving = true)))
        assertEquals(
            listOf("character/ioniq5/drive_lv4.png", "character/ioniq5/still_lv4.png", "character/ioniq5/profile.png"),
            assets.opened,
        )
    }

    @Test fun `앞 후보가 있으면 뒤 후보는 열지 않는다`() = runTest {
        val assets = FakeAssets(mapOf("character/grandeur/drive_lv1.png" to "drive", "character/grandeur/still_lv1.png" to "still"))
        val c = cache(assets)
        assertEquals("drive", c.loadFirst(CharacterArt.candidates("grandeur", 1, driving = true)))
        assertEquals(listOf("character/grandeur/drive_lv1.png"), assets.opened)
    }

    @Test fun `캐시에서 고를 때 아직 읽지 않은 앞 후보가 있으면 뒤 후보를 쓰지 않는다`() = runTest {
        val assets = FakeAssets(mapOf("character/grandeur/still_lv1.png" to "still", "character/grandeur/profile.png" to "profile"))
        val c = cache(assets)
        val keys = CharacterArt.candidates("grandeur", 1, driving = false)
        c.load("character/grandeur/profile.png")
        assertNull("정지 그림을 아직 읽지 않았다", c.firstCached(keys))
        c.loadFirst(keys)
        assertEquals("still", c.firstCached(keys))
    }

    @Test fun `캐시에서 고를 때 없는 것으로 확인된 앞 후보는 건너뛴다`() = runTest {
        val assets = FakeAssets(mapOf("character/ioniq3/profile.png" to "profile"))
        val c = cache(assets)
        val keys = CharacterArt.candidates("ioniq3", 2, driving = false)
        c.loadFirst(keys)
        assertEquals("profile", c.firstCached(keys))
    }

    @Test fun `후보가 모두 없으면 null`() = runTest {
        val c = cache(FakeAssets(emptyMap()))
        val keys = CharacterArt.candidates("staria", 1, driving = false)
        assertNull(c.loadFirst(keys))
        assertNull(c.firstCached(keys))
    }
}

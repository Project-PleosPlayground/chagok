package ai.safetypoc.app.ui.art

import core.content.BundledContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 내장 아이템의 이미지 값과 기획/디자인/Image/Item/ 의 파일 이름이 대소문자까지 같은지.
 * Windows·macOS 파일 시스템은 대소문자를 가리지 않아 빌드는 되지만, APK 안 assets 는 가리므로 기기에서만 그림이 빠진다.
 * 아직 그림이 없는 아이템은 검사하지 않는다(앱은 회색 자리를 그린다).
 */
class ItemArtFilesTest {
    private val root = File("../../기획/디자인/Image/Item")

    /** Item/ 아래 파일의 상대 경로(확장자 뺌) → 실제 이름. 키는 소문자 */
    private fun filesByLowercase(extension: String): Map<String, String> =
        root.walkTopDown().filter { it.isFile && it.extension == extension }
            .map { it.relativeTo(root).invariantSeparatorsPath.removeSuffix(".$extension") }
            .associateBy { it.lowercase() }

    @Test
    fun `아이템 그림 폴더가 있다`() {
        assertTrue(root.absolutePath, root.isDirectory)
    }

    @Test
    fun `PNG가 있는 아이템은 이미지 값과 파일 경로가 대소문자까지 같다`() {
        val pngs = filesByLowercase("png")
        val wrongCase = BundledContent.pack.items.mapNotNull { item ->
            pngs[item.imageKey.lowercase()]?.takeIf { it != item.imageKey }?.let { "${item.id}: ${item.imageKey} ≠ $it.png" }
        }
        assertEquals(emptyList<String>(), wrongCase)
    }

    @Test
    fun `아이템 그림 폴더의 PNG 이름은 이미지 값 규칙에 맞는다`() {
        val bad = filesByLowercase("png").values.filterNot { core.item.ItemImage.isValid(it) }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun `빌드가 그림을 넣는 assets 폴더와 앱이 읽는 폴더가 같다`() {
        val gradle = File("build.gradle.kts").readText()
        val declared = Regex("""val itemArtAssetDir = "([^"]*)"""").find(gradle)?.groupValues?.get(1)
        assertEquals(core.item.ItemImage.ASSET_DIR, declared)
    }
}

package ai.safetypoc.app.ui.profile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 프로필·온보딩 화면 코드가 hex·dp·sp 숫자를 직접 쓰지 않는지. 배치 값은 *Sizes.kt 의 이름 붙은 값만 쓴다 */
class ScreenValuesTest {
    private val dirs = listOf("profile", "onboarding").map { File("src/main/java/ai/safetypoc/app/ui/$it") }
    private val themeOnly = Regex("""\b\d+(\.\d+)?f?\s*\.\s*(dp|sp)\b|Color\s*\(\s*0x""")

    @Test
    fun `화면 코드에 hex·dp·sp 숫자가 없다`() {
        val files = dirs.flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" && !it.name.endsWith("Sizes.kt") }.toList() }
        assertTrue("화면 파일을 찾지 못했다", files.isNotEmpty())
        val found = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line -> if (themeOnly.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null }
        }
        assertTrue("직접 쓴 값:\n" + found.joinToString("\n"), found.isEmpty())
    }
}

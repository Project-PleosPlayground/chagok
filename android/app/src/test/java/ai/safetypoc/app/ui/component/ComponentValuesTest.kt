package ai.safetypoc.app.ui.component

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 공통 컴포넌트 코드가 색·길이·글자 크기를 테마(ui/theme)에서만 가져오는지 */
class ComponentValuesTest {
    private val componentDir = File("src/main/java/ai/safetypoc/app/ui/component")

    /**
     * 테마 밖에 쓰면 안 되는 값: 숫자.dp, 숫자.sp, Color(…), Color.Black 같은 이름 색(Transparent·Unspecified 빼고),
     * Dp(…), TextUnit(…), 숫자로 준 alpha
     */
    private val themeOnly = Regex(
        """\b\d+(\.\d+)?f?\s*\.\s*(dp|sp)\b""" +
            """|\bColor\s*\(""" +
            """|\bColor\.(?!Transparent\b|Unspecified\b)[A-Z]\w*""" +
            """|\b(Dp|TextUnit)\s*\(""" +
            """|\balpha\s*=\s*\d""",
    )

    @Test
    fun `컴포넌트 코드에 hex·dp·sp 숫자가 없다`() {
        val files = componentDir.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue("컴포넌트 파일을 찾지 못했다: ${componentDir.absolutePath}", files.isNotEmpty())
        val found = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (themeOnly.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("테마 값을 쓴다:\n" + found.joinToString("\n"), found.isEmpty())
    }
}

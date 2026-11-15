package ai.safetypoc.app.ui.decorate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 상점 화면 코드가 길이·글자 크기를 크기 객체(StoreSizes.kt)나 테마에서만 가져오는지 */
class StoreValuesTest {
    private val screenDir = File("src/main/java/ai/safetypoc/app/ui/decorate")
    private val sizesFile = "StoreSizes.kt"

    /** 크기 객체 밖에 쓰면 안 되는 값: 숫자.dp, 숫자.sp, Color(0x…) */
    private val sizesOnly = Regex("""\b\d+(\.\d+)?f?\s*\.\s*(dp|sp)\b|Color\s*\(\s*0x""")

    @Test
    fun `상점 화면 코드에 hex·dp·sp 숫자가 없다`() {
        val files = screenDir.walkTopDown().filter { it.extension == "kt" && it.name != sizesFile }.toList()
        assertTrue("상점 화면 파일을 찾지 못했다: ${screenDir.absolutePath}", files.isNotEmpty())
        val found = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (sizesOnly.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("크기 객체 밖에 값을 쓴다:\n" + found.joinToString("\n"), found.isEmpty())
    }
}

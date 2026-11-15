package ai.safetypoc.app.ui.report

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 주행 결과 화면 코드가 길이·글자 크기·색을 크기 객체(ReportSizes.kt)나 테마에서만 가져오는지 */
class ReportValuesTest {
    private val screenDir = File("src/main/java/ai/safetypoc/app/ui/report")
    /** 검사할 주행 결과 화면 파일. 같은 폴더의 통계 탭(ReportScreen, 지도), 크기 객체, 디자인 없는 AI 동의 묻기 뼈대(#101)는 뺀다 */
    private val resultFiles = setOf("DriveResult.kt", "DriveResultScreen.kt", "ReportComponents.kt")

    /** 크기 객체 밖에 쓰면 안 되는 값: 숫자.dp, 숫자.sp, Color(…), Color.White 같은 이름 색 */
    private val sizesOnly = Regex("""\b\d+(\.\d+)?f?\s*\.\s*(dp|sp)\b|\bColor\s*\(|\bColor\.(?!Transparent\b|Unspecified\b)[A-Z]\w*""")

    @Test
    fun `주행 결과 화면 코드에 hex·dp·sp 숫자가 없다`() {
        val files = screenDir.walkTopDown().filter { it.extension == "kt" && it.name in resultFiles }.toList()
        assertTrue("주행 결과 화면 파일을 찾지 못했다: ${screenDir.absolutePath}", files.isNotEmpty())
        val found = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                if (sizesOnly.containsMatchIn(line)) "${file.name}:${i + 1}: ${line.trim()}" else null
            }
        }
        assertTrue("크기 객체 밖에 값을 쓴다:\n" + found.joinToString("\n"), found.isEmpty())
    }
}

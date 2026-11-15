package ai.safetypoc.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * 앱 테마의 색·글자가 디자인 시스템 문서(기획/디자인/디자인_시스템.md)의 4절 팔레트 표, 5절 글자 표와 같은지.
 * 표의 열은 머리글 이름으로 찾는다. 문서 이름 snake_case는 Kotlin 이름 camelCase로 바꿔 맞춘다.
 */
class DesignSystemSyncTest {
    private val doc = File("../../기획/디자인/디자인_시스템.md").readText().replace("\r\n", "\n")

    /** 굵기 이름 → 숫자 */
    private val weights = mapOf(
        "thin" to 100, "extralight" to 200, "light" to 300, "regular" to 400, "normal" to 400,
        "medium" to 500, "semibold" to 600, "bold" to 700, "extrabold" to 800, "black" to 900,
    )

    /** 문서 글꼴 이름 → 앱 글꼴 */
    private val families = mapOf("roboto" to FontFamily.Default)

    @Test
    fun `색이 4절 팔레트 표와 같다`() {
        val rows = table("### 팔레트", "이름", "값")
        val expected = rows.associate { row ->
            camel(code(row.getValue("이름"))) to row.getValue("값").removePrefix("#").toLong(16).or(OPAQUE).toInt()
        }
        val actual = staticFields(AppPalette::class.java, Long::class.javaPrimitiveType!!)
            .mapValues { (_, raw) -> Color((raw as Long).toULong()).toArgb() }
        assertSame("AppPalette", expected, actual) { "#%06X".format(it and RGB) }
    }

    @Test
    fun `글자가 5절 글자 표와 같다`() {
        val section = section("### 글자")
        val familyName = Regex("""글꼴은 ([A-Za-z][\w ]*?)\.""").find(section)?.groupValues?.get(1)
        val family = families[familyName?.lowercase()]
        assertTrue("5절 글꼴 \"$familyName\"에 맞는 앱 글꼴이 이 테스트의 families에 없다", family != null)
        val expected = table("### 글자", "역할", "크기", "굵기").associate { row ->
            val weightName = row.getValue("굵기")
            val weight = weights[weightName.lowercase().replace(Regex("[\\s_-]"), "")]
                ?: error("5절 글자 표의 굵기 \"$weightName\"를 읽지 못했다")
            camel(code(row.getValue("역할"))) to Font(family!!, row.getValue("크기").toFloat(), weight)
        }
        val actual = staticFields(AppType::class.java, TextStyle::class.java).mapValues { (_, v) ->
            val style = v as TextStyle
            Font(style.fontFamily, style.fontSize.value, style.fontWeight?.weight)
        }
        assertSame("AppType", expected, actual) { it.toString() }
    }

    /** 비교할 글자 모양 */
    private data class Font(val family: FontFamily?, val size: Float, val weight: Int?)

    private fun <T> assertSame(owner: String, expected: Map<String, T>, actual: Map<String, T>, show: (T) -> String) {
        assertTrue("문서 표를 읽지 못했다", expected.isNotEmpty())
        val problems = buildList {
            (expected.keys - actual.keys).forEach { add("$owner.$it 없음 (문서 ${show(expected.getValue(it))})") }
            (actual.keys - expected.keys).forEach { add("$owner.$it 는 문서 표에 없음") }
            (expected.keys intersect actual.keys).filter { expected[it] != actual[it] }.forEach {
                add("$owner.$it 문서 ${show(expected.getValue(it))}, 코드 ${show(actual.getValue(it))}")
            }
        }
        assertTrue("디자인 시스템 문서와 다르다:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    /** heading 절(다음 같은 단계 이상의 제목 전까지)의 글 */
    private fun section(heading: String): String {
        val start = doc.indexOf("\n$heading\n")
        assertTrue("문서에서 \"$heading\" 절을 찾지 못했다", start >= 0)
        val level = heading.takeWhile { it == '#' }.length
        val rest = doc.substring(start + heading.length + 2)
        val end = Regex("\n#{1,$level} ").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /**
     * heading 절의 첫 표. 머리글로 열을 찾고(끝의 "(단위)"는 build.js와 같은 규칙으로 뗀다), 이름 칸이 `code`인 줄만.
     * [need] 열이 없으면 머리글을 보여 주며 실패한다
     */
    private fun table(heading: String, vararg need: String): List<Map<String, String>> {
        val lines = section(heading).lines().filter { it.startsWith("|") }.map(::cells)
        assertTrue("\"$heading\" 절에 표가 없다", lines.isNotEmpty())
        val head = lines.first().map { it.replace(UNIT_SUFFIX, "") }
        val missing = need.filterNot { it in head }
        assertTrue("\"$heading\" 표에 없는 열: ${missing.joinToString()}. 머리글: ${head.joinToString(" | ")}", missing.isEmpty())
        return lines.drop(1)
            .filter { row -> row.any { it.startsWith("`") } }
            .map { row -> head.indices.associate { i -> head[i] to row.getOrElse(i) { "" } } }
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
    private fun code(cell: String) = Regex("^`(\\w+)`$").find(cell)?.groupValues?.get(1) ?: error("`이름` 꼴이 아니다: $cell")
    private fun camel(name: String) = name.replace(Regex("_([a-z0-9])")) { it.groupValues[1].uppercase() }

    /** [owner] object의 static 필드 중 [type]인 것: 이름 → 값 */
    private fun staticFields(owner: Class<*>, type: Class<*>): Map<String, Any> =
        owner.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == type }
            .associate { it.isAccessible = true; it.name to it.get(null) }

    private companion object {
        /** 불투명 알파 */
        const val OPAQUE = 0xFF000000
        /** RGB 부분 */
        const val RGB = 0xFFFFFF
        /** 머리글 끝의 단위 표기. 예: "크기 (sp)", "크기(sp)" */
        val UNIT_SUFFIX = Regex("""\s*\(.*\)$""")
    }
}

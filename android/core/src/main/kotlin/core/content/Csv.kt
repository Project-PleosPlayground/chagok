package core.content

/**
 * CSV 한 파일을 읽는다 (기획/데이터_컬럼_사전.md 공통 규칙: 첫 행은 컬럼명, 한 셀에 값 하나, UTF-8).
 * 큰따옴표로 감싼 칸(쉼표·줄바꿈 포함, "" 는 따옴표 하나), 맨 앞 BOM, CRLF 줄바꿈을 처리한다. 모든 칸이 빈 줄은 건너뛴다.
 */
object Csv {
    private const val BOM = '﻿'

    /** 한 행. [line] 은 파일에서 몇 번째 줄인지(1부터, 컬럼명 행이 1) */
    class Row(val line: Int, private val cells: Map<String, String>) {
        /** 그 컬럼의 값. 앞뒤 공백을 뗀다. 컬럼이 없거나 빈 칸이면 "" */
        operator fun get(column: String): String = cells[column]?.trim().orEmpty()
    }

    class Table(val header: List<String>, val rows: List<Row>)

    class FormatException(message: String) : Exception(message)

    fun parse(text: String): Table {
        val records = records(text.removePrefix(BOM.toString()))
        if (records.isEmpty()) throw FormatException("컬럼명 행이 없다")
        val (headerLine, header) = records.first()
        val names = header.map { it.trim() }
        val rows = records.drop(1).map { (line, cells) ->
            if (cells.size > names.size) throw FormatException("${line}행: 칸이 컬럼(${names.size}개)보다 많다: ${cells.size}개")
            Row(line, names.zip(cells).toMap())
        }
        if (headerLine != 1) throw FormatException("컬럼명은 첫 행에 있어야 한다")
        return Table(names, rows)
    }

    /** (줄 번호, 칸 목록) 목록. 빈 줄은 뺀다 */
    private fun records(s: String): List<Pair<Int, List<String>>> {
        val out = ArrayList<Pair<Int, List<String>>>()
        var line = 1; var startLine = 1
        val cells = ArrayList<String>(); val cell = StringBuilder()
        var quoted = false; var i = 0
        fun endRecord() {
            cells += cell.toString(); cell.clear()
            if (cells.any { it.isNotBlank() }) out += startLine to cells.toList()   // 빈 줄, 엑셀이 남기는 ",,,," 줄은 뺀다
            cells.clear()
        }
        while (i < s.length) {
            val c = s[i]
            when {
                quoted && c == '"' && i + 1 < s.length && s[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' && (quoted || cell.isEmpty()) -> quoted = !quoted
                quoted -> { if (c == '\n') line++; cell.append(c) }
                c == ',' -> { cells += cell.toString(); cell.clear() }
                c == '\r' -> {}
                c == '\n' -> { endRecord(); line++; startLine = line }
                else -> cell.append(c)
            }
            i++
        }
        if (quoted) throw FormatException("${startLine}행: 닫히지 않은 따옴표")
        if (cell.isNotEmpty() || cells.isNotEmpty()) endRecord()
        return out
    }

    /** 콘텐츠 텍스트 탭(데이터 컬럼 사전 "콘텐츠 텍스트")의 컬럼 */
    const val TEXT_KEY = "키"
    const val TEXT = "문구"

    /** CSV 여러 파일을 읽으며 문제를 파일·줄 번호와 함께 모은다 */
    class Reader {
        val problems = ArrayList<String>()

        /** 표를 읽는다. 형식이 깨졌거나 [required] 컬럼이 없으면 문제를 남기고, 깨졌으면 null */
        fun table(file: String, text: String, required: List<String>): Table? = try {
            parse(text).also { t -> required.filter { it !in t.header }.forEach { problems += "$file: 컬럼이 없다: $it" } }
        } catch (e: FormatException) { problems += "$file: ${e.message}"; null }

        fun int(file: String, row: Row, column: String): Int? =
            row[column].toIntOrNull() ?: null.also { problems += "$file ${row.line}행: $column 이 정수가 아니다: '${row[column]}'" }

        /** 숫자 칸. 빈 칸이면 null */
        fun number(file: String, row: Row, column: String): Float? = row[column].let { v ->
            if (v.isEmpty()) null else v.toFloatOrNull() ?: null.also { problems += "$file ${row.line}행: $column 이 숫자가 아니다: '$v'" }
        }

        /** 콘텐츠 텍스트 탭 전체를 키 → 문구로 읽는다. 키가 겹치면 문제 */
        fun texts(file: String, text: String): Map<String, String>? {
            val t = table(file, text, listOf(TEXT_KEY, TEXT)) ?: return null
            val out = LinkedHashMap<String, String>()
            for (r in t.rows) {
                val key = r[TEXT_KEY]; if (key.isEmpty()) continue
                if (key in out) problems += "$file ${r.line}행: 키가 겹친다: $key"
                out[key] = r[TEXT]
            }
            return out
        }
    }
}

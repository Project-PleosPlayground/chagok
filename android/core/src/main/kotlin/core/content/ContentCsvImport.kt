package core.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.system.exitProcess

/**
 * 개발용 명령: 기획 CSV 를 읽어 내장 콘텐츠(bundled.json)의 미션·아이템을 바꾼다. `./gradlew :core:importContentCsv`.
 * 인자: CSV 폴더, bundled.json 경로. 미션 CSV 세 개는 꼭 있어야 하고, 아이템 CSV 가 있으면 아이템도 바꾼다.
 * 바꾼 묶음이 [ContentValidator] 를 통과할 때만 저장한다. 다른 칸(점수 구간, 캐릭터, _note 등)과 미션 수치(rules)는 그대로 둔다.
 */
fun main(args: Array<String>) {
    if (args.size != 2) fail("인자: <CSV 폴더> <bundled.json>")
    val dir = File(args[0]); val target = File(args[1])
    fun csvOrNull(name: String) = File(dir, name).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    fun csv(name: String) = csvOrNull(name) ?: fail("CSV 가 없다: ${File(dir, name).path}")

    val original = target.readText(Charsets.UTF_8)
    var pack = ContentCodec.decode(original)
    val texts = csv(MissionCsv.TEXTS_FILE)
    val missions = MissionCsv.read(csv(MissionCsv.CONDITIONS_FILE), csv(MissionCsv.REWARDS_FILE), texts, pack.missions.rules)
    pack = pack.copy(missions = missions.catalog ?: fail("미션 CSV 를 읽지 못했다:\n" + missions.problems.joinToString("\n")))
    val itemsCsv = csvOrNull(ItemCsv.ITEMS_FILE)
    if (itemsCsv != null) {
        val items = ItemCsv.read(itemsCsv, texts)
        if (items.items == null || items.texts == null) fail("아이템 CSV 를 읽지 못했다:\n" + items.problems.joinToString("\n"))
        pack = pack.copy(items = items.items, itemTexts = items.texts)
    }
    val problems = ContentValidator.problems(pack)
    if (problems.isNotEmpty()) fail("콘텐츠 검사에 걸렸다 (저장하지 않음):\n" + problems.joinToString("\n"))

    val root = Json.parseToJsonElement(original.removePrefix("\uFEFF")).jsonObject
    val encoded = Json.parseToJsonElement(ContentCodec.encode(pack)).jsonObject
    val replaced = if (itemsCsv != null) listOf(MISSIONS_KEY, ITEMS_KEY, ITEM_TEXTS_KEY) else listOf(MISSIONS_KEY)
    val updated = LinkedHashMap<String, JsonElement>()
    for ((k, v) in root) {
        updated[k] = if (k in replaced) encoded.getValue(k) else v
        // 옛 파일에 없던 아이템 문구 칸은 아이템 바로 뒤에 넣는다
        if (k == ITEMS_KEY && ITEM_TEXTS_KEY in replaced && ITEM_TEXTS_KEY !in root) updated[ITEM_TEXTS_KEY] = encoded.getValue(ITEM_TEXTS_KEY)
    }
    target.writeText(BundledJsonFormat.format(JsonObject(updated)) + "\n", Charsets.UTF_8)
    println("저장함: ${target.path} (조건 ${pack.missions.conditions.size}개, 단계 보상 ${pack.missions.tierRewards.size}개, 미션 문구 ${pack.missions.texts.size}개" +
        if (itemsCsv != null) ", 아이템 ${pack.items.size}개, 아이템 문구 ${pack.itemTexts.size}개)" else ", 아이템은 그대로)")
}

private const val MISSIONS_KEY = "missions"
private const val ITEMS_KEY = "items"
private const val ITEM_TEXTS_KEY = "itemTexts"

private fun fail(message: String): Nothing { System.err.println(message); exitProcess(1) }

/**
 * bundled.json 을 사람이 읽고 diff 보기 쉬운 모양으로 쓴다: 표의 행(객체 배열의 원소)은 한 줄에 하나,
 * 값이 객체·배열이거나 칸이 많은 객체는 펼치고, 나머지는 `{ "k": v, ... }`·`[1, 2]` 처럼 한 줄로. 정수인 숫자는 소수점 없이 쓴다.
 */
internal object BundledJsonFormat {
    private const val INDENT = "  "
    /** 이보다 칸이 많은 객체는 펼친다 (콘텐츠 텍스트처럼 키가 많은 표) */
    private const val INLINE_MAX_ENTRIES = 6

    fun format(e: JsonElement, depth: Int = 0): String {
        val pad = INDENT.repeat(depth); val inner = INDENT.repeat(depth + 1)
        return when (e) {
            is JsonObject -> if (e.isEmpty()) "{}" else if (depth == 0 || e.size > INLINE_MAX_ENTRIES || e.values.any { it !is JsonPrimitive && !isFlatArray(it) })
                e.entries.joinToString(",\n", "{\n", "\n$pad}") { (k, v) -> "$inner${JsonPrimitive(k)}: ${format(v, depth + 1)}" }
            else inline(e)
            is JsonArray -> if (e.any { it is JsonObject }) e.joinToString(",\n", "[\n", "\n$pad]") { "$inner${inline(it)}" } else inline(e)
            else -> inline(e)
        }
    }

    private fun isFlatArray(e: JsonElement) = e is JsonArray && e.all { it is JsonPrimitive }

    private fun inline(e: JsonElement): String = when (e) {
        is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(", ", "{ ", " }") { (k, v) -> "${JsonPrimitive(k)}: ${inline(v)}" }
        is JsonArray -> e.joinToString(", ", "[", "]") { inline(it) }
        is JsonPrimitive -> if (!e.isString) e.content.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: e.content else e.toString()
        else -> e.toString()
    }
}

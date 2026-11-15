package core.content

import core.character.CharacterDef
import core.item.ItemDef
import core.mission.MissionCatalog
import core.session.EndAnimation
import engine.EngineConfig
import engine.EngineTuning
import engine.TunableParam
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 원격 콘텐츠 업데이트(#31)로 바꿀 수 있는 데이터 한 묶음.
 * 앱에는 [BundledContent] 가 들어 있고, 서버에서 받은 묶음이 더 새것이면 그것을 쓴다.
 * 여기 있는 값의 "종류"(조건, 범위, 아이템 칸 등)는 각 기능 패키지의 enum(mission/Mission.kt, item/Item.kt, session/EndAnimation.kt) 안에서만 쓸 수 있다.
 */
@Serializable
data class ContentPack(
    /** 묶음 버전. 클수록 새것 */
    val contentVersion: Int,
    /** 이 묶음을 읽을 수 있는 가장 낮은 앱 버전(versionCode) */
    val minAppVersion: Int,
    val missions: MissionCatalog,
    val items: List<ItemDef>,
    /** 콘텐츠 텍스트 중 아이템 이름 문구. 키 → 문구 (데이터 컬럼 사전 "콘텐츠 텍스트"). 미션 문구는 [MissionCatalog.texts] */
    val itemTexts: Map<String, String> = emptyMap(),
    /** 캐릭터 종류(차종) 목록 (#48) */
    val characters: List<CharacterDef>,
    /** 점수 → 종료 애니메이션. minScore 내림차순으로 보고 처음 맞는 구간을 쓴다 */
    val scoreBands: List<ScoreBand>,
    /**
     * 원격 조정 엔진 수치 (스펙 6-1). 이름 → 실제 값. 이름·범위는 [TunableParam].
     * 비어 있으면 앱 내장 기본값. 켬·끔 값은 0·1 로 쓴다.
     */
    val engine: Map<String, Double> = emptyMap(),
) {
    fun animationFor(score: Int): EndAnimation =
        scoreBands.sortedByDescending { it.minScore }.firstOrNull { score >= it.minScore }?.animation ?: EndAnimation.NONE

    /**
     * 이 묶음의 엔진 수치를 [base] 위에 덮어쓴 설정. [engine] 이 비어 있으면 base 그대로(paramsVersion 0).
     * 값이 있으면 paramsVersion = contentVersion. [ContentValidator] 를 통과한 묶음에서만 부른다(아니면 IllegalArgumentException).
     * :app 은 이 설정을 SafetyScoreEngine.updateConfig 로 넘긴다. 엔진은 다음 세션 시작 때 적용한다(세션 고정).
     */
    fun engineConfig(base: EngineConfig): EngineConfig =
        if (engine.isEmpty()) base.copy(paramsVersion = EngineConfig.BUILT_IN_PARAMS_VERSION)
        else EngineTuning.apply(base, engine, contentVersion)
}

@Serializable
data class ScoreBand(val minScore: Int, val animation: EndAnimation)

/**
 * 콘텐츠 JSON 읽기·쓰기. 실패하면 예외를 던지므로 바깥에서 받는 데이터는 [ContentUpdate]를 거쳐 읽는다.
 * - 모르는 필드는 무시한다(새 묶음을 구버전 앱이 읽을 때).
 * - 모르는 enum 값은 무시하지 않는다(실패). 새 종류는 minAppVersion 으로 막는다.
 * - 맨 앞 BOM 은 지운다(Windows 메모장 등으로 편집한 파일).
 * - 숫자 자리에 따옴표 붙은 값("13")이 있으면 실패한다. 라이브러리는 받아 주지만, 만든 쪽의 형식 실수라 묶음을 믿지 않는다.
 * - 한 객체에 같은 이름이 두 번 있으면 실패한다. 라이브러리는 뒤의 값을 쓰는데, 앞의 값(범위 밖일 수도 있다)이 말없이 사라진다.
 */
object ContentCodec {
    private const val BOM = '\uFEFF'
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun decode(text: String): ContentPack = strictDecode(ContentPack.serializer(), text)
    fun encode(pack: ContentPack): String = json.encodeToString(ContentPack.serializer(), pack)
    fun decodeManifest(text: String): ContentManifest = strictDecode(ContentManifest.serializer(), text)

    /**
     * 읽은 결과를 다시 JSON 으로 만들어 원문과 자리별로 맞춰 본다. 다시 만든 쪽이 숫자인데 원문이 문자열이면 따옴표 숫자다.
     * 원문에만 있는 모르는 필드와 기본값으로 채운 필드는 비교하지 않는다.
     */
    private fun <T> strictDecode(serializer: KSerializer<T>, text: String): T {
        val body = text.removePrefix(BOM.toString())
        val value = json.decodeFromString(serializer, body)
        duplicateKey(body)?.let { throw SerializationException("같은 이름이 한 객체에 두 번 있다: $it") }
        requireNoQuotedNumbers(json.parseToJsonElement(body), json.encodeToJsonElement(serializer, value), "$")
        return value
    }

    /**
     * 한 객체 안에 같은 이름이 두 번 나오면 그 이름. 라이브러리는 뒤의 값을 쓰고 앞의 값을 말없이 버리므로 따로 본다.
     * 형식이 맞는 JSON 에만 부른다(decode 가 먼저 성공한 뒤). 이름의 \u 이스케이프는 풀어서 비교한다.
     */
    private fun duplicateKey(text: String): String? {
        val objects = ArrayDeque<MutableSet<String>?>()   // 객체는 이름 집합, 배열은 null
        var expectKey = false
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '{' -> { objects.addLast(HashSet()); expectKey = true }
                '[' -> { objects.addLast(null); expectKey = false }
                '}', ']' -> { objects.removeLastOrNull(); expectKey = false }
                ',' -> expectKey = objects.lastOrNull() != null
                ':' -> expectKey = false
                '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (text[i] != '"') {
                        if (text[i] == '\\') {
                            i++
                            when (val e = text[i]) {
                                'u' -> { sb.append(text.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                                'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                                'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                                else -> sb.append(e)
                            }
                        } else sb.append(text[i])
                        i++
                    }
                    if (expectKey) {
                        val key = sb.toString()
                        if (objects.lastOrNull()?.add(key) == false) return key
                        expectKey = false
                    }
                }
            }
            i++
        }
        return null
    }

    private fun requireNoQuotedNumbers(original: JsonElement, canonical: JsonElement, at: String) {
        when {
            original is JsonObject && canonical is JsonObject ->
                for ((k, v) in original) canonical[k]?.let { requireNoQuotedNumbers(v, it, "$at.$k") }
            original is JsonArray && canonical is JsonArray ->
                original.zip(canonical).forEachIndexed { i, (o, c) -> requireNoQuotedNumbers(o, c, "$at[$i]") }
            original is JsonPrimitive && canonical is JsonPrimitive && original.isString && !canonical.isString && canonical !is JsonNull ->
                throw SerializationException("숫자 자리에 따옴표 붙은 값: $at = \"${original.content}\"")
        }
    }

    /** 바이트를 엄격한 UTF-8 로 읽는다. 다른 인코딩(CP949 등)이거나 깨진 바이트가 있으면 null (깨진 글자로 바꿔 넘기지 않는다) */
    fun utf8OrNull(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
}

/** 앱에 내장된 기본 콘텐츠. 통신이 없거나 처음 실행할 때 쓴다. */
object BundledContent {
    const val RESOURCE_PATH = "/content/bundled.json"
    val pack: ContentPack by lazy {
        val text = BundledContent::class.java.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("내장 콘텐츠 없음: $RESOURCE_PATH")
        ContentCodec.decode(text)
    }
}

/** 내장 콘텐츠의 미션 데이터 (#31). 서버에서 받은 묶음은 ContentState.active.missions 로 읽는다. */
object DefaultMissions {
    val catalog: MissionCatalog get() = BundledContent.pack.missions
}

/** 내장 콘텐츠의 아이템 목록 (#31). 서버에서 받은 묶음은 ContentState.active.items 로 읽는다. */
object DefaultItems {
    val all: List<ItemDef> get() = BundledContent.pack.items
}

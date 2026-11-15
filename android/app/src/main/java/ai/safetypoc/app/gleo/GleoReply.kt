package ai.safetypoc.app.gleo

import org.json.JSONArray
import org.json.JSONObject

/** Gleo 가 부르는 함수. 이름은 `assets/schema.json` 의 함수 이름, 받는 Intent 의 action 과 같다 */
enum class GleoFunction {
    GET_DRIVE_SCORE,
    GET_PET_STATUS,
    GET_MISSION_PROGRESS;

    companion object {
        fun of(action: String?): GleoFunction? = entries.firstOrNull { it.name == action }
    }
}

/** 응답의 처리 결과 (Pleos Callback Schema `status`) */
enum class GleoStatus(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

/** 응답을 Gleo 가 어떻게 알리는지 (Pleos Callback Schema `mode`) */
enum class GleoMode(val wire: String) { VOICE("voice"), BEEP("beep"), SILENT("silent") }

/**
 * Gleo 에 보내는 응답 하나 (Pleos Callback Schema).
 * [message] 는 영어 한 문장이다. [data] 는 Gleo 가 답을 만들 때 보는 키-값 묶음이다.
 */
data class GleoReply(
    val function: GleoFunction,
    val status: GleoStatus,
    val message: String,
    val data: List<Map<String, Any>> = emptyList(),
    val mode: GleoMode = GleoMode.VOICE,
) {
    /** `response` extra 에 넣는 JSON. `data` 는 JSON 배열을 문자열로 넣는다 */
    fun toJson(): String = JSONObject()
        .put(KEY_FUNCTION, function.name)
        .put(KEY_STATUS, status.wire)
        .put(KEY_MESSAGE, message)
        .put(KEY_DATA, JSONArray(data.map { JSONObject(it) }).toString())
        .put(KEY_MODE, mode.wire)
        .toString()

    companion object {
        const val KEY_FUNCTION = "functionName"
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"
        const val KEY_DATA = "data"
        const val KEY_MODE = "mode"

        /** 앱 데이터를 읽다가 오류가 났을 때 */
        fun error(function: GleoFunction) = GleoReply(function, GleoStatus.FAIL, "An error occurred while reading the app data.")
    }
}

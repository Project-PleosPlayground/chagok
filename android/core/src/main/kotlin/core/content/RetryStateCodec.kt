package core.content

import kotlinx.serialization.json.Json

/** [RetryState] 를 저장·복원하는 JSON. :app 이 파일에 둔다. 깨져 있으면 처음 상태(간격 없이 다시 시도) */
object RetryStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(s: RetryState): String = json.encodeToString(RetryState.serializer(), s)
    fun decode(text: String?): RetryState =
        text?.let { runCatching { json.decodeFromString(RetryState.serializer(), it) }.getOrNull() } ?: RetryState()
}

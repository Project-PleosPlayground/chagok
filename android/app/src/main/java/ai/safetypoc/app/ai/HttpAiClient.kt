package ai.safetypoc.app.ai

import core.ai.AiClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * AI 중계 서버(`server/`, Vercel) 호출. POST <서버>/api/drive-end, 헤더 x-app-token, 본문은 core 가 만든 JSON 그대로.
 * 실패(통신·타임아웃·2xx 아님)면 null 을 돌려주고, core `AiService` 가 규칙 문장으로 간다. 예외는 밖으로 내지 않는다.
 * 서버 주소·토큰은 저장소에 넣지 않고 `local.properties` 의 `ai.relay.url`·`ai.relay.token` 으로 BuildConfig 에 들어온다 ([fromConfig]).
 */
class HttpAiClient(
    private val endpoint: String,
    private val token: String,
    private val timeoutMs: Int = TIMEOUT_MS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val log: (String) -> Unit = {},
) : AiClient {
    override suspend fun driveEnd(payloadJson: String): String? = withContext(io) {
        runCatching {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                if (token.isNotBlank()) c.setRequestProperty("x-app-token", token)
                c.outputStream.use { it.write(payloadJson.toByteArray(Charsets.UTF_8)) }
                val code = c.responseCode
                if (code !in 200..299) { log("AI 중계 서버 응답 $code"); null }
                else c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally { c.disconnect() }
        }.onFailure { log("AI 중계 서버 호출 실패: ${it.javaClass.simpleName} ${it.message ?: ""}") }.getOrNull()
    }

    companion object {
        /** core `AiService` 의 타임아웃(15초)보다 조금 짧게. 연결·읽기 각각. 서버가 Claude 를 두 번까지 시도해서 넉넉히 (11/15) */
        const val TIMEOUT_MS = 14_000
        const val PATH = "/api/drive-end"

        /** 서버 주소가 비어 있으면 null (AI 없이 규칙 문장만). 끝의 `/` 는 있어도 없어도 된다 */
        fun fromConfig(baseUrl: String, token: String, log: (String) -> Unit = {}): AiClient? =
            baseUrl.trim().takeIf { it.isNotEmpty() }?.let { HttpAiClient(it.trimEnd('/') + PATH, token.trim(), log = log) }
    }
}

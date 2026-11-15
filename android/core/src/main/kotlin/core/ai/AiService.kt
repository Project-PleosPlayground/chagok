package core.ai

import core.flow.SessionEndSummary
import core.session.SessionRecord
import core.session.VehicleStatus
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 중계 서버 호출. :app 이 HTTP(POST /api/drive-end, 헤더 x-app-token, 본문 [AiJson.encode])로 구현한다.
 * 응답 본문 JSON 문자열을 그대로 돌려주면 [AiService] 가 파싱한다. 실패·비정상 응답이면 null.
 */
fun interface AiClient {
    suspend fun driveEnd(payloadJson: String): String?
}

/** 언제 AI 를 부르는가. 설계 1절: 이벤트 0회·10분 미만 주행은 부르지 않는다(규칙 문장으로 충분). 비용은 주행당 최대 1회 */
object AiGate {
    const val MIN_MINUTES = 10
    fun penaltyEvents(r: SessionRecord) = r.result.events.count { it.type in SessionRecord.DEDUCTED }
    /** [minMinutes] 는 주행 최소 시간(분). 기본 10분. 시연용 짧은 주행에서도 보려면 :app 이 더 작게 넘긴다 */
    fun shouldCall(r: SessionRecord, minMinutes: Int = MIN_MINUTES): Boolean =
        r.valid && penaltyEvents(r) > 0 && (r.endWallMs - r.startWallMs) >= minMinutes * 60_000L
}

/**
 * 주행 끝 AI 리포트. 클라이언트가 없거나 실패·타임아웃·빈 응답이면 null 을 돌려주고, 화면은 파이프라인의 규칙 문장(SessionEndSummary.comment)을 그대로 쓴다.
 * 리포트 생성까지 기다려야 하므로 타임아웃이 길다(15초, 서버가 Claude 를 두 번까지 시도한다). 주행 끝 화면은 그동안 점수·보상을 먼저 보여 준다.
 */
class AiService(private val client: AiClient?, private val timeoutMs: Long = 15_000) {
    suspend fun report(payload: DriveEndPayload): AiReport? {
        val c = client ?: return null
        val body = runCatching { withTimeoutOrNull(timeoutMs) { c.driveEnd(AiJson.encode(payload)) } }.getOrNull() ?: return null
        return AiJson.parseAnalysis(body)
    }
}

/**
 * :app 이 부르는 한 줄. SessionEndPipeline.onSessionEnd 가 돌려준 요약을 받아, 게이트를 통과하면 AI 리포트로 comment 를 바꾼다.
 * 실패하면 요약을 그대로 돌려준다. 미션 제공은 여기서 하지 않는다(PersonalMissionPlanner 를 refresh 전에 부른다).
 */
class AiDriveEnd(
    private val builder: AiPayloadBuilder,
    private val service: AiService,
    /** AI 를 부르는 주행 최소 시간(분). [AiGate.MIN_MINUTES] 가 기본 */
    private val minMinutes: Int = AiGate.MIN_MINUTES,
    /** 이 프로필이 AI 리포트(주행 요약을 서버·Claude 로 보냄)에 동의했는지. false 면 아무것도 보내지 않는다 */
    private val consented: (profileId: String) -> Boolean = { true },
    /** 저장된 최신 기록. 도로 이름은 주행이 끝난 뒤 따로 채워지므로 대사의 장소 빈칸을 채울 때 다시 읽는다 */
    private val latest: (id: String) -> SessionRecord? = { null },
) {
    suspend fun run(summary: SessionEndSummary, character: AiCharacter, status: VehicleStatus, nowMs: Long): SessionEndSummary {
        if (!consented(summary.record.profileId)) return summary
        if (!AiGate.shouldCall(summary.record, minMinutes)) return summary
        val raw = service.report(builder.driveEnd(summary.record, character, status, nowMs)) ?: return summary
        val report = raw.withPlace(AiReport.placeOf(latest(summary.record.id) ?: summary.record))
        return summary.copy(comment = report.report, tip = report.tip, comic = report.panels, aiGenerated = true)
    }
}

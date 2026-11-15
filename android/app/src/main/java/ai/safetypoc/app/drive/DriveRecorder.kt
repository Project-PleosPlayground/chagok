package ai.safetypoc.app.drive

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.location.GeoFix
import core.flow.SessionEndSummary
import core.session.EventRecord
import core.session.SessionRecord
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.SessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 끝난 주행을 세션 기록으로 만들어 저장·미션 판정·보상(SessionEndPipeline)으로 넘기고, 주행 종료 요약을 갖는다.
 * 엔진 시각은 :app 이 넣는 wall-clock 시각이라 세션의 시작·끝 시각으로 그대로 쓴다.
 */
class DriveRecorder(
    private val graph: AppGraph,
    private val log: (String) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 이벤트가 난 곳 (GPS). 기기 안 기록에만 붙는다 (#13 반복 지점) */
    private val locate: (DrivingEvent) -> GeoFix? = { null },
) {
    private val _last = MutableStateFlow<SessionEndSummary?>(null)
    /** 마지막 주행의 종료 요약. 주행 종료 화면이 보여 준다 */
    val last: StateFlow<SessionEndSummary?> = _last.asStateFlow()

    /** 끝난 주행 하나를 처리한다. 차량 상태(벨트·충전·잔량)는 [status] 로 받는다. [where] 는 이벤트 위치(시연 주행은 가짜 경로) */
    fun onSessionEnded(
        result: SessionResult, status: VehicleStatus, nowMs: Long, where: (DrivingEvent) -> GeoFix? = locate,
        origin: String? = null, destination: String? = null,
    ): SessionEndSummary {
        val record = SessionRecord(
            id = newId(), profileId = graph.profileId, result = result,
            events = result.events.map { e -> where(e).let { g -> EventRecord(e, roadName = g?.road, lat = g?.lat, lng = g?.lng) } },
            startWallMs = result.startMs, endWallMs = result.endMs, origin = origin, destination = destination,
        )
        val summary = graph.pipeline.onSessionEnd(record, status, nowMs)
        _last.value = summary
        log(
            "세션 종료: 유효=${record.valid} 점수=${record.score} 거리=${"%.2f".format(record.distanceKm)}km 이벤트=${record.result.events.size} | " +
                "저장된 세션 ${graph.sessions.list(graph.profileId).size}개, 성공 미션 ${summary.completed.size}개, " +
                "위치 ${record.events.count { it.lat != null }}/${record.events.size}, 포인트 +${summary.coinsGained} → ${summary.profile.coins}, 경험치 +${summary.xpGained} → ${summary.character?.xp}, 레벨업 ${summary.leveledUpTo}",
        )
        if (!record.valid) log("짧은 주행: 유효 주행 조건 미달이라 리포트에 넣지 않음")
        return summary
    }

    /** AI 리포트가 늦게 오면 요약을 바꾼다(코멘트만 다르다). 마지막 요약이 그 주행일 때만. 그 사이 다른 주행이 끝났으면 버린다 */
    fun update(summary: SessionEndSummary) {
        if (_last.value?.record?.id == summary.record.id) _last.value = summary
    }
}

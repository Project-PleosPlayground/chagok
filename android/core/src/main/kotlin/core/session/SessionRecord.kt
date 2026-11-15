package core.session

import engine.DrivingEvent
import engine.EventType
import engine.SessionResult

/** 엔진 이벤트 + 앱이 붙이는 위치·거리 메타. */
data class EventRecord(
    val event: DrivingEvent,
    val distanceFromStartM: Float? = null,   // 세션 시작부터 이 이벤트까지 거리 (구간 바용)
    val roadName: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

/** 저장되는 세션 한 건. 엔진 SessionResult 에 앱 메타를 붙인 것. 통계·종료 화면의 원천 데이터. */
data class SessionRecord(
    val id: String,
    val profileId: String,
    val result: SessionResult,
    val events: List<EventRecord>,
    val startWallMs: Long,          // wall-clock 시각 (result.startMs 는 엔진 시계)
    val endWallMs: Long,
    val origin: String? = null,     // 출발지 도로명·지명
    val destination: String? = null,
    val evSocStartPct: Int? = null,
    val evSocEndPct: Int? = null,
) {
    val distanceKm: Float get() = result.distanceKm
    val score: Int get() = result.score
    val valid: Boolean get() = result.valid
    /**
     * 감점 행동이 하나도 없는 주행. 점수가 아니라 이벤트로 본다:
     * 점수는 거리로 나누고 반올림하므로 긴 주행은 감점 행동이 있어도 100점이 될 수 있다 (스펙 5-1).
     */
    val clean: Boolean get() = result.events.none { it.type in DEDUCTED }
    fun count(type: EventType): Int = result.events.count { it.type == type }
    val laneChanges: Int get() = count(EventType.LANE_CHANGE_SIGNALED) + count(EventType.LANE_CHANGE_NO_SIGNAL) + count(EventType.LANE_CHANGE_WRONG_SIGNAL)
    /** 깜빡이 매칭 비율. 차선변경이 없으면 null */
    val signaledRate: Float? get() = if (laneChanges == 0) null else count(EventType.LANE_CHANGE_SIGNALED).toFloat() / laneChanges

    companion object {
        /** 감점표(스펙 5-1)에서 감점이 붙는 행동. 깜빡이 켠 차선변경은 집계용이라 없다 */
        val DEDUCTED: Set<EventType> = setOf(
            EventType.HARSH_ACCEL, EventType.HARSH_BRAKE,
            EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL,
            EventType.WEAVING_EST, EventType.SPEEDING,
        )
    }
}

/** 차량 상태 스냅샷 (VehicleStatusMonitor 가 만든다). 출발 전 점검·관리 미션의 입력. */
data class VehicleStatus(
    val seatBeltBuckledAtStart: Boolean? = null,
    val doorsClosedAtStart: Boolean? = null,
    val tireWarning: Boolean? = null,
    val telltalesOn: Set<String> = emptySet(),
    val evSocPct: Int? = null,
    val charging: Boolean? = null,
    /** 충전을 멈춘 시점의 잔량 (충전 세션이 끝났을 때만) */
    val chargeStoppedAtPct: Int? = null,
    val minSocThisDayPct: Int? = null,
)

package core.mission

import core.session.EventRecord
import core.session.SessionRecord
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult

/**
 * 주행 중 진행도를 셀 수 있게 지금 주행의 이벤트를 모은다 (07 "판정 시점"). 엔진의 세션 시작·인정·이벤트·끝을 그대로 넘긴다.
 * - 유효 주행으로 인정되기 전에는 [record] 가 null 이다. 인정되지 않은 주행은 진행도에 넣지 않는다.
 * - 인정되면 인정 전에 일어난 이벤트도 함께 센다.
 * 판정은 하지 않는다. 판정은 주행이 끝날 때 [MissionEngine.onDriveEnd] 가 한다.
 */
class LiveDrive(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
) {
    constructor(profileId: String) : this({ profileId })

    private val profileId: String get() = currentProfile()
    private var startWallMs: Long? = null
    private var validated = false
    /** 같은 이벤트가 인정 시점 목록과 이벤트 흐름으로 두 번 올 수 있어 종류·시작 시각으로 거른다 */
    private val events = LinkedHashMap<Pair<EventType, Long>, DrivingEvent>()

    /** 세션이 시작됐다. 지난 주행에서 모은 것은 버린다 */
    fun start(startWallMs: Long) {
        this.startWallMs = startWallMs
        validated = false
        events.clear()
    }

    /** 유효 주행으로 인정됐다. [eventsSoFar] 는 인정 시점까지의 이벤트 전부 */
    fun validate(eventsSoFar: List<DrivingEvent>) {
        if (startWallMs == null) return
        validated = true
        eventsSoFar.forEach(::add)
    }

    /** 엔진 이벤트 하나 */
    fun event(e: DrivingEvent) {
        if (startWallMs != null) add(e)
    }

    /**
     * 세션이 끝났다. [startWallMs] 를 주면 그 주행일 때만 지운다. 끝난 주행의 처리가 다음 주행 시작보다 늦게 와도 다음 주행을 지우지 않는다
     */
    fun end(startWallMs: Long? = null) {
        if (startWallMs != null && startWallMs != this.startWallMs) return
        this.startWallMs = null
        validated = false
        events.clear()
    }

    /**
     * 유효 주행으로 인정된 지금까지의 주행. 인정 전이거나 주행 중이 아니면 null.
     * 거리·점수는 주행이 끝나야 정해져 0 이다. [ConditionRules.LIVE] 조건은 이벤트만 쓴다.
     */
    fun record(): SessionRecord? {
        val start = startWallMs ?: return null
        if (!validated) return null
        val list = events.values.sortedBy { it.startMs }
        val result = SessionResult(start, start, 0f, list, 0, 0, 0, 0, valid = true)
        return SessionRecord(LIVE_ID, profileId, result, list.map { EventRecord(it) }, start, start)
    }

    private fun add(e: DrivingEvent) {
        events[e.type to e.startMs] = e
    }

    private companion object {
        /** 저장된 주행과 겹치지 않는 지금 주행의 id */
        const val LIVE_ID = "live"
    }
}

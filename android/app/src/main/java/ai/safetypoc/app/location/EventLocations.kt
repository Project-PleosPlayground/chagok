package ai.safetypoc.app.location

import engine.DrivingEvent

/** GPS 위치 하나. [timeMs] 는 받은 시각(System.currentTimeMillis). [road] 는 도로명을 알 때만(시연 경로) */
data class GeoFix(val lat: Double, val lng: Double, val timeMs: Long, val road: String? = null)

/**
 * 주행 중 이벤트가 난 순간의 위치를 기억해 두었다가, 주행이 끝나면 기록(EventRecord.lat/lng)에 붙인다 (#13 반복 지점).
 * 위치는 기기 안 기록에만 쓰고 서버로 보내지 않는다(AiPayload 에 좌표가 없다).
 * [lastFix] 가 [maxAgeMs] 보다 오래된 위치면 붙이지 않는다(터널·신호 끊김).
 */
class EventLocations(private val lastFix: () -> GeoFix?, private val maxAgeMs: Long = MAX_AGE_MS) {
    private val byEvent = HashMap<Long, GeoFix>()

    /** 엔진이 이벤트를 낼 때 부른다. 이벤트 시작 시각으로 기억한다 */
    @Synchronized fun onEvent(e: DrivingEvent) {
        val fix = lastFix() ?: return
        if (kotlin.math.abs(e.startMs - fix.timeMs) <= maxAgeMs) byEvent[e.startMs] = fix
    }

    /** 끝난 주행의 이벤트 위치. 없으면 null */
    @Synchronized fun of(e: DrivingEvent): GeoFix? = byEvent[e.startMs]

    /** 주행이 끝나 기록에 붙인 뒤 비운다 */
    @Synchronized fun clear() = byEvent.clear()

    companion object { const val MAX_AGE_MS = 30_000L }
}

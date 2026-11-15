package ai.safetypoc.app.location

import engine.DrivingEvent
import engine.SessionResult
import kotlin.random.Random

/**
 * 디버그 탭 시연 주행용 가짜 경로. 차가 없으니 이벤트 위치를 경로 위 비율로 정한다.
 * 같은 시연을 여러 번 돌리면 같은 곳 근처(±20m)에 이벤트가 쌓여 반복 지점이 생긴다.
 * 경로는 공개 지명(강남역 → 선릉역 테헤란로 직선 근사)이고 개인 위치가 아니다.
 */
object DemoRoute {
    /** 시연 경로 하나: 출발·도착 좌표, 도로명, 출발·도착 이름 */
    data class Route(val start: Pair<Double, Double>, val end: Pair<Double, Double>, val road: String, val origin: String, val destination: String)

    /** 기본 시연 경로: 강남역 → 선릉역 (테헤란로) */
    val GANGNAM = Route(37.4979 to 127.0276, 37.5045 to 127.0490, "테헤란로", "강남역", "선릉역")
    /** 장소 선물 시연(11/15): 서울역 → 남산. 도착하면 남산타워 배경을 받는다 */
    val NAMSAN = Route(37.5547 to 126.9707, 37.5512 to 126.9882, "소월로", "서울역", "남산")

    private const val JITTER_DEG = 0.0002
    /** 시연 경로의 도로명과 출발·도착지. 기기 지오코더가 없는 에뮬레이터에서도 주행 상세에 보이게 정해 둔다 */
    const val ROAD = "테헤란로"
    const val ORIGIN = "강남역"
    const val DESTINATION = "선릉역"

    fun locate(result: SessionResult, route: Route = GANGNAM, random: Random = Random.Default): (DrivingEvent) -> GeoFix? {
        val span = (result.endMs - result.startMs).coerceAtLeast(1)
        return { e ->
            val f = ((e.startMs - result.startMs).toDouble() / span).coerceIn(0.0, 1.0)
            GeoFix(
                route.start.first + (route.end.first - route.start.first) * f + random.nextDouble(-JITTER_DEG, JITTER_DEG),
                route.start.second + (route.end.second - route.start.second) * f + random.nextDouble(-JITTER_DEG, JITTER_DEG),
                e.startMs,
                route.road,
            )
        }
    }

    /** 도착 자리(경로 끝) */
    fun arrival(route: Route, timeMs: Long) = GeoFix(route.end.first, route.end.second, timeMs, route.road)
}

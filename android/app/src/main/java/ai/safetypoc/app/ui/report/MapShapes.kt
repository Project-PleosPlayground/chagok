package ai.safetypoc.app.ui.report

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 지도에 그릴 모양. 지도 SDK 와 상관없는 계산이라 JVM 테스트로 확인한다 */
object MapShapes {
    private const val EARTH_M = 6_371_000.0

    /**
     * 중심 ([lat],[lng]) 반경 [radiusM] 원을 [n] 각형 (위도, 경도) 꼭짓점으로. 처음과 끝이 같다(닫힌 고리).
     * 자주 놀라는 곳(core Hotspots.RADIUS_M 200m)을 지도에 실제 크기로 그린다.
     */
    fun circle(lat: Double, lng: Double, radiusM: Double, n: Int = 48): List<Pair<Double, Double>> {
        val dLat = radiusM / EARTH_M * 180 / PI
        val dLng = dLat / cos(lat * PI / 180)
        val ring = (0 until n).map { i -> val a = 2 * PI * i / n; (lat + dLat * sin(a)) to (lng + dLng * cos(a)) }
        return ring + ring.first()
    }

    /** 점들을 다 담는 (남, 서, 북, 동). 비어 있으면 null */
    fun bounds(points: List<Pair<Double, Double>>): DoubleArray? {
        if (points.isEmpty()) return null
        return doubleArrayOf(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }
}

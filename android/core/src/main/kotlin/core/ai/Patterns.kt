package core.ai

import core.session.SessionRecord
import engine.EventType
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 주행 패턴 분석 (#13). AI 를 쓰지 않는 규칙 계산이다. 결과는 리포트 입력(AiPayload)과 화면(반복 지점 지도)에 쓴다.
 * - 100km 당 횟수: 많이 달린 주와 적게 달린 주를 같은 잣대로 본다
 * - 지난주 대비: 급제동·급가속·깜빡이 없는 차선변경(100km 당), 깜빡이 켠 비율
 * - 지난번 대비: 바로 전 주행과 횟수 비교 (거리가 비슷할 때만)
 * - 시간대 쏠림: 감점 행동이 특정 시간대 주행에 몰리는지 (그 시간대 주행 거리 비율과 비교)
 * - 반복 지점: 반경 [Hotspots.RADIUS_M] 안에서 서로 다른 주행 2번 이상 감점 행동이 난 곳. 좌표는 기기 안에서만 쓴다
 */
object DrivePatterns {
    /** 100km 당 횟수를 낼 최소 거리. 이보다 적게 달린 기간은 비교하지 않는다 */
    const val MIN_KM_FOR_RATE = 20f
    /** 깜빡이 비율을 비교할 최소 차선변경 수 (기간마다) */
    const val MIN_LANE_CHANGES = 5
    /** 시간대 쏠림을 말할 최소 감점 행동 수 */
    const val MIN_EVENTS_FOR_TIME = 4
    /** 바로 전 주행과 횟수를 비교할 수 있는 거리 차이 (긴 쪽 / 짧은 쪽) */
    const val LAST_DRIVE_KM_RATIO = 2f

    val BAD: List<Pair<String, Set<EventType>>> = listOf(
        "harsh_brake" to setOf(EventType.HARSH_BRAKE),
        "harsh_accel" to setOf(EventType.HARSH_ACCEL),
        "lane_no_signal" to setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL),
    )
    private val LABEL = mapOf("harsh_brake" to "급제동", "harsh_accel" to "급가속", "lane_no_signal" to "깜빡이 없는 차선변경")

    data class Rates(val harsh_brake: Float, val harsh_accel: Float, val lane_no_signal: Float)

    private fun km(rs: List<SessionRecord>) = rs.sumOf { it.distanceKm.toDouble() }.toFloat()
    private fun cnt(rs: List<SessionRecord>, types: Set<EventType>) = rs.sumOf { r -> r.result.events.count { it.type in types } }

    /** 100km 당 횟수. 거리가 [MIN_KM_FOR_RATE] 미만이면 null */
    fun per100km(rs: List<SessionRecord>): Rates? {
        val d = km(rs)
        if (d < MIN_KM_FOR_RATE) return null
        fun r(types: Set<EventType>) = round1(cnt(rs, types) * 100f / d)
        return Rates(r(BAD[0].second), r(BAD[1].second), r(BAD[2].second))
    }

    /**
     * 지난주(그 전 7일) 대비. 값은 "늘었음 (…)" / "줄었음 (…, 좋아짐)" / "같음 (…)" / "비교 없음".
     * 문장을 쓰는 쪽(서버·AI)은 숫자를 다시 비교하지 않고 이 말만 쓴다.
     */
    fun trends(last7: List<SessionRecord>, prev7: List<SessionRecord>): Map<String, String> {
        val cur = per100km(last7); val prev = per100km(prev7)
        val out = LinkedHashMap<String, String>()
        for ((key, _) in BAD) {
            if (cur == null || prev == null) { out[key] = "비교 없음"; continue }
            val c = cur.get(key); val p = prev.get(key)
            out[key] = when {
                c == 0f && p == 0f -> "같음 (지난주도 이번 주도 0회)"
                kotlin.math.abs(c - p) < max(0.5f, 0.2f * p) -> "같음 (100km당 ${fmt(p)}회 → ${fmt(c)}회)"
                c < p -> "줄었음 (100km당 ${fmt(p)}회 → ${fmt(c)}회, 좋아짐)"
                else -> "늘었음 (100km당 ${fmt(p)}회 → ${fmt(c)}회)"
            }
        }
        val lc = last7.sumOf { it.laneChanges }; val lp = prev7.sumOf { it.laneChanges }
        out["signaled_rate"] = if (lc < MIN_LANE_CHANGES || lp < MIN_LANE_CHANGES) "비교 없음" else {
            val c = cnt(last7, setOf(EventType.LANE_CHANGE_SIGNALED)) * 100 / lc
            val p = cnt(prev7, setOf(EventType.LANE_CHANGE_SIGNALED)) * 100 / lp
            when {
                kotlin.math.abs(c - p) < 10 -> "같음 (깜빡이 켠 비율 $p% → $c%)"
                c > p -> "늘었음 (깜빡이 켠 비율 $p% → $c%, 좋아짐)"
                else -> "줄었음 (깜빡이 켠 비율 $p% → $c%)"
            }
        }
        return out
    }

    /**
     * 바로 전 주행 대비 (기획안 4절 "오늘은 지난번보다 급제동이 줄었어!"). 값은 [trends] 와 같은 말.
     * 횟수로 비교하므로 두 주행 거리가 [LAST_DRIVE_KM_RATIO] 배 넘게 차이 나면 "비교 없음"이다(5km 와 50km 주행의 횟수는 비교하지 않는다).
     */
    fun vsLastDrive(cur: SessionRecord, prev: SessionRecord?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val comparable = prev != null && cur.distanceKm > 0f && prev.distanceKm > 0f &&
            max(cur.distanceKm, prev.distanceKm) / kotlin.math.min(cur.distanceKm, prev.distanceKm) <= LAST_DRIVE_KM_RATIO
        for ((key, types) in BAD) {
            if (!comparable) { out[key] = "비교 없음"; continue }
            val c = cnt(listOf(cur), types); val p = cnt(listOf(prev!!), types)
            out[key] = when {
                c == p -> "같음 (지난번 ${p}회 → 이번 ${c}회)"
                c < p -> "줄었음 (지난번 ${p}회 → 이번 ${c}회, 좋아짐)"
                else -> "늘었음 (지난번 ${p}회 → 이번 ${c}회)"
            }
        }
        return out
    }

    /**
     * 감점 행동이 한 시간대 주행에 쏠렸는지. 그 시간대가 행동의 60% 이상이고, 그 시간대 주행 거리 비율보다 20%p 이상 높을 때만 문장을 낸다.
     * [timeOfDay] 는 주행 시작 시각 → "아침"·"저녁" 등.
     */
    fun timeConcentration(last7: List<SessionRecord>, timeOfDay: (Long) -> String): String? {
        val deducted = SessionRecord.DEDUCTED
        val total = cnt(last7, deducted)
        if (total < MIN_EVENTS_FOR_TIME) return null
        val byBucket = last7.groupBy { timeOfDay(it.startWallMs) }
        if (byBucket.size < 2) return null
        val (bucket, rs) = byBucket.maxByOrNull { cnt(it.value, deducted) } ?: return null
        val n = cnt(rs, deducted)
        val share = n.toFloat() / total
        val kmShare = km(rs) / max(km(last7), 0.1f)
        if (share < 0.6f || share - kmShare < 0.2f) return null
        val top = BAD.maxByOrNull { cnt(rs, it.second) }!!
        return "최근 7일 급제동·급가속·깜빡이 실수 ${total}번 중 ${n}번이 $bucket 주행에서 났다 ($bucket 주행은 전체 거리의 ${(kmShare * 100).roundToInt()}%). 그중 ${LABEL[top.first]}이 ${cnt(rs, top.second)}번"
    }

    private fun Rates.get(key: String) = when (key) { "harsh_brake" -> harsh_brake; "harsh_accel" -> harsh_accel; else -> lane_no_signal }
    private fun round1(x: Float) = (x * 10f).roundToInt() / 10f
    private fun fmt(x: Float) = if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString()
}

/** 반복 지점 하나. 좌표는 기기 안 지도에만 쓰고 서버로 보내지 않는다 */
data class Hotspot(
    val lat: Double, val lng: Double,
    /** 이 지점의 감점 행동 수 */
    val events: Int,
    /** 그 행동이 난 서로 다른 주행 수 */
    val drives: Int,
    val types: Map<EventType, Int>,
    /** 기록에 도로명이 있으면 가장 많이 나온 것 */
    val roadName: String?,
)

object Hotspots {
    const val RADIUS_M = 200.0
    const val MIN_DRIVES = 2

    /** 두 좌표 사이 거리(m), haversine */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(a))
    }

    private class Cluster(var lat: Double, var lng: Double) {
        val items = ArrayList<Triple<String, EventType, String?>>()   // 주행 id, 종류, 도로명
    }

    /**
     * 좌표가 있는 감점 행동을 반경 [radiusM] 로 묶는다(앞에서부터 가장 가까운 묶음에 넣고 중심을 평균으로 옮김).
     * 서로 다른 주행 [minDrives] 번 이상인 묶음만, 행동 수가 많은 순으로.
     */
    fun find(records: List<SessionRecord>, radiusM: Double = RADIUS_M, minDrives: Int = MIN_DRIVES): List<Hotspot> {
        val clusters = ArrayList<Cluster>()
        for (r in records.sortedBy { it.startWallMs }) for (e in r.events) {
            val lat = e.lat ?: continue; val lng = e.lng ?: continue
            if (e.event.type !in SessionRecord.DEDUCTED) continue
            val near = clusters.minByOrNull { distanceM(it.lat, it.lng, lat, lng) }?.takeIf { distanceM(it.lat, it.lng, lat, lng) <= radiusM }
            val c = near ?: Cluster(lat, lng).also { clusters += it }
            val n = c.items.size
            c.lat = (c.lat * n + lat) / (n + 1); c.lng = (c.lng * n + lng) / (n + 1)
            c.items += Triple(r.id, e.event.type, e.roadName)
        }
        return clusters.map { c ->
            Hotspot(
                c.lat, c.lng, c.items.size, c.items.map { it.first }.distinct().size,
                c.items.groupingBy { it.second }.eachCount(),
                c.items.mapNotNull { it.third }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            )
        }.filter { it.drives >= minDrives }.sortedByDescending { it.events }
    }

    /** [lat],[lng] 가 반복 지점 중 하나의 반경 안인지 */
    fun near(spots: List<Hotspot>, lat: Double?, lng: Double?, radiusM: Double = RADIUS_M): Boolean =
        lat != null && lng != null && spots.any { distanceM(it.lat, it.lng, lat, lng) <= radiusM }
}

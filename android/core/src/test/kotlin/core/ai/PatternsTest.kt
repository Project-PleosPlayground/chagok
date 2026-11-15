package core.ai

import core.Fx
import core.character.Profile
import core.mission.InMemoryMissionStore
import core.session.InMemorySessionStore
import core.session.SessionRecord
import core.session.VehicleStatus
import engine.EventType.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PatternsTest {
    private val H = 3_600_000L
    private val builder = AiPayloadBuilder(InMemorySessionStore(), InMemoryMissionStore())

    /** 이벤트마다 좌표를 붙인다 (i 번째 이벤트 → points[i]) */
    private fun SessionRecord.at(vararg points: Pair<Double, Double>?) =
        copy(events = events.mapIndexed { i, e -> points.getOrNull(i)?.let { (la, ln) -> e.copy(lat = la, lng = ln) } ?: e.copy(lat = null, lng = null) })

    // 강남역 사거리 근처와 그 옆 150m, 2km 떨어진 곳
    private val A = 37.4979 to 127.0276
    private val A2 = 37.4990 to 127.0285   // A 에서 약 145m
    private val B = 37.5150 to 127.0400

    @Test fun `100km 당 횟수 - 거리가 모자라면 null`() {
        val rs = listOf(Fx.record("a", Fx.MON, km = 25f, events = List(5) { Fx.ev(HARSH_BRAKE) } + Fx.ev(HARSH_ACCEL)))
        val r = DrivePatterns.per100km(rs)!!
        assertEquals(20f, r.harsh_brake); assertEquals(4f, r.harsh_accel); assertEquals(0f, r.lane_no_signal)
        assertNull(DrivePatterns.per100km(listOf(Fx.record("b", Fx.MON, km = 10f))))
    }

    @Test fun `지난주 대비 - 거리로 나눠 본다, 많이 달려서 횟수가 늘어도 줄었음`() {
        // 지난주 30km 에 급제동 6번(100km당 20), 이번 주 90km 에 9번(100km당 10)
        val prev = listOf(Fx.record("p", Fx.MON - 7 * Fx.DAY, km = 30f, events = List(6) { Fx.ev(HARSH_BRAKE) }))
        val cur = listOf(Fx.record("c", Fx.MON, km = 90f, events = List(9) { Fx.ev(HARSH_BRAKE) } + List(3) { Fx.ev(HARSH_ACCEL) }))
        val t = DrivePatterns.trends(cur, prev)
        assertTrue(t["harsh_brake"]!!.startsWith("줄었음"), t["harsh_brake"])
        assertTrue(t["harsh_brake"]!!.contains("20회 → 10회"), t["harsh_brake"])
        assertTrue(t["harsh_accel"]!!.startsWith("늘었음"), t["harsh_accel"])
        assertEquals("같음 (지난주도 이번 주도 0회)", t["lane_no_signal"])
        assertEquals("비교 없음", t["signaled_rate"], "차선변경이 모자라면 비교하지 않는다")
        assertTrue(DrivePatterns.trends(cur, emptyList()).values.all { it == "비교 없음" }, "지난주 주행이 없으면 전부 비교 없음")
    }

    @Test fun `지난주 대비 - 작은 차이는 같음, 깜빡이 비율은 높을수록 좋아짐`() {
        val prev = listOf(Fx.record("p", Fx.MON - 7 * Fx.DAY, km = 50f, events = List(10) { Fx.ev(HARSH_BRAKE) } + List(3) { Fx.ev(LANE_CHANGE_SIGNALED) } + List(3) { Fx.ev(LANE_CHANGE_NO_SIGNAL) }))
        val cur = listOf(Fx.record("c", Fx.MON, km = 50f, events = List(11) { Fx.ev(HARSH_BRAKE) } + List(9) { Fx.ev(LANE_CHANGE_SIGNALED) } + Fx.ev(LANE_CHANGE_NO_SIGNAL)))
        val t = DrivePatterns.trends(cur, prev)
        assertTrue(t["harsh_brake"]!!.startsWith("같음"), "100km당 20 → 22 는 20% 안쪽: ${t["harsh_brake"]}")
        assertEquals("늘었음 (깜빡이 켠 비율 50% → 90%, 좋아짐)", t["signaled_rate"])
        assertTrue(t["lane_no_signal"]!!.startsWith("줄었음"), t["lane_no_signal"])
    }

    @Test fun `지난번 대비 - 횟수로 비교하고, 거리가 두 배 넘게 다르면 비교 없음`() {
        val prev = Fx.record("p", Fx.MON, km = 12f, events = List(2) { Fx.ev(HARSH_BRAKE) } + Fx.ev(LANE_CHANGE_NO_SIGNAL))
        val cur = Fx.record("c", Fx.MON + Fx.DAY, km = 15f, events = listOf(Fx.ev(HARSH_ACCEL), Fx.ev(LANE_CHANGE_WRONG_SIGNAL)))
        val t = DrivePatterns.vsLastDrive(cur, prev)
        assertEquals("줄었음 (지난번 2회 → 이번 0회, 좋아짐)", t["harsh_brake"])
        assertEquals("늘었음 (지난번 0회 → 이번 1회)", t["harsh_accel"])
        assertEquals("같음 (지난번 1회 → 이번 1회)", t["lane_no_signal"], "반대 깜빡이도 깜빡이 없는 차선변경으로 센다")
        assertTrue(DrivePatterns.vsLastDrive(cur, null).values.all { it == "비교 없음" })
        val long = Fx.record("l", Fx.MON + Fx.DAY, km = 40f)
        assertTrue(DrivePatterns.vsLastDrive(long, prev).values.all { it == "비교 없음" }, "12km 와 40km 는 횟수로 비교하지 않는다")
    }

    @Test fun `시간대 쏠림 - 행동이 저녁에 몰리고 저녁 주행 거리는 적을 때만`() {
        val morning = (0..2).map { Fx.record("m$it", Fx.MON + it * Fx.DAY + 8 * H, km = 20f, events = listOf(Fx.ev(HARSH_BRAKE))) }
        val evening = (0..1).map { Fx.record("e$it", Fx.MON + it * Fx.DAY + 18 * H, km = 10f, events = List(4) { Fx.ev(HARSH_BRAKE) } + Fx.ev(HARSH_ACCEL)) }
        val s = DrivePatterns.timeConcentration(morning + evening, builder::timeOfDay)!!
        assertTrue(s.contains("13번 중 10번이 저녁 주행"), s); assertTrue(s.contains("전체 거리의 25%"), s); assertTrue(s.contains("급제동이 8번"), s)
        // 거리 비율만큼만 나면 쏠림이 아니다
        val even = (0..1).map { Fx.record("x$it", Fx.MON + it * Fx.DAY + 8 * H, km = 10f, events = List(3) { Fx.ev(HARSH_BRAKE) }) } +
            (0..1).map { Fx.record("y$it", Fx.MON + it * Fx.DAY + 18 * H, km = 10f, events = List(3) { Fx.ev(HARSH_BRAKE) }) }
        assertNull(DrivePatterns.timeConcentration(even, builder::timeOfDay))
        assertNull(DrivePatterns.timeConcentration(evening, builder::timeOfDay), "한 시간대만 달렸으면 쏠림을 말하지 않는다")
    }

    @Test fun `반복 지점 - 반경 200m 안, 서로 다른 주행 2번 이상만`() {
        val r1 = Fx.record("1", Fx.MON, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(HARSH_ACCEL, 20_000))).at(A, B)
        val r2 = Fx.record("2", Fx.MON + Fx.DAY, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(LANE_CHANGE_SIGNALED, 20_000))).at(A2, A)
        val r3 = Fx.record("3", Fx.MON + 2 * Fx.DAY, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(HARSH_BRAKE, 20_000))).at(B, null)
        val spots = Hotspots.find(listOf(r1, r2, r3))
        assertEquals(2, spots.size, "A(1·2번 주행), B(1·3번 주행). 깜빡이 켠 차선변경과 좌표 없는 이벤트는 안 센다")
        val a = spots.first { Hotspots.distanceM(it.lat, it.lng, A.first, A.second) < 200 }
        assertEquals(2, a.events); assertEquals(2, a.drives); assertEquals(mapOf(HARSH_BRAKE to 2), a.types); assertEquals("테헤란로", a.roadName)
        assertTrue(Hotspots.near(spots, A.first, A.second)); assertFalse(Hotspots.near(spots, 37.60, 127.10)); assertFalse(Hotspots.near(spots, null, null))
        // 한 주행 안에서만 반복된 곳은 반복 지점이 아니다
        assertTrue(Hotspots.find(listOf(Fx.record("s", Fx.MON, events = List(3) { Fx.ev(HARSH_BRAKE) }).at(A, A, A))).isEmpty())
        assertEquals(145.0, Hotspots.distanceM(A.first, A.second, A2.first, A2.second), 10.0)
    }

    @Test fun `리포트 입력 - 지난주 대비·패턴·반복 지점 표시, 좌표와 도로명은 안 보낸다`() {
        val sessions = InMemorySessionStore()
        val b = AiPayloadBuilder(sessions, InMemoryMissionStore())
        val mon = Fx.MON + 18 * H
        sessions.save(Fx.record("prev", mon - 7 * Fx.DAY, km = 40f, events = List(8) { Fx.ev(HARSH_BRAKE) }).at(B, B, B, B, B, B, B, B))
        sessions.save(Fx.record("old", mon - 2 * Fx.DAY, km = 30f, events = listOf(Fx.ev(HARSH_BRAKE))).at(A))
        sessions.save(Fx.record("m", mon - Fx.DAY - 10 * H, km = 30f))
        val r = Fx.record("now", mon, km = 30f, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(HARSH_BRAKE, 300_000))).at(A2, B)
        sessions.save(r)
        val pl = b.driveEnd(r, AiCharacter("볼트", 1, "x"), VehicleStatus(), r.endWallMs)
        assertEquals(listOf(true, true), pl.session.events.map { it.at_hotspot }, "A2 는 이틀 전 A 와, B 는 지난주 B 와 반복")
        assertEquals(2, pl.history.hotspots_30d)
        assertTrue(pl.history.trends["harsh_brake"]!!.startsWith("줄었음"), pl.history.trends.toString())
        assertEquals(3.3f, pl.history.per100km_7d!!.harsh_brake)
        assertTrue(pl.history.patterns.any { it.contains("2군데") }, pl.history.patterns.toString())
        val json = AiJson.encode(pl)
        assertFalse(json.contains("37.") || json.contains("127."), "좌표 없음"); assertFalse(json.contains("테헤란로"), "도로명 없음")
    }
}

package ai.safetypoc.app.location

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.drive.DemoDriveReplay
import ai.safetypoc.app.drive.DriveRecorder
import core.ai.Hotspots
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EventLocationsTest {
    private val t0 = 1_800_000_000_000L
    private fun ev(at: Long) = DrivingEvent(EventType.HARSH_BRAKE, 1, at, at + 500, 15f)

    @Test fun `이벤트 순간의 위치를 기억하고, 오래된 위치는 붙이지 않는다`() {
        var fix: GeoFix? = GeoFix(37.5, 127.0, t0)
        val locs = EventLocations({ fix })
        locs.onEvent(ev(t0 + 5_000))
        fix = GeoFix(37.6, 127.1, t0 + 10_000)
        locs.onEvent(ev(t0 + 100_000))   // 90초 전 위치뿐 → 안 붙임
        assertEquals(37.5, locs.of(ev(t0 + 5_000))!!.lat, 1e-9)
        assertNull(locs.of(ev(t0 + 100_000)))
        locs.clear(); assertNull(locs.of(ev(t0 + 5_000)))
        fix = null; locs.onEvent(ev(t0)); assertNull("GPS 없으면 없음", locs.of(ev(t0)))
    }

    @Test fun `시연 주행 두 번이면 급제동 위치가 겹쳐 반복 지점이 생긴다`() {
        val graph = AppGraph()
        val rec = DriveRecorder(graph)
        val rnd = Random(3)
        repeat(2) { i ->
            val r = DemoDriveReplay.run(AppConfig.engine, t0 + i * 3_600_000L)!!
            val s = rec.onSessionEnded(r, VehicleStatus(), t0 + i * 3_600_000L + 600_000, DemoRoute.locate(r, random = rnd))
            assertTrue("이벤트에 위치가 붙는다", s.record.events.all { it.lat != null && it.lng != null })
        }
        val spots = Hotspots.find(graph.sessions.list(graph.profileId))
        assertEquals(1, spots.size); assertEquals(2, spots[0].drives)
    }

    @Test fun `위치를 모르면 기록은 좌표 없이 저장된다`() {
        val graph = AppGraph()
        val r = DemoDriveReplay.run(AppConfig.engine, t0)!!
        val s = DriveRecorder(graph).onSessionEnded(r, VehicleStatus(), t0 + 600_000)
        assertTrue(s.record.events.all { it.lat == null })
    }
}

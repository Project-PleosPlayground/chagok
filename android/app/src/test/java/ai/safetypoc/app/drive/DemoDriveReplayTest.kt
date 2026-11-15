package ai.safetypoc.app.drive

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.location.DemoRoute
import core.mission.ConditionType
import core.mission.MissionKind
import core.session.VehicleStatus
import engine.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoDriveReplayTest {
    private val start = 1_800_000_000_000L

    @Test fun `시연 주행은 유효 주행이고 급제동이 1회 잡힌다`() {
        val r = DemoDriveReplay.run(AppConfig.engine, start)
        assertNotNull(r); r!!
        assertTrue(r.valid)
        assertEquals(1, r.events.count { it.type == EventType.HARSH_BRAKE })
        assertEquals(0, r.events.count { it.type == EventType.HARSH_ACCEL })
        assertTrue(r.distanceKm >= 1f)
    }

    @Test fun `주행은 주어진 시각 뒤에 시작해 주행 길이 안에 끝난다`() {
        val r = DemoDriveReplay.run(AppConfig.engine, start)!!
        assertTrue(r.startMs >= start)
        assertTrue(r.endMs <= start + DemoDriveReplay.durationMs())
    }

    @Test fun `20분 주행은 약 20분이고 급가속 3회, 급제동 2회가 잡힌다`() {
        val minutes = DemoDriveReplay.durationMs(long = true) / 60_000.0
        assertTrue("길이 $minutes 분", minutes in 18.0..22.0)
        val r = DemoDriveReplay.run(AppConfig.engine, start, long = true)!!
        assertTrue(r.valid)
        assertEquals(3, r.events.count { it.type == EventType.HARSH_ACCEL })
        assertEquals(2, r.events.count { it.type == EventType.HARSH_BRAKE })
    }

    @Test fun `도전한 뒤 재생한 주행은 급가속 이하 미션을 성공시킨다`() {
        val graph = AppGraph()
        val pid = graph.profileId
        val m = graph.missions.offer(pid, MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, tier = 1, nowMs = start)!!
        graph.missionActions.challenge(pid, m.uid, start, driving = false)
        val r = DemoDriveReplay.run(AppConfig.engine, start + 1)!!
        val summary = DriveRecorder(graph).onSessionEnded(r, VehicleStatus(), start)
        assertEquals(listOf(m.uid), summary.completed.map { it.instance.uid })
        assertEquals(m.rewardCoins, summary.coinsGained)
    }

    @Test fun `시연 경로 주행은 출발·도착지와 사건 도로명·좌표를 기록에 남긴다`() {
        val graph = AppGraph()
        val r = DemoDriveReplay.run(AppConfig.engine, start, long = true)!!
        val rec = DriveRecorder(graph).onSessionEnded(
            r, VehicleStatus(), start, where = DemoRoute.locate(r), origin = DemoRoute.ORIGIN, destination = DemoRoute.DESTINATION,
        ).record
        assertEquals(DemoRoute.ORIGIN, rec.origin); assertEquals(DemoRoute.DESTINATION, rec.destination)
        assertTrue(rec.events.isNotEmpty())
        assertTrue(rec.events.all { it.roadName == DemoRoute.ROAD && it.lat != null && it.lng != null })
    }
}

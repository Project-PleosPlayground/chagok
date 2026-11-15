package ai.safetypoc.app.drive

import ai.safetypoc.app.AppGraph
import core.mission.ConditionType
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRecorderTest {
    private val graph = AppGraph()
    private val logs = ArrayList<String>()
    private val recorder = DriveRecorder(graph, log = { logs += it })
    private val start = 1_793_000_000_000L
    private val minute = 60_000L

    private fun result(km: Float, minutes: Int, valid: Boolean, vararg events: EventType) =
        SessionResult(start, start + minutes * minute, km, events.mapIndexed { i, t -> DrivingEvent(t, 1, start + i * 1000L, start + i * 1000L + 500, 15f) }, 95, 100, 95, 100, valid)

    @Test fun `유효 주행은 저장하고 미션 판정·보상까지 한 뒤 요약을 남긴다`() {
        val pid = graph.profileId
        val m = graph.missions.offer(pid, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, start - minute)!!   // 1회 이하
        graph.missions.challenge(pid, m.uid, start - minute, driving = false)
        val s = recorder.onSessionEnded(result(4f, 4, true, EventType.HARSH_BRAKE), VehicleStatus(), start + 5 * minute)
        assertEquals(1, graph.sessions.list(pid).size)
        assertEquals(MissionStatus.SUCCEEDED, graph.missionStore.list(pid).single { it.uid == m.uid }.status)
        assertEquals(60, s.coinsGained)
        assertEquals(15 + graph.content.active.missions.rules.validDriveXp, s.xpGained)
        assertEquals(s, recorder.last.value)
        assertTrue(logs.single().contains("성공 미션 1개"))
    }

    @Test fun `유효하지 않은 주행도 저장하지만 보상은 없다`() {
        val s = recorder.onSessionEnded(result(0.4f, 2, false), VehicleStatus(), start + 3 * minute)
        assertEquals(1, graph.sessions.list(graph.profileId).size)
        assertEquals(0, s.coinsGained); assertEquals(0, s.xpGained)
    }
}

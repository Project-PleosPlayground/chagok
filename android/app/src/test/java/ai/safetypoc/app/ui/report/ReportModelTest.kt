package ai.safetypoc.app.ui.report

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.drive.DemoDriveReplay
import ai.safetypoc.app.drive.DriveRecorder
import ai.safetypoc.app.location.GeoFix
import core.session.SessionRecord
import core.session.VehicleStatus
import engine.EventType
import engine.SessionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportModelTest {
    private val graph = AppGraph()
    private val drives = DriveRecorder(graph)
    private val report = ReportModel({ graph.profileId }, graph.sessions)
    private val now = 1_800_000_000_000L

    private fun demoDrive() = DemoDriveReplay.run(AppConfig.engine, now)!!.let { drives.onSessionEnded(it, VehicleStatus(), now) }

    @Test fun `주행 기록이 없으면 리포트가 비어 있다`() {
        report.refresh()
        assertNull(report.state.value.session)
    }

    @Test fun `리포트는 마지막 주행의 점수와 세부 점수 옆 이벤트 횟수를 보여 준다`() {
        val summary = demoDrive()
        report.refresh()
        val s = report.state.value.session!!
        assertTrue(s.valid); assertEquals(summary.record.score, s.score)
        assertEquals(listOf(SubScore.ACCEL, SubScore.BRAKE, SubScore.MANNER), s.lines.map { it.kind })
        val brake = s.lines.first { it.kind == SubScore.BRAKE }
        assertEquals(summary.record.result.brakeScore, brake.score)
        assertEquals(listOf(EventCount(EventType.HARSH_BRAKE, 1)), brake.events)
        assertEquals(listOf(EventCount(EventType.HARSH_ACCEL, 0)), s.lines.first { it.kind == SubScore.ACCEL }.events)
        assertEquals(summary.record.endWallMs - summary.record.startWallMs, s.durationMs)
    }

    @Test fun `주행 종료는 받은 포인트·경험치·성공 미션·지금 포인트를 보여 준다`() {
        val summary = demoDrive()
        val end = summary.toDriveEnd(graph.growth)
        assertEquals(graph.profiles.state(graph.profileId).coins, end.points)
        assertEquals(summary.coinsGained, end.coinsGained)
        assertEquals(summary.xpGained, end.xpGained)
        assertEquals(summary.leveledUpTo, end.leveledUpTo)
        assertEquals(summary.completed.map { CompletedMission(it.instance.title, it.coins, it.xp) }, end.completed)
        assertTrue(end.xpGained > 0)
        assertTrue(end.session.valid)
    }

    /** 1분·0.3km 주행: 유효 주행 조건(1km·3분) 미달 */
    private fun shortDrive(startMs: Long, recorder: DriveRecorder = drives) = SessionResult(
        startMs = startMs, endMs = startMs + SHORT_MS, distanceKm = 0.3f, events = emptyList(),
        score = 0, accelScore = 0, brakeScore = 0, mannerScore = 0, valid = false,
    ).let { recorder.onSessionEnded(it, VehicleStatus(), startMs + SHORT_MS) }

    @Test fun `짧은 주행은 주행 종료에 점수 없음으로 나오고 리포트에는 넣지 않고 로그만 남긴다`() {
        val logs = mutableListOf<String>()
        val recorder = DriveRecorder(graph, log = { logs += it })
        val end = shortDrive(now, recorder).toDriveEnd(graph.growth)
        assertFalse(end.session.valid)
        assertEquals(end, recorder.last.value?.toDriveEnd(graph.growth))
        assertTrue(logs.any { "리포트에 넣지 않음" in it })
        report.refresh()
        assertNull(report.state.value.session)
    }

    @Test fun `짧은 주행 뒤에도 리포트는 마지막 유효 주행을 보여 준다`() {
        val valid = demoDrive()
        shortDrive(valid.record.endWallMs + 60_000L)
        report.refresh()
        val s = report.state.value.session!!
        assertTrue(s.valid); assertEquals(valid.record.score, s.score)
    }

    private companion object {
        const val SHORT_MS = 60_000L
    }

    @Test fun `리포트는 최근 30일 위치가 있는 감점 행동과 자주 놀라는 곳을 모은다`() {
        val a = 37.4979 to 127.0276
        fun drive(at: Long, where: Pair<Double, Double>?) = DemoDriveReplay.run(AppConfig.engine, at)!!.let { r ->
            drives.onSessionEnded(r, VehicleStatus(), at, where = { where?.let { (la, ln) -> GeoFix(la, ln, at) } })
        }
        val model = ReportModel({ graph.profileId }, graph.sessions) { now }
        val old = drive(now - 40 * DAY_MS, a)
        model.refresh()
        assertTrue("30일보다 오래된 주행은 넣지 않는다", model.state.value.places.isEmpty())
        val placed = old.record.events.count { it.event.type in SessionRecord.DEDUCTED }
        assertTrue(placed > 0)
        drive(now - DAY_MS, a); model.refresh()
        assertEquals(placed, model.state.value.places.size)
        assertTrue("주행 한 번으로는 자주 놀라는 곳이 아니다", model.state.value.hotspots.isEmpty())
        drive(now, a); model.refresh()
        assertEquals(2, model.state.value.hotspots.single().drives)
        drive(now, null); model.refresh()
        assertEquals("위치 없는 주행은 핀이 없다", 2 * placed, model.state.value.places.size)
    }
}

private const val DAY_MS = 86_400_000L

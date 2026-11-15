package ai.safetypoc.app.ui.stats

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.drive.DemoDriveReplay
import ai.safetypoc.app.drive.DriveRecorder
import core.session.VehicleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StatsModelTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val graph = AppGraph()
    private val drives = DriveRecorder(graph)
    /** 2026-11-14(토) 12:00 KST */
    private var now = LocalDate.of(2026, 11, 14).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val model = StatsModel({ graph.profileId }, graph.sessions, graph.missionStore, graph.profiles, { "한 줄" }, { now }, { zone })

    private fun demoDrive(at: Long) = DemoDriveReplay.run(AppConfig.engine, at)!!.let { drives.onSessionEnded(it, VehicleStatus(), at) }
    private val day = 86_400_000L

    @Test fun `기록이 없으면 오늘 목록이 비고 누적 점수는 없다`() {
        model.enter()
        val s = model.state.value
        assertEquals(LocalDate.of(2026, 11, 14), s.today); assertTrue(s.dayDrives.isEmpty())
        assertNull(s.cumulative.score); assertNull(s.dayAnalysis)
    }

    @Test fun `오늘 두 번 달리면 달력 칸 2, 목록 2, 이번 주 그래프에 급감속이 센다`() {
        demoDrive(now - 3_600_000); demoDrive(now - 1_800_000)
        model.enter()
        val s = model.state.value
        assertEquals(2, s.drivesPerDay[s.today]); assertEquals(2, s.dayDrives.size)
        assertNotNull(s.cumulative.score)
        val sat = s.chart.days.indexOf(s.today)
        assertEquals(2, s.chart.counts.getValue(StatKind.BRAKE)[sat])
        assertEquals(2, s.cumulative.weekCounts[StatKind.BRAKE])
    }

    @Test fun `유효 주행이 아닌 주행은 달력·목록·누적 점수에 넣지 않는다`() {
        demoDrive(now - 3_600_000)
        val short = DemoDriveReplay.run(AppConfig.engine, now - 1_800_000)!!.copy(valid = false, score = 0)
        drives.onSessionEnded(short, VehicleStatus(), now - 1_800_000)
        assertEquals("저장은 둘 다 한다", 2, graph.sessions.list(graph.profileId).size)
        model.enter()
        val s = model.state.value
        assertEquals(1, s.drivesPerDay[s.today]); assertEquals(1, s.dayDrives.size)
        assertTrue(s.dayDrives.single().score != 0)
        assertEquals(1, s.cumulative.weekCounts[StatKind.BRAKE])
    }

    @Test fun `다른 날을 고르면 그날 분석이 나오고 주행을 누르면 상세`() {
        demoDrive(now - 3 * day)
        model.enter()
        val past = LocalDate.of(2026, 11, 11)
        model.selectDate(past)
        val s = model.state.value
        assertNotNull(s.dayAnalysis); assertEquals(1, s.dayDrives.size)
        model.openDrive(s.dayDrives.single().id)
        val d = model.state.value.detail!!
        assertEquals(1, d.events.size); assertEquals(4, d.habits.size)
        model.closeDrive(); assertNull(model.state.value.detail)
    }

    @Test fun `지난주 대비 - 이번 주 평균에서 지난주 평균을 뺀다`() {
        demoDrive(now - 7 * day); demoDrive(now - 1_800_000)
        model.enter()
        assertEquals(0, model.state.value.cumulative.weekDelta)
    }

    @Test fun `야간 비율 - 22시부터 04시까지`() {
        val start = LocalDate.of(2026, 11, 14).atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0.5f, StatsModel.nightRatio(start, start + 2 * 3_600_000, zone), 0.01f)
    }
}

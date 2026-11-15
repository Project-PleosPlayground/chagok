package core.mission

import core.Fx
import core.Mx
import core.character.Profile
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType.HARSH_ACCEL
import engine.EventType.HARSH_BRAKE
import engine.EventType.LANE_CHANGE_NO_SIGNAL
import engine.EventType.LANE_CHANGE_SIGNALED
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** 07 "판정 시점": 주행 중에는 진행도만 바뀌고 판정은 주행 끝에 한다 (#62) */
class LiveProgressTest {
    private val h = 3_600_000L
    private val sessions = InMemorySessionStore(); private val store = InMemoryMissionStore()
    private val catalog = Mx.catalog()
    private val engine = MissionEngine(store, sessions, missions = { catalog }, random = Random(1))
    private val p = Profile.DEFAULT
    private val mon9 = Fx.MON + 9 * h
    private val driveStart = mon9 + h
    private val live = LiveDrive(p)

    private fun take(c: ConditionType, target: Float): MissionInstance {
        val row = catalog.row(c)!!
        val tier = row.tiers.first { row.target(it) == target }
        val m = engine.offer(p, MissionKind.PERSONAL, c, tier, mon9)!!
        return (engine.challenge(p, m.uid, mon9, driving = false) as MissionEngine.SlotResult.Ok).instance
    }
    private fun get(uid: String) = store.list(p).single { it.uid == uid }
    private fun progress() = live.record()?.let { engine.liveProgress(it) } ?: emptyMap()

    @Test fun `급제동 1회 이하 미션 - 2회째 급제동에 센 값이 2가 되고, 주행이 끝날 때까지 판정하지 않으며, 끝나면 실패`() {
        val m = take(ConditionType.HARSH_BRAKE_MAX, 1f)
        val brake1 = Fx.ev(HARSH_BRAKE, 10_000); val brake2 = Fx.ev(HARSH_BRAKE, 200_000)
        live.start(driveStart)
        live.event(brake1)
        assertTrue(progress().isEmpty(), "인정 전에는 진행도를 내지 않는다")
        live.validate(listOf(brake1))
        assertEquals(1f, progress()[m.uid], "인정 전 급제동도 센다")
        live.event(brake2)
        assertEquals(2f, progress()[m.uid])
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status, "목표를 넘어도 주행 중에는 판정하지 않는다")
        assertNull(get(m.uid).counted, "주행 중 진행도는 저장하지 않는다")

        val r = Fx.record("d1", driveStart, events = listOf(brake1, brake2))
        sessions.save(r)
        live.end()
        engine.onDriveEnd(r, VehicleStatus(), r.endWallMs)
        assertEquals(MissionStatus.FAILED, get(m.uid).status)
        assertEquals(2f, get(m.uid).counted, "주행 중 센 값과 주행 끝 판정에 쓴 값이 같다")
    }

    @Test fun `유효 주행으로 인정되기 전에 끝난 주행은 진행도를 바꾸지 않는다`() {
        val m = take(ConditionType.HARSH_BRAKE_MAX, 1f)
        val brake = Fx.ev(HARSH_BRAKE, 10_000)
        live.start(driveStart); live.event(brake); live.event(Fx.ev(HARSH_BRAKE, 20_000))
        assertTrue(progress().isEmpty())
        val r = Fx.record("short", driveStart, km = 0.3f, minutes = 2, valid = false, events = listOf(brake))
        sessions.save(r)
        live.end()
        engine.onDriveEnd(r, VehicleStatus(), r.endWallMs)
        assertTrue(progress().isEmpty())
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status)
        assertNull(get(m.uid).counted)
    }

    @Test fun `인정 시점 목록과 이벤트 흐름으로 같은 이벤트가 두 번 와도 한 번만 센다`() {
        val m = take(ConditionType.HARSH_BRAKE_MAX, 1f)
        val brake = Fx.ev(HARSH_BRAKE, 10_000)
        live.start(driveStart); live.event(brake); live.validate(listOf(brake)); live.event(brake)
        assertEquals(1f, progress()[m.uid])
    }

    @Test fun `이벤트로 세는 조건만 주행 중에 세고, 다른 미션의 이벤트는 섞지 않는다`() {
        val accel = take(ConditionType.HARSH_ACCEL_MAX, 1f)
        val rate = take(ConditionType.SIGNALED_RATE_MIN, catalog.row(ConditionType.SIGNALED_RATE_MIN)!!.let { it.target(it.tiers.first())!! })
        val km = take(ConditionType.CLEAN_KM, catalog.row(ConditionType.CLEAN_KM)!!.let { it.target(it.tiers.first())!! })
        live.start(driveStart)
        live.validate(listOf(Fx.ev(HARSH_BRAKE, 10_000), Fx.ev(LANE_CHANGE_SIGNALED, 20_000)))
        live.event(Fx.ev(LANE_CHANGE_NO_SIGNAL, 30_000))
        val now = progress()
        assertEquals(0f, now[accel.uid])
        assertEquals(50f, now[rate.uid])
        assertTrue(km.uid !in now, "거리로 세는 조건은 주행이 끝나야 센다")
    }

    @Test fun `도전하기 전에 시작한 주행은 세지 않는다`() {
        live.start(mon9 - h); live.validate(listOf(Fx.ev(HARSH_BRAKE, 10_000)))
        val m = take(ConditionType.HARSH_BRAKE_MAX, 1f)
        assertTrue(m.uid !in progress())
    }

    @Test fun `주행이 끝나면 지금 주행 기록이 없다`() {
        live.start(driveStart); live.validate(emptyList())
        assertTrue(live.record() != null)
        live.end()
        assertNull(live.record())
    }

    @Test fun `끝난 주행의 처리가 다음 주행 시작보다 늦게 와도 다음 주행의 진행도는 지우지 않는다`() {
        val m = take(ConditionType.HARSH_BRAKE_MAX, 1f)
        live.start(driveStart); live.validate(emptyList())
        val next = driveStart + h
        live.start(next)                    // 다음 주행이 먼저 시작됨
        live.end(driveStart)                // 지난 주행의 끝 처리가 늦게 옴
        live.validate(listOf(Fx.ev(HARSH_BRAKE, 10_000)))
        assertEquals(1f, progress()[m.uid])
        live.end(next)
        assertNull(live.record())
    }
}

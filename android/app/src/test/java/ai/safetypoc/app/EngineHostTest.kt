package ai.safetypoc.app

import ai.safetypoc.app.drive.DriveRecorder
import ai.safetypoc.app.voice.VoicePlayer
import core.session.VehicleStatus
import core.mission.LiveDrive
import engine.EventType
import engine.FeedbackCue
import engine.SessionLifecycle
import engine.SessionResult
import engine.SignalSource
import engine.VehicleSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineHostTest {
    private class FakeSource : SignalSource {
        var sink: ((VehicleSignal) -> Unit)? = null
        override fun start(sink: (VehicleSignal) -> Unit) { this.sink = sink }
        override fun stop() { sink = null }
    }

    /** 불린 순서를 남긴다 */
    private class RecordingVoice : VoicePlayer {
        val calls = ArrayList<String>()
        override fun start() { calls += "start" }
        override fun play(cue: FeedbackCue) = Unit
        override fun release() { calls += "release" }
    }

    private val voice = RecordingVoice()
    private val scheduler = TestCoroutineScheduler()
    private val source = FakeSource()
    private val graph = AppGraph()
    private val recorder = DriveRecorder(graph)
    private val config = AppConfig.engine
    private val t0 = 1_793_000_000_000L
    private var now = t0
    private val ended = ArrayList<SessionResult>()
    /** 결과를 처리하는 동안 본 driveStartMs */
    private val driveStartWhileHandling = ArrayList<Long?>()
    private lateinit var host: EngineHost
    /** 넘겨받은 세션 단계(이름) */
    private val lifecycle = ArrayList<String>()
    /** 주행 중 진행도용으로 모은 지금 주행 */
    private val live = LiveDrive("p")

    private fun newHost() = EngineHost(
        listOf(source), voice, config,
        onSessionEnded = { r ->
            driveStartWhileHandling += host.driveStartMs.value
            ended += r
            recorder.onSessionEnded(r, VehicleStatus(), now)
        },
        engineDispatcher = StandardTestDispatcher(scheduler),
        resultContext = StandardTestDispatcher(scheduler),
        onLifecycle = {
            lifecycle += it::class.simpleName!!
            when (it) {
                is SessionLifecycle.Started -> live.start(it.startMs)
                is SessionLifecycle.Validated -> live.validate(it.eventsSoFar)
                is SessionLifecycle.Ended -> Unit
            }
        },
        onEvent = { live.event(it) },
        clock = { now },
    ).also { host = it }

    /** [mps] 로 [ms] 동안 100ms 마다 속도 신호를 보낸다 */
    private fun drive(mps: Float, ms: Long) {
        val end = now + ms
        while (now < end) {
            now += SIGNAL_INTERVAL_MS
            source.sink!!(VehicleSignal.Speed(now, mps))
        }
        scheduler.runCurrent()
    }

    @Test fun `수동 종료한 주행도 저장·보상 처리로 한 번 가고 주행 중 표시가 풀린다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        assertNotNull("주행이 시작됐다", host.driveStartMs.value)

        host.stop(); scheduler.runCurrent()

        assertEquals(1, ended.size)
        assertTrue(ended.single().valid)
        assertEquals(1, graph.sessions.list(graph.profileId).size)
        val gained = recorder.last.value!!.xpGained
        assertTrue(gained > 0)
        val pid = graph.profileId
        assertEquals("보상이 한 번만 들어갔다", gained, graph.profiles.progress(pid, graph.profiles.state(pid).selected!!)!!.xp)
        assertNull(host.driveStartMs.value)
        assertEquals(ended.single(), host.state.value.lastResult)
        assertFalse(host.state.value.running)
    }

    @Test fun `정차로 자동 종료된 주행은 한 번만 처리하고, 그 뒤 수동 종료해도 다시 넘기지 않는다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        drive(0f, config.sessionEndIdleMs + IDLE_MARGIN_MS)
        assertEquals(1, ended.size)
        assertNull(host.driveStartMs.value)

        host.stop(); scheduler.runCurrent()
        assertEquals(1, ended.size)
        assertEquals(1, graph.sessions.list(graph.profileId).size)
    }

    @Test fun `끝난 주행을 처리하는 동안에는 아직 주행 중으로 남는다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        val start = host.driveStartMs.value
        drive(0f, config.sessionEndIdleMs + IDLE_MARGIN_MS)
        assertEquals(listOf(start), driveStartWhileHandling)
        assertNull(host.driveStartMs.value)
    }

    @Test fun `정차 여부는 마지막 속도 신호로 정하고 신호 전에는 정차로 본다`() {
        newHost().start(); scheduler.runCurrent()
        assertTrue(host.state.value.stopped)
        drive(DRIVE_MPS, SHORT_MS)
        assertFalse(host.state.value.stopped)
        drive(config.sessionIdleSpeedMps, SHORT_MS)
        assertTrue("정차 기준 속도와 같으면 정차", host.state.value.stopped)
        host.stop(); scheduler.runCurrent()
        assertTrue(host.state.value.stopped)
    }

    @Test fun `종료 전에 들어온 신호는 엔진에 넣되, 종료 뒤 처리돼도 화면 상태는 정차·멈춤으로 남는다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        val sink = source.sink!!
        now += SIGNAL_INTERVAL_MS; sink(VehicleSignal.Speed(now, DRIVE_MPS))
        host.stop(); scheduler.runCurrent()
        assertTrue(host.state.value.stopped)
        assertFalse(host.state.value.running)
        assertEquals(now, ended.single().endMs)
    }

    @Test fun `종료 뒤 바로 다시 시작해도 새 주행의 음성을 놓지 않는다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, SHORT_MS)
        host.stop()
        host.start()
        scheduler.runCurrent()
        assertEquals(listOf("start", "release", "start"), voice.calls)
    }

    @Test fun `세션만 닫으면 결과를 한 번 넘기고 신호 입력은 계속 받아 다음 출발에 새 세션이 열린다`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        drive(0f, SHORT_MS)

        host.endSession(); scheduler.runCurrent()
        assertEquals(1, ended.size)
        assertNull(host.driveStartMs.value)
        assertTrue(host.state.value.running)
        assertNotNull("신호 입력이 붙어 있다", source.sink)

        drive(DRIVE_MPS, VALID_DRIVE_MS)
        assertNotNull("다시 출발하면 새 세션", host.driveStartMs.value)
        host.stop(); scheduler.runCurrent()
        assertEquals(2, ended.size)
    }

    @Test fun `세션 시작·인정·끝을 엔진이 낸 순서대로 넘긴다(주행 중 미션 진행도용)`() {
        newHost().start(); scheduler.runCurrent()
        drive(DRIVE_MPS, VALID_DRIVE_MS)
        assertEquals(listOf("Started", "Validated"), lifecycle)
        drive(0f, config.sessionEndIdleMs + IDLE_MARGIN_MS)
        assertEquals(listOf("Started", "Validated", "Ended"), lifecycle)
    }

    @Test fun `엔진 실제 경로로 주행 중에 모은 급제동 수가 주행 끝 결과와 같다`() {
        newHost().start(); scheduler.runCurrent()
        drive(CRUISE_MPS, VALID_DRIVE_MS)
        // 1초 동안 60 → 20 km/h (급제동)
        source.sink!!(VehicleSignal.BrakePedal(now, true))
        for (i in 1..BRAKE_STEPS) {
            now += SIGNAL_INTERVAL_MS
            source.sink!!(VehicleSignal.Speed(now, CRUISE_MPS - (CRUISE_MPS - BRAKE_END_MPS) * i / BRAKE_STEPS))
        }
        source.sink!!(VehicleSignal.BrakePedal(now, false))
        scheduler.runCurrent()
        drive(BRAKE_END_MPS, SETTLE_MS)
        val liveBrakes = live.record()!!.count(EventType.HARSH_BRAKE)
        drive(0f, config.sessionEndIdleMs + IDLE_MARGIN_MS)
        assertTrue("급제동이 잡혀야 비교할 수 있다", liveBrakes >= 1)
        assertEquals(ended.single().events.count { it.type == EventType.HARSH_BRAKE }, liveBrakes)
    }

    private companion object {
        const val SIGNAL_INTERVAL_MS = 100L
        /** 36 km/h */
        const val DRIVE_MPS = 10f
        /** 유효 주행(1km·3분)을 넘기는 길이: 시작 대기 5초 + 3분 20초 */
        const val VALID_DRIVE_MS = 205_000L
        const val IDLE_MARGIN_MS = 2_000L
        const val SHORT_MS = 1_000L
        /** 60 km/h */
        const val CRUISE_MPS = 16.7f
        /** 20 km/h */
        const val BRAKE_END_MPS = 5.6f
        /** 급제동 1초를 100ms 신호 10개로 */
        const val BRAKE_STEPS = 10
        /** 급제동이 끝나고 확정될 때까지 */
        const val SETTLE_MS = 5_000L
    }
}

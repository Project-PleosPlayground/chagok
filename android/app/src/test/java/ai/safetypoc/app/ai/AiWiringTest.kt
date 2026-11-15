package ai.safetypoc.app.ai

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.drive.DriveRecorder
import core.ai.AiClient
import core.mission.MissionKind
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWiringTest {
    private val start = 1_793_000_000_000L
    private val minute = 60_000L
    private fun result(vararg events: EventType) =
        SessionResult(start, start + 15 * minute, 8f, events.mapIndexed { i, t -> DrivingEvent(t, 1, start + i * 1000L, start + i * 1000L + 500, 15f) }, 90, 100, 90, 100, true)

    @Test fun `캐릭터 정보 - 별칭이 없으면 차종 이름, 있으면 별칭`() {
        val g = AppGraph()
        assertEquals("그랜저", AiCharacters.of(g.profiles, g.profileId, g.content.active).name)
        val sel = g.profiles.state(g.profileId).selected!!
        g.profiles.saveProgress(g.profiles.progress(g.profileId, sel).copy(alias = "붕붕", stage = 2))
        val c = AiCharacters.of(g.profiles, g.profileId, g.content.active)
        assertEquals("붕붕", c.name); assertEquals(2, c.stage); assertEquals(AiCharacters.PERSONA, c.persona)
    }

    @Test fun `주행 끝 - AI 가 답하면 요약의 코멘트만 바뀌어 리코더에 반영된다`() = runTest {
        var sent: String? = null
        val g = AppGraph(aiClient = AiClient { json -> sent = json; """{"report":"출근길에 한 번 놀랐네. 그래도 잘 왔어.","missions":[]}""" })
        val recorder = DriveRecorder(g)
        val summary = recorder.onSessionEnded(result(EventType.HARSH_BRAKE), VehicleStatus(), start + 16 * minute)
        val withAi = g.ai.run(summary, AiCharacters.of(g.profiles, g.profileId, g.content.active), VehicleStatus(), start + 16 * minute)
        assertEquals("출근길에 한 번 놀랐네. 그래도 잘 왔어.", withAi.comment)
        assertEquals(summary.coinsGained, withAi.coinsGained); assertEquals(summary.record, withAi.record)
        assertTrue(sent!!.contains("\"character\":{\"name\":\"그랜저\""))
        recorder.update(withAi)
        assertEquals(withAi, recorder.last.value)
    }

    @Test fun `서버 주소가 없으면 AI 없이 규칙 문장, 설정이 있으면 HTTP 클라이언트`() = runTest {
        assertNull(HttpAiClient.fromConfig("", "tok"))
        assertNull(HttpAiClient.fromConfig("   ", ""))
        val g = AppGraph(aiClient = null)
        val recorder = DriveRecorder(g)
        val summary = recorder.onSessionEnded(result(EventType.HARSH_BRAKE), VehicleStatus(), start + 16 * minute)
        assertEquals(summary, g.ai.run(summary, AiCharacters.of(g.profiles, g.profileId, g.content.active), VehicleStatus(), start + 16 * minute))
        // 잘못된 주소는 호출 실패 → null → 규칙 문장 그대로
        val bad = HttpAiClient("http://127.0.0.1:9/api/drive-end", "", timeoutMs = 500)
        assertNull(bad.driveEnd("{}"))
    }

    @Test fun `습관 개인 미션 - 7일 주행이 쌓이면 refresh 앞에서 하루 개인 미션을 정한다`() {
        val g = AppGraph()
        val sessions = g.sessions
        for (d in 0..3) sessions.save(core.session.SessionRecord("w$d", g.profileId, result(EventType.HARSH_BRAKE, EventType.HARSH_BRAKE, EventType.HARSH_BRAKE).copy(startMs = start - (4 - d) * 24 * 60 * minute, endMs = start - (4 - d) * 24 * 60 * minute + 15 * minute), emptyList(), start - (4 - d) * 24 * 60 * minute, start - (4 - d) * 24 * 60 * minute + 15 * minute))
        val plan = g.planner.offerToday(g.profileId, start)
        assertEquals(core.content.BundledContent.pack.missions.rules.personalPerDay, plan.all.size); assertEquals(core.mission.ConditionType.HARSH_BRAKE_MAX, plan.improve!!.condition)
        g.missionActions.refresh(g.profileId, start, null)
        assertEquals(core.content.BundledContent.pack.missions.rules.personalPerDay, g.missions.offers(g.profileId, start).count { it.kind == MissionKind.PERSONAL })
    }
}

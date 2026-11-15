package ai.safetypoc.app.gleo

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.ui.home.HomeModel
import ai.safetypoc.app.ui.mission.MissionTabModel
import core.mission.ConditionType
import core.mission.MissionKind
import core.session.SessionRecord
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoAnswersTest {
    private val graph = AppGraph()
    private val now = 1_793_000_000_000L
    private var driving = false
    private val home = HomeModel({ graph.profileId }, graph.profiles, graph.growth, content = { graph.content.active })
    private val missionTab = MissionTabModel(
        { graph.profileId }, graph.missions, graph.missionActions, slotCount = { 3 }, driving = { driving }, now = { now },
    )
    private val gleo = GleoAnswers(
        driving = { driving },
        pet = home::current,
        maxStage = graph.growth.maxStage,
        slot = missionTab::slotNow,
        offered = missionTab::offeredNow,
        lastDrive = { graph.sessions.list(graph.profileId).lastOrNull { it.valid } },
    )

    private fun drive(id: String, score: Int, valid: Boolean, vararg events: EventType) {
        val start = now - 3_600_000L
        val end = start + 1_200_000L
        val result = SessionResult(
            start, end, 12.34f, events.mapIndexed { i, t -> DrivingEvent(t, 1, start + i * 1000L, start + i * 1000L + 500, 15f) },
            score, 100, 100, 100, valid,
        )
        graph.sessions.save(SessionRecord(id, graph.profileId, result, emptyList(), start, end))
    }

    private fun dataOf(reply: GleoReply): List<JSONObject> {
        val arr = JSONArray(JSONObject(reply.toJson()).getString(GleoReply.KEY_DATA))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    @Test fun `점수 - 마지막 유효 주행의 점수와 이벤트 횟수를 답한다`() {
        drive("a", 70, true, EventType.HARSH_BRAKE)
        drive("b", 85, true, EventType.HARSH_BRAKE, EventType.HARSH_BRAKE, EventType.HARSH_ACCEL, EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_SIGNALED)
        drive("c", 0, false)
        val r = gleo.answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, r.status)
        assertTrue(r.message, r.message.contains("85"))
        val d = dataOf(r).single()
        assertEquals(85, d.getInt("score"))
        assertEquals(12.3, d.getDouble("distanceKm"), 1e-9)
        assertEquals(2, d.getInt("harshBraking"))
        assertEquals(1, d.getInt("harshAcceleration"))
        assertEquals(1, d.getInt("laneChangeWithoutSignal"))
        assertEquals(0, d.getInt("laneChangeWrongSignal"))
    }

    @Test fun `점수 - 주행 중이면 알려 주지 않는다`() {
        drive("a", 85, true)
        driving = true
        val r = gleo.answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, r.status)
        assertTrue(dataOf(r).isEmpty())
    }

    @Test fun `점수 - 유효 주행이 없으면 없다고 답한다`() {
        drive("c", 0, false)
        val r = gleo.answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, r.status)
        assertTrue(dataOf(r).isEmpty())
    }

    @Test fun `캐릭터 - 이름, 단계, 다음 단계까지 남은 경험치, 포인트를 답한다`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(alias = "뿡뿡이", xp = graph.growth.minXp(2) + 10, stage = 2))
        graph.profiles.saveState(graph.profiles.state(graph.profileId).copy(coins = 340))
        val r = gleo.answer(GleoFunction.GET_PET_STATUS)
        assertEquals(GleoStatus.SUCCESS, r.status)
        val d = dataOf(r).single()
        assertEquals("뿡뿡이", d.getString("petName"))
        assertEquals(2, d.getInt("stage"))
        assertEquals(graph.growth.maxStage, d.getInt("maxStage"))
        assertEquals(graph.growth.minXp(3) - graph.growth.minXp(2) - 10, d.getInt("experienceToNextStage"))
        assertEquals(340, d.getInt("points"))
    }

    @Test fun `캐릭터 - 마지막 단계면 남은 경험치가 0이다`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        val max = graph.growth.maxStage
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(xp = graph.growth.minXp(max), stage = max))
        val r = gleo.answer(GleoFunction.GET_PET_STATUS)
        assertEquals(0, dataOf(r).single().getInt("experienceToNextStage"))
        assertTrue(r.message, r.message.contains("final stage"))
    }

    @Test fun `미션 - 도전 중인 미션의 제목, 상태, 진행도를 답한다`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        missionTab.challenge(m.uid)
        val r = gleo.answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(GleoStatus.SUCCESS, r.status)
        val d = dataOf(r).single()
        assertEquals(m.title, d.getString("title"))
        assertEquals("in_progress", d.getString("state"))
        assertEquals("0/3", d.getString("progress"))
        assertEquals(m.rewardCoins, d.getInt("rewardPoints"))
    }

    @Test fun `미션 - 도전 중인 미션이 없으면 도전할 수 있는 미션 수를 답한다`() {
        graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, now)!!
        val r = gleo.answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(2, dataOf(r).single().getInt("availableMissions"))
        assertTrue(r.message, r.message.contains("2 missions"))
    }

    @Test fun `조회는 화면 상태를 바꾸지 않는다`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        missionTab.refresh()
        home.refresh()
        missionTab.tapCard(m.uid)
        val before = missionTab.state.value
        assertTrue(before.dialog != null)
        val homeBefore = home.state.value
        GleoFunction.entries.forEach { gleo.answer(it) }
        assertEquals(before, missionTab.state.value)
        assertEquals(homeBefore, home.state.value)
    }

    @Test fun `응답 JSON 에 콜백 필수 항목이 있다`() {
        val json = JSONObject(gleo.answer(GleoFunction.GET_PET_STATUS).toJson())
        assertEquals("GET_PET_STATUS", json.getString(GleoReply.KEY_FUNCTION))
        assertEquals("success", json.getString(GleoReply.KEY_STATUS))
        assertEquals("voice", json.getString(GleoReply.KEY_MODE))
        assertTrue(json.getString(GleoReply.KEY_MESSAGE).isNotBlank())
    }
}

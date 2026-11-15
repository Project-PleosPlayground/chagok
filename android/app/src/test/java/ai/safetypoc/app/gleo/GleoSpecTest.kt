package ai.safetypoc.app.gleo

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.ui.home.HomeModel
import ai.safetypoc.app.ui.home.HomeState
import ai.safetypoc.app.ui.mission.MissionCard
import ai.safetypoc.app.ui.mission.MissionTabModel
import core.mission.ConditionType
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.EventRecord
import core.session.SessionRecord
import engine.DrivingEvent
import engine.EventType
import engine.SessionResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** 스펙(#3 Gleo 음성 질의 응답)만 보고 쓴 블랙박스 테스트 */
class GleoSpecTest {
    private val graph = AppGraph()
    private val now = 1_793_000_000_000L
    private var driving = false
    private var live = emptyMap<String, Float>()
    private val home = HomeModel({ graph.profileId }, graph.profiles, graph.growth) { graph.content.active }
    private val missionTab = MissionTabModel(
        { graph.profileId }, graph.missions, graph.missionActions, slotCount = { 3 }, driving = { driving }, now = { now }, live = { live },
    )

    /** 앱이 넘기는 것과 같은 연결 */
    private fun wired() = GleoAnswers(
        driving = { driving },
        pet = home::current,
        maxStage = graph.growth.maxStage,
        slot = missionTab::slotNow,
        offered = missionTab::offeredNow,
        lastDrive = { graph.sessions.list(graph.profileId).lastOrNull { it.valid } },
    )

    private fun answers(
        driving: Boolean = false,
        pet: HomeState = HomeState(),
        slot: List<MissionCard> = emptyList(),
        offered: Int = 0,
        lastDrive: SessionRecord? = null,
    ) = GleoAnswers(
        driving = { driving }, pet = { pet }, maxStage = MAX_STAGE, slot = { slot }, offered = { offered }, lastDrive = { lastDrive },
    )

    private fun record(
        id: String,
        startWallMs: Long,
        valid: Boolean = true,
        score: Int = 87,
        distanceKm: Float = 12.34f,
        events: List<EventType> = emptyList(),
        endWallMs: Long = startWallMs + 20 * 60_000L,
    ): SessionRecord {
        val ev = events.mapIndexed { i, t -> DrivingEvent(t, 1, i * 1000L, i * 1000L + 500, 10f) }
        return SessionRecord(
            id = id, profileId = graph.profileId,
            result = SessionResult(0, 1, distanceKm, ev, if (valid) score else 0, 100, 100, 100, valid),
            events = ev.map { EventRecord(it) }, startWallMs = startWallMs, endWallMs = endWallMs,
        )
    }

    private fun card(uid: String, title: String, status: MissionStatus, progress: String, coins: Int = 30, xp: Int = 20) = MissionCard(
        uid = uid, title = title, description = "", kind = MissionKind.PERSONAL, status = status, tier = 1, progress = progress,
        rewardCoins = coins, rewardXp = xp, remainingMs = 1000L, canChallenge = false, canCancel = false, canClear = false,
    )

    private fun json(r: GleoReply) = JSONObject(r.toJson())
    private fun dataOf(r: GleoReply): JSONArray {
        val raw = json(r).get("data")
        assertTrue("data 는 JSON 배열을 담은 문자열이어야 한다: ${raw.javaClass}", raw is String)
        return JSONArray(raw as String)
    }
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()

    // ---- 공통 콜백 형식 ----

    @Test fun `세 함수 모두 콜백 JSON 다섯 키, 함수 이름, 음성 모드, 비지 않은 메시지`() {
        val a = answers()
        GleoFunction.values().forEach { f ->
            val o = json(a.answer(f))
            assertEquals(setOf("functionName", "status", "message", "data", "mode"), o.keySet())
            assertEquals(f.name, o.getString("functionName"))
            assertEquals("voice", o.getString("mode"))
            assertTrue(o.getString("status") in setOf("success", "asking", "fail"))
            assertTrue("$f message", o.getString("message").isNotBlank())
            assertTrue(o.get("data") is String)
        }
    }

    @Test fun `of 는 함수 이름이면 그 함수, 모르는 액션·빈 값·null 이면 null`() {
        assertEquals(GleoFunction.GET_DRIVE_SCORE, GleoFunction.of("GET_DRIVE_SCORE"))
        assertEquals(GleoFunction.GET_PET_STATUS, GleoFunction.of("GET_PET_STATUS"))
        assertEquals(GleoFunction.GET_MISSION_PROGRESS, GleoFunction.of("GET_MISSION_PROGRESS"))
        assertNull(GleoFunction.of("GET_UNKNOWN"))
        assertNull(GleoFunction.of(""))
        assertNull(GleoFunction.of(null))
    }

    // ---- GET_DRIVE_SCORE ----

    @Test fun `주행 점수 - 마지막 유효 주행의 점수·거리·네 가지 횟수, 깜빡이 반대와 미점등을 나눈다`() {
        val r = record(
            "s1", startWallMs = now - 3_600_000, score = 87, distanceKm = 12.34f,
            events = listOf(
                EventType.HARSH_BRAKE, EventType.HARSH_BRAKE, EventType.HARSH_ACCEL,
                EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_NO_SIGNAL,
                EventType.LANE_CHANGE_WRONG_SIGNAL, EventType.LANE_CHANGE_SIGNALED, EventType.LANE_CHANGE_SIGNALED,
            ),
        )
        val reply = answers(lastDrive = r).answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val arr = dataOf(reply)
        assertEquals(1, arr.length())
        val d = arr.getJSONObject(0)
        assertEquals(
            setOf("score", "maxScore", "distanceKm", "harshBraking", "harshAcceleration", "laneChangeWithoutSignal", "laneChangeWrongSignal", "endedAt"),
            d.keySet(),
        )
        assertEquals(87, d.getInt("score"))
        assertEquals(100, d.getInt("maxScore"))
        assertEquals(12.3, d.getDouble("distanceKm"), 1e-9)
        assertEquals(2, d.getInt("harshBraking"))
        assertEquals(1, d.getInt("harshAcceleration"))
        assertEquals(3, d.getInt("laneChangeWithoutSignal"))
        assertEquals(1, d.getInt("laneChangeWrongSignal"))
    }

    @Test fun `주행 점수 - 거리는 소수 한 자리로 반올림하고 float 꼬리가 붙지 않는다`() {
        fun km(v: Float) = dataOf(answers(lastDrive = record("s", now, distanceKm = v)).answer(GleoFunction.GET_DRIVE_SCORE)).getJSONObject(0).get("distanceKm").toString()
        assertEquals("12.3", km(12.34f))
        assertEquals(4.0, km(3.96f).toDouble(), 1e-9)
        assertEquals("1.2", km(1.2f))
    }

    @Test fun `주행 점수 - 감점 없는 주행은 네 횟수가 모두 0`() {
        val d = dataOf(answers(lastDrive = record("s", now, score = 100)).answer(GleoFunction.GET_DRIVE_SCORE)).getJSONObject(0)
        assertEquals(100, d.getInt("score"))
        listOf("harshBraking", "harshAcceleration", "laneChangeWithoutSignal", "laneChangeWrongSignal").forEach { assertEquals(it, 0, d.getInt(it)) }
    }

    @Test fun `주행 점수 - 끝난 시각은 KST 분 단위 문자열`() {
        // UTC 2026-11-15 00:30:45 = KST 09:30:45
        val end = LocalDateTime.of(2026, 11, 15, 0, 30, 45).toInstant(ZoneOffset.UTC).toEpochMilli()
        val r = record("s", startWallMs = end - 20 * 60_000L, endWallMs = end)
        val endedAt = dataOf(answers(lastDrive = r).answer(GleoFunction.GET_DRIVE_SCORE)).getJSONObject(0).getString("endedAt")
        assertTrue(endedAt, Regex("2026-11-15[T ]09:30").containsMatchIn(endedAt))
        assertFalse("초는 넣지 않는다: $endedAt", endedAt.contains("09:30:45"))
        assertFalse("UTC 가 아니다: $endedAt", endedAt.contains("00:30"))
    }

    @Test fun `주행 점수 - 주행 중이면 success 로 "끝난 뒤 알려 준다", 점수를 말하지 않는다`() {
        val reply = answers(driving = true, lastDrive = record("s", now, score = 87)).answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals("success", json(reply).getString("status"))
        assertTrue(reply.message.isNotBlank())
        assertFalse("메시지에 점수가 없어야 한다: ${reply.message}", reply.message.contains("87"))
        val arr = dataOf(reply)
        for (i in 0 until arr.length()) assertFalse(arr.getJSONObject(i).has("score"))
    }

    @Test fun `주행 점수 - 주행 중이고 유효 주행이 없어도 같은 답`() {
        val reply = answers(driving = true, lastDrive = null).answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, reply.status)
    }

    @Test fun `주행 점수 - 유효 주행이 없으면 success 와 빈 data`() {
        val reply = answers(lastDrive = null).answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertTrue(reply.data.isEmpty())
        assertEquals(0, dataOf(reply).length())
        assertTrue(reply.message.isNotBlank())
    }

    @Test fun `주행 점수 - 기록이 무효 주행뿐이면 success 와 빈 data`() {
        graph.sessions.save(record("bad1", now - 7_200_000, valid = false))
        graph.sessions.save(record("bad2", now - 3_600_000, valid = false))
        val reply = wired().answer(GleoFunction.GET_DRIVE_SCORE)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals(0, dataOf(reply).length())
    }

    @Test fun `주행 점수 - 마지막 주행이 무효면 그 전 유효 주행을 말한다`() {
        graph.sessions.save(record("old", now - 7_200_000, score = 73, events = listOf(EventType.HARSH_ACCEL)))
        graph.sessions.save(record("new", now - 3_600_000, valid = false, events = listOf(EventType.HARSH_BRAKE)))
        val d = dataOf(wired().answer(GleoFunction.GET_DRIVE_SCORE)).getJSONObject(0)
        assertEquals(73, d.getInt("score"))
        assertEquals(1, d.getInt("harshAcceleration"))
        assertEquals(0, d.getInt("harshBraking"))
    }

    @Test fun `주행 점수 - 유효 주행이 여럿이면 가장 최근 것`() {
        graph.sessions.save(record("a", now - 7_200_000, score = 60))
        graph.sessions.save(record("b", now - 3_600_000, score = 95))
        assertEquals(95, dataOf(wired().answer(GleoFunction.GET_DRIVE_SCORE)).getJSONObject(0).getInt("score"))
    }

    // ---- GET_PET_STATUS ----

    @Test fun `펫 - 별명이 있으면 별명, 단계·최고 단계·다음 단계까지 경험치·포인트`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(alias = "콩이", xp = 150, stage = 2))
        graph.profiles.saveState(graph.profiles.state(graph.profileId).copy(coins = 420))
        val reply = wired().answer(GleoFunction.GET_PET_STATUS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val arr = dataOf(reply)
        assertEquals(1, arr.length())
        val d = arr.getJSONObject(0)
        assertEquals(setOf("petName", "stage", "maxStage", "experienceToNextStage", "points"), d.keySet())
        assertEquals("콩이", d.getString("petName"))
        assertEquals(2, d.getInt("stage"))
        assertEquals(graph.growth.maxStage, d.getInt("maxStage"))
        assertEquals(graph.growth.minXp(3) - 150, d.getInt("experienceToNextStage"))
        assertEquals(420, d.getInt("points"))
    }

    @Test fun `펫 - 별명이 비었거나 공백뿐이면 캐릭터 이름`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        val name = graph.content.active.characters.first { it.id == id }.name
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(alias = ""))
        assertEquals(name, dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0).getString("petName"))
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(alias = "   "))
        assertEquals(name, dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0).getString("petName"))
    }

    @Test fun `펫 - 경험치가 단계 경계에 딱 맞으면 다음 단계까지 그 단계 폭 전부`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        val xp = graph.growth.minXp(2)
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(xp = xp, stage = 2))
        val d = dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0)
        assertEquals(2, d.getInt("stage"))
        assertEquals(graph.growth.minXp(3) - graph.growth.minXp(2), d.getInt("experienceToNextStage"))
    }

    @Test fun `펫 - 다음 단계 바로 앞이면 1`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(xp = graph.growth.minXp(2) - 1, stage = 1))
        assertEquals(1, dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0).getInt("experienceToNextStage"))
    }

    @Test fun `펫 - 최고 단계면 다음 단계까지 0, 경험치가 넘쳐도 0`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        val max = graph.growth.maxStage
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(xp = graph.growth.minXp(max), stage = max))
        var d = dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0)
        assertEquals(max, d.getInt("stage"))
        assertEquals(max, d.getInt("maxStage"))
        assertEquals(0, d.getInt("experienceToNextStage"))
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(xp = graph.growth.minXp(max) + 777, stage = max))
        d = dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0)
        assertEquals(0, d.getInt("experienceToNextStage"))
    }

    @Test fun `펫 - HomeState 를 그대로 넘겨도 최고 단계면 0`() {
        val pet = HomeState(characterName = "콩이", stage = MAX_STAGE, xp = 2000, xpInStage = 1000, xpSpan = 0, coins = 5)
        val d = dataOf(answers(pet = pet).answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0)
        assertEquals(0, d.getInt("experienceToNextStage"))
        assertEquals(MAX_STAGE, d.getInt("maxStage"))
        assertEquals(5, d.getInt("points"))
    }

    @Test fun `펫 - 캐릭터를 고르지 않았으면 success 와 빈 data`() {
        graph.profiles.saveState(graph.profiles.state(graph.profileId).copy(selected = null))
        val reply = wired().answer(GleoFunction.GET_PET_STATUS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        assertEquals(0, dataOf(reply).length())
        assertTrue(reply.message.isNotBlank())
        assertEquals(0, dataOf(answers(pet = HomeState()).answer(GleoFunction.GET_PET_STATUS)).length())
    }

    @Test fun `펫 - 주행 중에도 success`() {
        driving = true
        assertEquals(GleoStatus.SUCCESS, wired().answer(GleoFunction.GET_PET_STATUS).status)
    }

    // ---- GET_MISSION_PROGRESS ----

    @Test fun `미션 - 슬롯 미션 하나, 미션 탭 카드와 같은 진행도와 보상`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        missionTab.challenge(m.uid)
        val tabCard = missionTab.state.value.slot.single()
        val reply = wired().answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val arr = dataOf(reply)
        assertEquals(1, arr.length())
        val d = arr.getJSONObject(0)
        assertEquals(setOf("title", "state", "progress", "rewardPoints", "rewardExperience"), d.keySet())
        assertEquals(tabCard.title, d.getString("title"))
        assertEquals("in_progress", d.getString("state"))
        assertEquals(tabCard.progress, d.getString("progress"))
        assertEquals("0/3", d.getString("progress"))
        assertEquals(tabCard.rewardCoins, d.getInt("rewardPoints"))
        assertEquals(tabCard.rewardXp, d.getInt("rewardExperience"))
    }

    @Test fun `미션 - 여러 개면 슬롯 순서대로 모두`() {
        val a = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        val b = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, now)!!
        missionTab.challenge(b.uid)
        missionTab.challenge(a.uid)
        val expected = missionTab.slotNow()
        val arr = dataOf(wired().answer(GleoFunction.GET_MISSION_PROGRESS))
        assertEquals(2, arr.length())
        expected.forEachIndexed { i, c ->
            assertEquals(c.title, arr.getJSONObject(i).getString("title"))
            assertEquals(c.progress, arr.getJSONObject(i).getString("progress"))
        }
    }

    @Test fun `미션 - 판정 끝난 미션이 슬롯에 남아 있으면 그 상태로 말한다`() {
        val slot = listOf(
            card("1", "급제동 3회 이하", MissionStatus.SUCCEEDED, "1/3", coins = 50, xp = 40),
            card("2", "안전벨트", MissionStatus.FAILED, "0/1"),
            card("3", "깜빡이 90%", MissionStatus.IN_PROGRESS, "-/90"),
        )
        val reply = answers(slot = slot, offered = 4).answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val arr = dataOf(reply)
        assertEquals(3, arr.length())
        assertEquals(listOf("succeeded", "failed", "in_progress"), (0 until 3).map { arr.getJSONObject(it).getString("state") })
        assertEquals(listOf("1/3", "0/1", "-/90"), (0 until 3).map { arr.getJSONObject(it).getString("progress") })
        assertEquals(50, arr.getJSONObject(0).getInt("rewardPoints"))
        assertEquals(40, arr.getJSONObject(0).getInt("rewardExperience"))
        for (i in 0 until 3) assertFalse(arr.getJSONObject(i).has("availableMissions"))
    }

    @Test fun `미션 - 주행 중이면 지금 주행까지 센 값, 상태는 그대로, success`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        missionTab.challenge(m.uid)
        driving = true
        live = mapOf(m.uid to 5f)
        val reply = wired().answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val d = dataOf(reply).getJSONObject(0)
        assertEquals("5/3", d.getString("progress"))
        assertEquals("in_progress", d.getString("state"))
    }

    @Test fun `미션 - 슬롯이 비면 도전할 수 있는 제공 미션 수 하나`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        val n = missionTab.offeredNow()
        assertTrue(n > 0)
        val reply = wired().answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(GleoStatus.SUCCESS, reply.status)
        val arr = dataOf(reply)
        assertEquals(1, arr.length())
        assertEquals(setOf("availableMissions"), arr.getJSONObject(0).keySet())
        assertEquals(n, arr.getJSONObject(0).getInt("availableMissions"))
    }

    @Test fun `미션 - 슬롯이 비고 제공 미션도 없으면 availableMissions 0`() {
        val arr = dataOf(answers(slot = emptyList(), offered = 0).answer(GleoFunction.GET_MISSION_PROGRESS))
        assertEquals(1, arr.length())
        assertEquals(0, arr.getJSONObject(0).getInt("availableMissions"))
    }

    @Test fun `미션 - 하나 도전하면 제공 수는 하나 줄어 빈 슬롯 응답에 쓰지 않는다`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        val before = missionTab.offeredNow()
        missionTab.refresh()
        missionTab.challenge(missionTab.state.value.personal.first().uid)
        assertEquals(before - 1, missionTab.offeredNow())
        val arr = dataOf(wired().answer(GleoFunction.GET_MISSION_PROGRESS))
        assertEquals(1, arr.length())
        assertFalse(arr.getJSONObject(0).has("availableMissions"))
    }

    // ---- 읽기 전용 ----

    @Test fun `세 함수를 불러도 저장 데이터와 화면 상태가 바뀌지 않는다`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        missionTab.challenge(m.uid)
        graph.sessions.save(record("s", now - 3_600_000))
        home.refresh()
        missionTab.refresh()

        val homeState = home.state.value
        val tabState = missionTab.state.value
        val profileState = graph.profiles.state(graph.profileId)
        val progresses = graph.profiles.progresses(graph.profileId)
        val missions = graph.missionStore.list(graph.profileId)
        val sessions = graph.sessions.list(graph.profileId)

        val a = wired()
        listOf(false, true).forEach { d ->
            driving = d
            GleoFunction.values().forEach { a.answer(it); a.answer(it).toJson() }
        }

        assertTrue(home.state.value === homeState)
        assertTrue(missionTab.state.value === tabState)
        assertEquals(profileState, graph.profiles.state(graph.profileId))
        assertEquals(progresses, graph.profiles.progresses(graph.profileId))
        assertEquals(missions, graph.missionStore.list(graph.profileId))
        assertEquals(sessions, graph.sessions.list(graph.profileId))
    }

    @Test fun `화면을 한 번도 갱신하지 않았어도 답은 저장값에서 나오고 화면 상태는 초기값 그대로`() {
        val id = graph.profiles.state(graph.profileId).selected!!
        graph.profiles.saveProgress(graph.profiles.progress(graph.profileId, id).copy(alias = "콩이"))
        val initialHome = home.state.value
        val initialTab = missionTab.state.value
        assertEquals("콩이", dataOf(wired().answer(GleoFunction.GET_PET_STATUS)).getJSONObject(0).getString("petName"))
        wired().answer(GleoFunction.GET_MISSION_PROGRESS)
        assertEquals(HomeState(), home.state.value)
        assertTrue(home.state.value === initialHome)
        assertTrue(missionTab.state.value === initialTab)
    }

    private companion object {
        const val MAX_STAGE = 5
    }
}

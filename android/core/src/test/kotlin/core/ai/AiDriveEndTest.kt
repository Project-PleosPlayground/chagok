package core.ai

import core.Fx
import core.Mx
import core.Time
import core.character.InMemoryProfileStore
import core.character.Profile
import core.content.BundledContent
import core.flow.SessionEndPipeline
import core.mission.ConditionType
import core.mission.InMemoryMissionStore
import core.mission.MissionEngine
import core.mission.MissionKind
import core.mission.MissionStatus
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class AiDriveEndTest {
    private val H = 3_600_000L
    private val sessions = InMemorySessionStore(); private val store = InMemoryMissionStore()
    private val catalog = BundledContent.pack.missions   // 개인 2, 주간 10
    private val engine = MissionEngine(store, sessions, missions = { catalog }, random = Random(1))
    private val planner = PersonalMissionPlanner(engine, store, sessions, { catalog }, random = Random(2))
    private val builder = AiPayloadBuilder(sessions, store)
    private val p = Profile.DEFAULT
    private val mon9 = Fx.MON + 9 * H
    private val fri18 = Fx.MON + 4 * Fx.DAY + 18 * H + 12 * 60_000L
    private val ch = AiCharacter("볼트", 2, "차 안에 사는 작은 반려 캐릭터")

    /** 월~목 하루 한 번씩 운전. 급제동이 많고 깜빡이는 잘 켠다 */
    private fun weekOfBraking() {
        for (d in 0..3) sessions.save(Fx.record("w$d", Fx.MON + d * Fx.DAY + 8 * H, km = 12f, events = List(3) { Fx.ev(HARSH_BRAKE, 10_000L + it * 20_000L) } + listOf(Fx.ev(LANE_CHANGE_SIGNALED, 100_000), Fx.ev(LANE_CHANGE_SIGNALED, 120_000))))
    }

    // ───────── 습관 → 단계 ─────────

    @Test fun `7일 습관 - 가장 잦은 나쁜 습관을 고치고 잘하는 것을 지킨다`() {
        weekOfBraking()
        val h = HabitRanker.rank(sessions.list(p), tzOffsetMs = Time.KST_OFFSET_MS)
        assertEquals(ConditionType.HARSH_BRAKE_MAX, h.improve!!.condition); assertEquals(3f, h.improve!!.dailyAvg); assertEquals(2f, h.improve!!.target, "평균 3회 → 2회 이하")
        assertEquals(ConditionType.SIGNALED_RATE_MIN, h.keep!!.condition); assertEquals(1f, h.keep!!.target)
    }

    @Test fun `바라는 목표치를 조건 표의 단계로 바꾼다`() {
        assertEquals(2, HabitRanker.tierFor(catalog, ConditionType.HARSH_BRAKE_MAX, 2f), "표 [3,2,1,0]: 2회 이하 = 2단계")
        assertEquals(4, HabitRanker.tierFor(catalog, ConditionType.HARSH_BRAKE_MAX, 0f))
        assertEquals(1, HabitRanker.tierFor(catalog, ConditionType.HARSH_BRAKE_MAX, 7f), "표 밖(너무 쉬움)이면 가장 쉬운 단계")
        assertEquals(4, HabitRanker.tierFor(catalog, ConditionType.SIGNALED_RATE_MIN, 1f), "비율 1.0 → 100% = 4단계")
        assertEquals(2, HabitRanker.tierFor(catalog, ConditionType.SIGNALED_RATE_MIN, 0.85f), "85% → 80% 단계")
        assertEquals(1, HabitRanker.tierFor(catalog, ConditionType.SIGNALED_RATE_MIN, 0.5f), "표보다 낮으면 가장 쉬운 단계")
        assertEquals(1, HabitRanker.tierFor(catalog, ConditionType.SEATBELT_BEFORE_START, 0f))
        assertNull(HabitRanker.tierFor(catalog.copy(conditions = emptyList()), ConditionType.HARSH_BRAKE_MAX, 1f))
    }

    // ───────── 개인 미션 제공 ─────────

    @Test fun `오늘 개인 미션을 습관으로 정해 제공하고(남는 칸은 랜덤), refresh 는 랜덤을 더하지 않는다`() {
        weekOfBraking()
        val plan = planner.offerToday(p, fri18)
        assertEquals(ConditionType.HARSH_BRAKE_MAX, plan.improve!!.condition); assertEquals(2, plan.improve!!.tier); assertEquals("급제동 2회 이하", plan.improve!!.title)
        assertEquals(ConditionType.SIGNALED_RATE_MIN, plan.keep!!.condition); assertEquals(4, plan.keep!!.tier)
        assertEquals(PER_DAY - 2, plan.filler.size); assertEquals(PER_DAY, plan.all.size)
        engine.refresh(p, fri18, null)
        val personal = engine.offers(p, fri18).filter { it.kind == MissionKind.PERSONAL }
        assertEquals(PER_DAY, personal.size, "하루 개수 그대로"); assertTrue(personal.all { it.validUntilMs == Fx.MON + 5 * Fx.DAY })
        assertEquals(10, engine.offers(p, fri18).count { it.kind == MissionKind.WEEKLY }, "주간 미션은 refresh 가 랜덤으로")
        assertEquals(PersonalMissionPlanner.Plan.NONE, planner.offerToday(p, fri18 + H), "같은 날 다시 부르면 없음")
    }

    @Test fun `습관이 하나뿐이면 나머지는 다른 조건에서 랜덤으로 채운다`() {
        // 급제동만 있고 차선변경이 없어 keep 은 "0회였던 습관"(급가속 0)으로 잡힌다 → 둘 다 습관. 급가속도 없게 만들려면 셋 다 0 이어야 하므로 깜빡이 없는 차선변경만 있는 주행으로 만든다
        for (d in 0..2) sessions.save(Fx.record("w$d", Fx.MON + d * Fx.DAY + 8 * H, events = listOf(Fx.ev(LANE_CHANGE_NO_SIGNAL))))
        val plan = planner.offerToday(p, mon9 + 3 * Fx.DAY)
        assertEquals(ConditionType.LANE_NO_SIGNAL_MAX, plan.improve!!.condition); assertEquals(4, plan.improve!!.tier, "평균 1회 → 0회 이하")
        assertEquals(ConditionType.HARSH_BRAKE_MAX, plan.keep!!.condition, "7일 동안 0회였던 습관을 지킨다"); assertEquals(4, plan.keep!!.tier)
        assertEquals(PER_DAY, plan.all.size); assertEquals(PER_DAY, plan.all.map { it.condition }.distinct().size)
    }

    @Test fun `7일 유효 주행 3회 미만이면 제공하지 않고 refresh 가 랜덤으로 채운다`() {
        sessions.save(Fx.record("a", mon9, events = listOf(Fx.ev(HARSH_BRAKE))))
        sessions.save(Fx.record("b", mon9 + Fx.DAY, events = listOf(Fx.ev(HARSH_BRAKE))))
        assertEquals(PersonalMissionPlanner.Plan.NONE, planner.offerToday(p, mon9 + 2 * Fx.DAY))
        engine.refresh(p, mon9 + 2 * Fx.DAY, null)
        assertEquals(PER_DAY, engine.offers(p, mon9 + 2 * Fx.DAY).count { it.kind == MissionKind.PERSONAL })
    }

    @Test fun `refresh 가 먼저 돌았으면 그날은 더 제공하지 않는다`() {
        weekOfBraking()
        engine.refresh(p, fri18, null)
        assertEquals(PersonalMissionPlanner.Plan.NONE, planner.offerToday(p, fri18))
        assertEquals(PER_DAY, engine.offers(p, fri18).count { it.kind == MissionKind.PERSONAL })
    }

    // ───────── 리포트 입력 ─────────

    @Test fun `주행 끝 페이로드 - 샘플 모양, 어제·오늘 미션, 도로명·날짜·시각 없음`() {
        weekOfBraking()
        // 목요일 개인 미션 하나를 도전해 실패시킨다 (급제동 0회 이하인데 3회)
        val thu9 = Fx.MON + 3 * Fx.DAY + 7 * H
        val m = engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, thu9)!!
        engine.challenge(p, m.uid, thu9, driving = false)
        engine.onDriveEnd(sessions.get("w3")!!, VehicleStatus(), thu9 + 2 * H)
        assertEquals(MissionStatus.FAILED, store.list(p).single { it.uid == m.uid }.status)
        // 금요일: 습관 미션 제공 후 퇴근 주행
        planner.offerToday(p, fri18 - 10 * H)
        val r = Fx.record("c2", fri18, km = 14.2f, minutes = 39, events = listOf(Fx.ev(HARSH_BRAKE, 480_000, 2), Fx.ev(LANE_CHANGE_SIGNALED, 1_380_000), Fx.ev(LANE_CHANGE_NO_SIGNAL, 1_920_000)))
        sessions.save(r)
        val pl = builder.driveEnd(r, ch, VehicleStatus(evSocPct = 41, charging = false, tireWarning = false), r.endWallMs)
        assertEquals("금", pl.session.weekday); assertEquals("저녁", pl.session.time_of_day); assertEquals("퇴근길(추정)", pl.session.trip_label)
        assertEquals(14, pl.session.distance_km); assertEquals(39, pl.session.duration_min)
        assertEquals(8, pl.session.events[0].at_min); assertEquals("HARSH_BRAKE", pl.session.events[0].type); assertEquals(54, pl.session.events[0].speed_kmh)
        assertEquals(5, pl.history.sessions_last_7d); assertEquals(13, pl.history.harsh_brake_7d); assertEquals(0, pl.history.harsh_brake_prev_7d)
        assertEquals(0, pl.history.sessions_prev_7d, "그 전 7일엔 주행이 없다")
        assertEquals(0, pl.missions.request_count); assertEquals("HARSH_BRAKE_MAX", pl.missions.habits.improve!!.condition)
        assertEquals(1, pl.missions.yesterday.size); assertEquals("FAILED", pl.missions.yesterday[0].result); assertEquals("급제동 없이", pl.missions.yesterday[0].title)
        assertEquals(PER_DAY, pl.missions.today.size); assertTrue(pl.missions.today.all { it.status == "OFFERED" })
        val json = AiJson.encode(pl)
        assertTrue(json.contains("\"trip_label\":\"퇴근길(추정)\"")); assertTrue(json.contains("\"request_count\":0")); assertTrue(json.contains("\"soc_pct\":41"))
        assertFalse(json.contains("lat")); assertFalse(json.contains("profile")); assertFalse(json.contains("uid"))
        // 사람·장소·때를 알아볼 수 있는 값: 기록엔 도로명(테헤란로)이 있지만 보내지 않는다
        assertFalse(json.contains("테헤란로")); assertFalse(json.contains("road")); assertFalse(json.contains("\"date\""))
        assertFalse(Regex("\\d{1,2}:\\d{2}").containsMatchIn(json), "HH:mm 시각 없음"); assertFalse(Regex("\\d{4}-\\d{2}-\\d{2}").containsMatchIn(json), "날짜 없음")
    }

    @Test fun `응답 파싱 - 리포트만 쓰고 빈 것은 null`() {
        assertEquals("오늘 한 번 놀랐네.", AiJson.parseReport("""{"report":" 오늘 한 번 놀랐네. ","missions":[]}"""))
        assertNull(AiJson.parseReport("""{"report":"   "}""")); assertNull(AiJson.parseReport("not json")); assertNull(AiJson.parseReport("""{"missions":[]}"""))
    }

    @Test fun `응답 파싱 - 종합 분석과 팁을 함께 읽고, 팁이 없거나 비면 null`() {
        assertEquals(AiReport("급가속이 3번 있었어요.", "천천히 출발해 보세요."), AiJson.parseAnalysis("""{"report":"급가속이 3번 있었어요.","tip":" 천천히 출발해 보세요. ","missions":[]}"""))
        assertEquals(AiReport("급가속이 3번 있었어요.", null), AiJson.parseAnalysis("""{"report":"급가속이 3번 있었어요.","tip":"  "}"""))
        assertEquals(AiReport("급가속이 3번 있었어요.", null), AiJson.parseAnalysis("""{"report":"급가속이 3번 있었어요."}"""))
        assertNull(AiJson.parseAnalysis("""{"tip":"천천히"}"""))
    }

    // ───────── 게이트·서비스 ─────────

    @Test fun `게이트 - 이벤트 없음·짧은 주행·무효 주행은 부르지 않는다`() {
        assertTrue(AiGate.shouldCall(Fx.record("a", fri18, minutes = 30, events = listOf(Fx.ev(HARSH_BRAKE)))))
        assertFalse(AiGate.shouldCall(Fx.record("b", fri18, minutes = 30)), "감점 이벤트 0")
        assertFalse(AiGate.shouldCall(Fx.record("c", fri18, minutes = 30, events = listOf(Fx.ev(LANE_CHANGE_SIGNALED)))), "깜빡이 켠 차선변경은 감점 아님")
        assertFalse(AiGate.shouldCall(Fx.record("d", fri18, minutes = 8, events = listOf(Fx.ev(HARSH_BRAKE)))), "10분 미만")
        assertFalse(AiGate.shouldCall(Fx.record("e", fri18, valid = false, events = listOf(Fx.ev(HARSH_BRAKE)))))
        assertTrue(AiGate.shouldCall(Fx.record("f", fri18, minutes = 4, events = listOf(Fx.ev(HARSH_BRAKE))), minMinutes = 0), "최소 시간을 낮추면 짧은 주행도")
    }

    @Test fun `주행 끝 - AI 가 답하면 코멘트를 바꾸고, 없거나 느리면 규칙 문장 그대로`() = runTest {
        weekOfBraking()
        val pipeline = SessionEndPipeline(sessions, InMemoryProfileStore(), engine)
        val r = Fx.record("x", fri18, events = listOf(Fx.ev(HARSH_BRAKE), Fx.ev(HARSH_ACCEL, 20_000)))
        val summary = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
        assertTrue(summary.comment.startsWith("급하게 멈춘 게 1번"))

        var sent: String? = null
        val ok = AiDriveEnd(builder, AiService({ json -> sent = json; """{"report":"퇴근길에 한 번 놀랐네. 그래도 깜빡이는 늘 켜 줘서 좋아.","missions":[]}""" }))
        assertEquals("퇴근길에 한 번 놀랐네. 그래도 깜빡이는 늘 켜 줘서 좋아.", ok.run(summary, ch, VehicleStatus(), r.endWallMs).comment)
        assertTrue(sent!!.contains("\"harsh_brake_7d\""))
        assertTrue(ok.run(summary, ch, VehicleStatus(), r.endWallMs).aiGenerated, "AI 문장은 표시가 붙는다")
        assertFalse(summary.aiGenerated, "규칙 문장은 표시가 없다")

        val none = AiDriveEnd(builder, AiService(null))
        assertEquals(summary.comment, none.run(summary, ch, VehicleStatus(), r.endWallMs).comment)
        assertFalse(none.run(summary, ch, VehicleStatus(), r.endWallMs).aiGenerated)
        val slow = AiDriveEnd(builder, AiService({ delay(20_000); "{\"report\":\"늦었어\"}" }, timeoutMs = 100))
        assertEquals(summary.comment, slow.run(summary, ch, VehicleStatus(), r.endWallMs).comment)
        val broken = AiDriveEnd(builder, AiService({ throw IllegalStateException("network") }))
        assertEquals(summary.comment, broken.run(summary, ch, VehicleStatus(), r.endWallMs).comment)

        val quiet = pipeline.onSessionEnd(Fx.record("q", fri18 + H, minutes = 20), VehicleStatus(), fri18 + 2 * H)
        var called = false
        assertEquals(quiet.comment, AiDriveEnd(builder, AiService({ called = true; "{\"report\":\"x\"}" })).run(quiet, ch, VehicleStatus(), fri18 + 2 * H).comment)
        assertFalse(called, "이벤트 없는 주행은 부르지 않는다")
    }

    @Test fun `동의하지 않은 프로필은 아무것도 보내지 않는다`() = runTest {
        val pipeline = SessionEndPipeline(sessions, InMemoryProfileStore(), engine)
        val r = Fx.record("n", fri18, events = listOf(Fx.ev(HARSH_BRAKE)))
        val summary = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
        var called = 0
        val client = AiService({ called++; "{\"report\":\"보냈다\"}" })
        assertEquals(summary.comment, AiDriveEnd(builder, client, consented = { false }).run(summary, ch, VehicleStatus(), r.endWallMs).comment)
        assertEquals(0, called, "동의 전에는 서버를 부르지 않는다")
        assertEquals("보냈다", AiDriveEnd(builder, client, consented = { it == p }).run(summary, ch, VehicleStatus(), r.endWallMs).comment)
        assertEquals(1, called)
    }
}

/** 하루 개인 미션 수(번들 콘텐츠). 바뀌어도 테스트가 따라가게 */
private val PER_DAY = BundledContent.pack.missions.rules.personalPerDay

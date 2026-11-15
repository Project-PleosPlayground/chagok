package ai.safetypoc.app.ui.report

import ai.safetypoc.app.AppConfig
import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.drive.DemoDriveReplay
import ai.safetypoc.app.drive.DriveRecorder
import core.character.GrowthModule
import core.session.VehicleStatus
import engine.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** 주행 결과 화면의 값 규칙 (영역사양_주행결과화면 "점수에 따라 바뀌는 것", 요약 칸, 받은 보상, 부제) */
class DriveResultTest {
    private val kst = ZoneOffset.ofHours(9)

    @Test fun `점수 고리 색은 50 이하 빨강, 51~80 노랑, 81~100 초록이다`() {
        for (s in listOf(0, 1, 49, 50)) assertEquals("점수 $s", ScoreBand.RED, scoreBand(s))
        for (s in listOf(51, 52, 79, 80)) assertEquals("점수 $s", ScoreBand.YELLOW, scoreBand(s))
        for (s in listOf(81, 82, 99, 100)) assertEquals("점수 $s", ScoreBand.GREEN, scoreBand(s))
    }

    @Test fun `별은 20점마다 하나이고 5개를 넘지 않는다`() {
        val expected = mapOf(0 to 0, 19 to 0, 20 to 1, 39 to 1, 40 to 2, 42 to 2, 59 to 2, 60 to 3, 79 to 3, 80 to 4, 99 to 4, 100 to 5)
        for ((score, stars) in expected) assertEquals("점수 $score", stars, starCount(score))
        assertEquals(5, starCount(120))
        assertEquals(0, starCount(-5))
    }

    @Test fun `요약 칸은 횟수가 0이면 좋아요, 1 이상이면 주의다`() {
        assertEquals(SummaryStatus.GOOD, SummaryLine(SummaryKind.SPEEDING, 0).status)
        assertEquals(SummaryStatus.CAUTION, SummaryLine(SummaryKind.ACCEL, 1).status)
        assertEquals(SummaryStatus.CAUTION, SummaryLine(SummaryKind.ACCEL, 5).status)
    }

    @Test fun `요약 칸 네 개의 순서와 세는 이벤트`() {
        assertEquals(listOf(SummaryKind.ACCEL, SummaryKind.BRAKE, SummaryKind.NO_SIGNAL, SummaryKind.SPEEDING), SummaryKind.entries)
        assertEquals(
            listOf(setOf(EventType.HARSH_ACCEL), setOf(EventType.HARSH_BRAKE), setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL), setOf(EventType.SPEEDING)),
            SummaryKind.entries.map { SUMMARY_EVENTS.getValue(it) },
        )
    }

    private val growth = GrowthModule()

    @Test fun `받은 경험치가 없으면 경험치 칩이 없다`() {
        assertNull(expReward(growth, 0, 150, null))
        assertNull(expReward(growth, -10, 150, null))
    }

    @Test fun `경험치 칩은 지금 단계 막대에서 오른 퍼센트다`() {
        // 2단계(100~300): 130 → 150 은 막대 15% → 25%
        assertEquals(ExpReward.Percent(10), expReward(growth, 20, 150, null))
    }

    @Test fun `퍼센트가 0으로 반올림돼도 1부터 보인다`() {
        assertEquals(ExpReward.Percent(MIN_EXP_PERCENT), expReward(growth, 1, 101, null))
    }

    @Test fun `단계가 오르면 레벨 달성 칩이다`() {
        assertEquals(ExpReward.LevelUp(3), expReward(growth, 50, 320, 3))
    }

    @Test fun `최고 단계이거나 캐릭터가 없으면 경험치 숫자를 보인다`() {
        assertEquals(ExpReward.Points(30), expReward(growth, 30, 1200, null))
        assertEquals(ExpReward.Points(30), expReward(growth, 30, null, null))
    }

    private fun scores(endWallMs: Long, minutes: Int) = SessionScores(
        valid = true, score = 42, distanceKm = 18.4f, durationMs = minutes * 60_000L, endWallMs = endWallMs, lines = emptyList(),
    )

    private fun at(day: Int, hour: Int, minute: Int) = java.time.ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, kst).toInstant().toEpochMilli()

    @Test fun `부제는 같은 날이면 오늘, 아니면 날짜를 쓴다`() {
        val sameDay = tripLine(scores(at(13, 14, 20), 32), nowMs = at(13, 20, 0), zone = kst)
        assertEquals(TripLine(today = true, date = "2026.11.13", time = "14:20", minutes = 32, km = 18.4f), sameDay)
        val nextDay = tripLine(scores(at(13, 23, 50), 32), nowMs = at(14, 0, 10), zone = kst)
        assertFalse(nextDay.today)
        assertEquals("2026.11.13", nextDay.date)
    }

    @Test fun `부제의 시각은 24시간이고 0으로 채운다`() {
        assertEquals("09:05", tripLine(scores(at(13, 9, 5), 10), nowMs = at(13, 12, 0), zone = kst).time)
        assertEquals("00:00", tripLine(scores(at(13, 0, 0), 10), nowMs = at(13, 12, 0), zone = kst).time)
    }

    @Test fun `끝난 시연 주행이 주행 결과 값이 된다`() {
        val graph = AppGraph()
        val now = 1_800_000_000_000L
        val summary = DemoDriveReplay.run(AppConfig.engine, now)!!.let { DriveRecorder(graph).onSessionEnded(it, VehicleStatus(), now) }
        val end = summary.toDriveEnd(graph.growth)
        assertEquals(summary.record.endWallMs, end.session.endWallMs)
        assertEquals(summary.record.summaryLines(), end.summary)
        assertEquals(1, end.summary.first { it.kind == SummaryKind.BRAKE }.count)
        assertFalse("규칙 문장이면 AI 표시가 없다", end.aiGenerated)
        assertEquals("AI 팁이 없으면 가장 많았던 급감속을 줄이는 규칙 팁", "앞차와 거리를 한 대 더 두면 급감속이 줄어요.", end.tip)
        assertNotNull(end.expReward)
        assertTrue(end.points >= end.coinsGained)
    }

    @Test fun `종합 분석은 문장마다 나눈다`() {
        assertEquals(
            listOf("급가속이 3번 있었어요.", "그래도 깜빡이는 잘 켰어요!", "다음엔 천천히 가 볼까요?"),
            sentencesOf("급가속이 3번 있었어요. 그래도 깜빡이는 잘 켰어요!  다음엔 천천히 가 볼까요?"),
        )
        assertEquals(listOf("한 문장"), sentencesOf(" 한 문장 "))
    }
}

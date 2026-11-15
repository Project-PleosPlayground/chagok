package ai.safetypoc.app.ui.report

import core.character.GrowthModule
import core.session.SessionRecord
import engine.EventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** 점수 고리·숫자의 색 구간 (디자인 시스템 6절 score_ring) */
enum class ScoreBand { RED, YELLOW, GREEN }

/** 점수 50 이하 빨강, 80 이하 노랑, 그 위 초록 */
const val RED_MAX_SCORE = 50
const val YELLOW_MAX_SCORE = 80

fun scoreBand(score: Int): ScoreBand = when {
    score <= RED_MAX_SCORE -> ScoreBand.RED
    score <= YELLOW_MAX_SCORE -> ScoreBand.YELLOW
    else -> ScoreBand.GREEN
}

/** star_rating: 별은 5개이고 20점마다 하나씩 채운다 */
const val STAR_COUNT = 5
const val SCORE_PER_STAR = 20

fun starCount(score: Int): Int = (score / SCORE_PER_STAR).coerceIn(0, STAR_COUNT)

/** 주행 요약 칸의 종류 (영역사양_주행결과화면 5-7 순서) */
enum class SummaryKind { ACCEL, BRAKE, NO_SIGNAL, SPEEDING }

/** 칸 하나가 세는 엔진 이벤트. "방향지시등 미시행" 은 반대 깜빡이도 함께 센다(만화·통계·미션과 같게, 11/15) */
val SUMMARY_EVENTS: Map<SummaryKind, Set<EventType>> = mapOf(
    SummaryKind.ACCEL to setOf(EventType.HARSH_ACCEL),
    SummaryKind.BRAKE to setOf(EventType.HARSH_BRAKE),
    SummaryKind.NO_SIGNAL to setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL),
    SummaryKind.SPEEDING to setOf(EventType.SPEEDING),
)

/** status_badge 의 종류. 횟수가 0이면 좋아요, 1 이상이면 주의 */
enum class SummaryStatus { CAUTION, GOOD }

data class SummaryLine(val kind: SummaryKind, val count: Int) {
    val status: SummaryStatus get() = if (count == 0) SummaryStatus.GOOD else SummaryStatus.CAUTION
}

/** 다음 주행 팁 규칙 문장: 이번 주행에서 가장 많았던 행동을 줄일 행동 하나. AI 팁이 없을 때 쓴다 (통계 탭 하루 분석과 같은 문장) */
fun SessionRecord.ruleTip(): String {
    val a = count(EventType.HARSH_ACCEL); val b = count(EventType.HARSH_BRAKE)
    val n = count(EventType.LANE_CHANGE_NO_SIGNAL) + count(EventType.LANE_CHANGE_WRONG_SIGNAL)
    return when (maxOf(a, b, n)) {
        0 -> "지금처럼 부드럽게 출발하고 멈춰 보세요."
        b -> "앞차와 거리를 한 대 더 두면 급감속이 줄어요."
        a -> "출발할 때 3초에 걸쳐 천천히 밟아 보세요."
        else -> "차선을 바꾸기 전에 깜빡이를 먼저 켜 보세요."
    }
}

/** 3컷 만화 장면 그림 (assets/comic, 16:9, 왼쪽 1/3 은 말풍선 자리). 챗지피티로 만든 차곡 만화체 장면과 칸의 효과음 글자 (11/15) */
/** [biasX] 칸에 맞춰 자를 때 가로 기준(-1 왼쪽 ~ 1 오른쪽). 그림마다 캐릭터가 있는 쪽. 4컷(생각·신남)만 차 없이 캐릭터만 (11/15) */
enum class ComicArt(val path: String, val sfx: String, val biasX: Float) {
    START("comic/start.jpg", "부릉~", 0.55f), STARTLED("comic/startled.jpg", "끼익!", 0f), THUMBS("comic/thumbs.jpg", "GOOD!", 0.55f),
    THINK("comic/think.jpg", "음…", 0.2f), CHEER("comic/cheer.jpg", "야호!", 0.2f),
}

/** 만화 한 칸: 그림, 캐릭터 대사, 그림이 왼쪽인지(칸마다 랜덤) */
data class ComicPanel(val art: ComicArt, val line: String, val artLeft: Boolean)

/**
 * 주행 결과 3컷 만화. 그림은 앱이 주행 내용으로 고른다: 1컷 출발, 2컷 감점 행동이 있으면 놀람·없으면 엄지척,
 * 3컷 미션을 성공했으면 신남·아니면 생각. 대사는 AI 가 쓴 [aiLines], 없으면 규칙 대사.
 * 그림·대사 좌우는 주행마다 랜덤(같은 주행은 늘 같다).
 */
fun SessionRecord.comicPanels(completedMissions: Int, aiLines: List<String>?): List<ComicPanel> {
    val a = count(EventType.HARSH_ACCEL); val b = count(EventType.HARSH_BRAKE)
    val n = count(EventType.LANE_CHANGE_NO_SIGNAL) + count(EventType.LANE_CHANGE_WRONG_SIGNAL)
    val worst = maxOf(a, b, n)
    // 1 출발 · 2 가장 눈에 띈 일 · 3 잘한 점 · 4 다음엔(미션 성공이면 신남)
    val arts = listOf(
        ComicArt.START,
        if (worst > 0) ComicArt.STARTLED else ComicArt.THUMBS,
        if (worst > 0) ComicArt.THUMBS else ComicArt.START,
        if (completedMissions > 0) ComicArt.CHEER else ComicArt.THINK,
    )
    val minutes = ((endWallMs - startWallMs) / 60_000L).toInt()
    val counts = listOfNotNull(
        b.takeIf { it > 0 }?.let { "급하게 멈춘 게 ${it}번" },
        a.takeIf { it > 0 }?.let { "확 밟은 게 ${it}번" },
        n.takeIf { it > 0 }?.let { "깜빡이 없이 바꾼 게 ${it}번" },
    )
    val tip = when {
        worst == 0 -> "지금처럼 부드럽게만 가자."
        worst == b -> "다음엔 앞차랑 한 대 더 띄워 보자."
        worst == a -> "다음엔 출발할 때 3초 천천히 밟아 보자."
        else -> "다음엔 차선 바꾸기 전에 깜빡이 먼저 켜 보자."
    }
    val rule = listOf(
        "오늘 ${minutes}분 동안 %.1fkm 같이 달렸어!".format(distanceKm),
        if (counts.isEmpty()) "한 번도 안 놀랐어! 정말 부드러웠어." else counts.joinToString(", ") + "… 깜짝 놀랐어!",
        when {
            n > 0 -> "그래도 끝까지 같이 와 줘서 고마워!"
            count(EventType.LANE_CHANGE_SIGNALED) > 0 -> "그래도 깜빡이는 한 번도 안 빠뜨렸어!"
            else -> "그래도 차선은 차분하게 잘 지켰어!"
        },
        if (completedMissions > 0) "$tip 미션도 ${completedMissions}개 성공했어!" else tip,
    )
    val lines = aiLines?.takeIf { it.size == arts.size } ?: rule
    return arts.indices.map { ComicPanel(arts[it], lines[it], artLeft = false) }
}

fun SessionRecord.summaryLines(): List<SummaryLine> =
    SummaryKind.entries.map { SummaryLine(it, SUMMARY_EVENTS.getValue(it).sumOf(::count)) }

/** reward_chip_exp 에 보일 것 */
sealed interface ExpReward {
    /** 지금 단계 막대에서 오른 퍼센트 */
    data class Percent(val percent: Int) : ExpReward
    /** 단계가 올랐다 */
    data class LevelUp(val stage: Int) : ExpReward
    /** 막대가 없는 최고 단계라 경험치 숫자 그대로 */
    data class Points(val xp: Int) : ExpReward
}

/** 받은 경험치가 있어도 퍼센트가 0으로 반올림되면 "+0%" 로 보이지 않게 이 값부터 보인다 */
const val MIN_EXP_PERCENT = 1
private const val PERCENT = 100

/**
 * 받은 경험치 칩. [gained] 가 0 이하면 null. [xpAfter] 는 받은 뒤 경험치(캐릭터를 고르지 않았으면 null).
 * 단계가 오르면 레벨 달성, 최고 단계면 숫자, 그 밖에는 지금 단계 막대에서 오른 퍼센트다.
 */
fun expReward(growth: GrowthModule, gained: Int, xpAfter: Int?, leveledUpTo: Int?): ExpReward? {
    if (gained <= 0) return null
    if (leveledUpTo != null) return ExpReward.LevelUp(leveledUpTo)
    if (xpAfter == null || growth.stageFor(xpAfter) >= growth.maxStage) return ExpReward.Points(gained)
    val percent = ((growth.progress(xpAfter) - growth.progress(xpAfter - gained)) * PERCENT).roundToInt()
    return ExpReward.Percent(percent.coerceAtLeast(MIN_EXP_PERCENT))
}

/** 주행 결과 부제 "오늘 14:20 도착 · 32분 · 18.4km" 의 값 (디자인 시스템 8절: 날짜 YYYY.MM.DD, 시각 24시간) */
data class TripLine(val today: Boolean, val date: String, val time: String, val minutes: Int, val km: Float)

private const val MS_PER_MINUTE = 60_000L
private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.ROOT)
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

fun tripLine(s: SessionScores, nowMs: Long, zone: ZoneId): TripLine {
    val end = Instant.ofEpochMilli(s.endWallMs).atZone(zone)
    val today = end.toLocalDate() == Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return TripLine(today, DATE_FORMAT.format(end), TIME_FORMAT.format(end), (s.durationMs / MS_PER_MINUTE).toInt(), s.distanceKm)
}

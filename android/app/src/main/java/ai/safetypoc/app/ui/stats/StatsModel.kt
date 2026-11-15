package ai.safetypoc.app.ui.stats

import ai.safetypoc.app.ui.report.EventPlace
import core.character.ProfileStore
import core.mission.MissionStatus
import core.mission.MissionStore
import core.session.SessionRecord
import core.session.SessionStore
import engine.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** 통계에서 세는 감점 행동 세 가지 (시안 "급가속 · 급감속 · 방향지시등 미시행") */
enum class StatKind(val types: Set<EventType>) {
    ACCEL(setOf(EventType.HARSH_ACCEL)),
    BRAKE(setOf(EventType.HARSH_BRAKE)),
    NO_SIGNAL(setOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL)),
}

/** 주행 목록 한 줄 */
data class DriveRow(
    val id: String,
    val score: Int?,
    /** 한 문장. [임시] 주행마다 AI 문장을 저장하지 않아 규칙 문장 */
    val line: String,
    val startWallMs: Long,
    val endWallMs: Long,
    val distanceKm: Float,
    val origin: String?,
    val destination: String?,
)

/** 누적 안전점수 카드 */
data class Cumulative(
    /** 유효 주행 전체 평균. 유효 주행이 없으면 null */
    val score: Int?,
    /** 이번 주 평균 − 지난주 평균. 둘 중 하나라도 주행이 없으면 null */
    val weekDelta: Int?,
    /** 이번 주 감점 행동 횟수 */
    val weekCounts: Map<StatKind, Int>,
)

/** 1주일간 주행 기록: 이번 주 월~일 날마다 감점 행동 횟수 */
/** 1주일 그래프: 날마다 감점 행동 횟수와 평균 안전점수(주행 없는 날 null). 화면은 점수 막대를 그린다 (11/15) */
data class WeekChart(val days: List<LocalDate>, val counts: Map<StatKind, List<Int>>, val scores: List<Int?> = emptyList())

/** 고른 날의 분석 [임시] 규칙 문장. AI 로 바꿀 자리 */
data class DayAnalysis(val summary: String, val tip: String)

/** 주행 상세 (DashboardView3) */
data class DriveDetail(
    val row: DriveRow,
    val missions: Int,
    /** 주행 구간 막대: 시작 0 ~ 끝 1 위의 이벤트 자리 */
    val marks: List<Mark>,
    val events: List<EventLine>,
    val habits: List<Habit>,
    /** 지도 핀: 좌표가 있는 사건만 (기기 안 기록) */
    val places: List<EventPlace> = emptyList(),
)

data class Mark(val at: Float, val type: EventType)
data class EventLine(val type: EventType, val atWallMs: Long, val place: String?)

/** 운전 습관 한 줄: 값 글자와 3단계(0 나쁨 · 1 주의 · 2 좋음) */
data class Habit(val kind: HabitKind, val value: String, val level: Int)
enum class HabitKind { ACCEL, BRAKE, NO_SIGNAL, NIGHT }

data class StatsState(
    val month: YearMonth = YearMonth.now(),
    val today: LocalDate = LocalDate.now(),
    val selected: LocalDate = LocalDate.now(),
    /** 날짜마다 유효 주행 수 (달력 칸 진하기) */
    val drivesPerDay: Map<LocalDate, Int> = emptyMap(),
    /** 날짜마다 유효 주행 평균 점수 (달력 칸 색) */
    val dayScores: Map<LocalDate, Int> = emptyMap(),
    val dayDrives: List<DriveRow> = emptyList(),
    val weekComment: String = "",
    val cumulative: Cumulative = Cumulative(null, null, emptyMap()),
    val chart: WeekChart = WeekChart(emptyList(), emptyMap()),
    /** 오늘이 아닌 날을 고르면 그날 분석, 오늘이면 null(누적 카드) */
    val dayAnalysis: DayAnalysis? = null,
    val detail: DriveDetail? = null,
    val coins: Int = 0,
)

interface StatsActions {
    fun prevMonth()
    fun nextMonth()
    fun selectDate(date: LocalDate)
    fun openDrive(id: String)
    fun closeDrive()
}

/**
 * 통계 탭 (DashboardView 1·2·3). 기록은 [sessions] 의 유효 주행. 화면에 들어올 때 [enter], 다시 읽을 때 [refresh].
 * 날짜·주는 기기 시간대([zone]), 주는 월요일부터.
 */
class StatsModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val sessions: SessionStore,
    private val missions: MissionStore,
    private val profiles: ProfileStore,
    /** 주행 한 줄 문장. 앱은 SessionEndPipeline.fallbackComment */
    private val lineFor: (SessionRecord) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : StatsActions {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var detailId: String? = null

    /** 탭에 들어올 때: 오늘, 이번 달, 목록으로 */
    fun enter() {
        val today = today()
        month = YearMonth.from(today); selected = today; detailId = null
        refresh()
    }

    fun refresh() {
        val z = zone(); val today = today()
        val all = sessions.list(profileId).filter { it.valid }.sortedBy { it.startWallMs }
        val byDay = all.groupBy { dateOf(it.startWallMs, z) }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = all.filter { dateOf(it.startWallMs, z) in weekStart..weekStart.plusDays(6) }
        val lastWeek = all.filter { dateOf(it.startWallMs, z) in weekStart.minusDays(7)..weekStart.minusDays(1) }
        val days = (0L..6L).map { weekStart.plusDays(it) }
        val dayList = byDay[selected].orEmpty().sortedBy { it.startWallMs }
        _state.value = StatsState(
            month = month, today = today, selected = selected,
            drivesPerDay = byDay.mapValues { it.value.size },
            dayScores = byDay.mapValues { e -> e.value.map { it.score }.average().roundToInt() },
            dayDrives = dayList.map(::row),
            weekComment = weekComment(thisWeek, lastWeek),
            cumulative = Cumulative(
                score = all.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.roundToInt(),
                weekDelta = if (thisWeek.isEmpty() || lastWeek.isEmpty()) null
                    else (thisWeek.map { it.score }.average() - lastWeek.map { it.score }.average()).roundToInt(),
                weekCounts = StatKind.entries.associateWith { k -> thisWeek.sumOf { r -> count(r, k) } },
            ),
            chart = WeekChart(
                days, StatKind.entries.associateWith { k -> days.map { d -> byDay[d].orEmpty().sumOf { count(it, k) } } },
                scores = days.map { d -> byDay[d]?.takeIf { it.isNotEmpty() }?.map { it.score }?.average()?.roundToInt() },
            ),
            dayAnalysis = if (selected == today) null else dayAnalysis(dayList),
            detail = detailId?.let { id -> all.firstOrNull { it.id == id } }?.let(::detail),
            coins = profiles.state(profileId).coins,
        )
    }

    override fun prevMonth() { month = month.minusMonths(1); refresh() }
    override fun nextMonth() { month = month.plusMonths(1); refresh() }
    override fun selectDate(date: LocalDate) { selected = date; month = YearMonth.from(date); detailId = null; refresh() }
    override fun openDrive(id: String) { detailId = id; refresh() }
    override fun closeDrive() { detailId = null; refresh() }

    private fun today() = dateOf(now(), zone())

    private fun row(r: SessionRecord) = DriveRow(
        r.id, r.score, lineFor(r), r.startWallMs, r.endWallMs, r.distanceKm, r.origin, r.destination,
    )

    private fun detail(r: SessionRecord): DriveDetail {
        val span = (r.result.endMs - r.result.startMs).coerceAtLeast(1)
        val deducted = r.events.filter { it.event.type in SessionRecord.DEDUCTED }.sortedBy { it.event.startMs }
        val succeeded = missions.list(profileId).count {
            it.status == MissionStatus.SUCCEEDED && it.endedMs != null && it.endedMs!! in (r.startWallMs - MISSION_JUDGE_SLACK_MS)..(r.endWallMs + MISSION_JUDGE_SLACK_MS)
        }
        return DriveDetail(
            row = row(r),
            missions = succeeded,
            marks = deducted.map { Mark(((it.event.startMs - r.result.startMs).toFloat() / span).coerceIn(0f, 1f), it.event.type) },
            events = deducted.map { EventLine(it.event.type, r.startWallMs + (it.event.startMs - r.result.startMs), it.roadName) },
            habits = habits(r),
            places = deducted.mapNotNull { e ->
                val lat = e.lat ?: return@mapNotNull null; val lng = e.lng ?: return@mapNotNull null
                EventPlace(lat, lng, e.event.type, r.startWallMs + (e.event.startMs - r.result.startMs))
            },
        )
    }

    /** 3단계: 0회 좋음, 1~2회 주의, 3회 이상 나쁨. 깜빡이는 켠 비율, 야간은 22~04시 주행 비율 */
    private fun habits(r: SessionRecord): List<Habit> {
        fun countLevel(n: Int) = when { n == 0 -> 2; n <= 2 -> 1; else -> 0 }
        val a = r.count(EventType.HARSH_ACCEL); val b = r.count(EventType.HARSH_BRAKE)
        val noSig = count(r, StatKind.NO_SIGNAL)
        val lanes = r.laneChanges
        val missRate = if (lanes == 0) 0f else noSig.toFloat() / lanes
        val night = nightRatio(r.startWallMs, r.endWallMs, zone())
        return listOf(
            Habit(HabitKind.ACCEL, "${a}회", countLevel(a)),
            Habit(HabitKind.BRAKE, "${b}회", countLevel(b)),
            Habit(HabitKind.NO_SIGNAL, if (lanes == 0) "-" else "${(missRate * 100).roundToInt()}%",
                when { missRate == 0f -> 2; missRate <= 0.3f -> 1; else -> 0 }),
            Habit(HabitKind.NIGHT, "${(night * 100).roundToInt()}%", when { night == 0f -> 2; night <= 0.5f -> 1; else -> 0 }),
        )
    }

    private fun weekComment(thisWeek: List<SessionRecord>, lastWeek: List<SessionRecord>): String {
        if (thisWeek.isEmpty()) return "이번 주 첫 주행을 기다리고 있어요."
        if (lastWeek.isEmpty()) return "이번 주 ${thisWeek.size}번 달렸어요. 다음 주부터 지난주와 비교해 드릴게요."
        val now = thisWeek.sumOf { count(it, StatKind.BRAKE) }.toFloat() / thisWeek.size
        val before = lastWeek.sumOf { count(it, StatKind.BRAKE) }.toFloat() / lastWeek.size
        return when {
            now < before -> "지난주보다 급감속이 줄었어요. 한 박자 여유가 생겼네요."
            now > before -> "지난주보다 급감속이 조금 늘었어요. 앞차와 거리를 넉넉히 두어 보세요."
            else -> "지난주와 비슷하게 달리고 있어요. 이 흐름 그대로 가요."
        }
    }

    private fun dayAnalysis(day: List<SessionRecord>): DayAnalysis {
        if (day.isEmpty()) return DayAnalysis("이날은 주행 기록이 없어요.", "다음 주행에서 만나요.")
        val a = day.sumOf { count(it, StatKind.ACCEL) }; val b = day.sumOf { count(it, StatKind.BRAKE) }
        val n = day.sumOf { count(it, StatKind.NO_SIGNAL) }
        val avg = day.map { it.score }.average().roundToInt()
        val summary = buildString {
            append("${day.size}번 주행, 평균 ${avg}점이에요. ")
            if (a + b + n == 0) append("급가속·급감속·방향지시등 미시행이 한 번도 없었어요.")
            else append(listOfNotNull(
                a.takeIf { it > 0 }?.let { "급가속 ${it}회" }, b.takeIf { it > 0 }?.let { "급감속 ${it}회" },
                n.takeIf { it > 0 }?.let { "방향지시등 미시행 ${it}회" },
            ).joinToString(", ") + "가 있었어요.")
        }
        val tip = when (maxOf(a, b, n)) {
            0 -> "지금처럼 부드럽게 출발하고 멈춰 보세요."
            b -> "앞차와 거리를 한 대 더 두면 급감속이 줄어요."
            a -> "출발할 때 3초에 걸쳐 천천히 밟아 보세요."
            else -> "차선을 바꾸기 전에 깜빡이를 먼저 켜 보세요."
        }
        return DayAnalysis(summary, tip)
    }

    companion object {
        /** 미션 판정 시각이 주행 시각과 조금 어긋나도(늦게 기록, 시연 재생은 엔진 세션 시작보다 조금 앞) 이 주행으로 센다 */
        const val MISSION_JUDGE_SLACK_MS = 60_000L
        const val NIGHT_FROM_HOUR = 22
        const val NIGHT_TO_HOUR = 4

        fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        fun count(r: SessionRecord, k: StatKind) = r.result.events.count { it.type in k.types }

        /** 주행 시간 중 22~04시 비율. 1분 단위로 센다 */
        fun nightRatio(startMs: Long, endMs: Long, zone: ZoneId): Float {
            if (endMs <= startMs) return 0f
            var night = 0; var total = 0
            var t = startMs
            while (t < endMs) {
                val h = Instant.ofEpochMilli(t).atZone(zone).hour
                if (h >= NIGHT_FROM_HOUR || h < NIGHT_TO_HOUR) night++
                total++; t += 60_000L
            }
            return night.toFloat() / total
        }
    }
}

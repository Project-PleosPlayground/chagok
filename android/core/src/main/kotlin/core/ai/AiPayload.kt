package core.ai

import core.Time
import core.mission.ConditionRules
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import core.mission.MissionStore
import core.session.SessionRecord
import core.session.SessionStore
import core.session.VehicleStatus
import engine.EventType
import engine.Scoring
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset

/**
 * AI 중계 서버(`server/`, POST /api/drive-end) 입력. ai-poc/samples/drive_end_evening.json 과 같은 모양(snake_case).
 * 앱은 이 JSON 을 서버로 보내고, 서버가 Claude 를 부른다. 사용자가 동의한 프로필만 보낸다(AiDriveEnd 의 consented).
 * 사람·장소·때를 알아볼 수 있는 값은 넣지 않는다: 위도·경도·VIN·이름·도로명·날짜·정확한 시각이 없다.
 * 때는 요일과 시간대(아침·낮·저녁·밤·새벽), 이벤트는 주행 시작 뒤 몇 분째인지만, 거리는 km 정수.
 * 주행 끝 1회 호출. 미션은 앱이 규칙으로 정하므로(PersonalMissionPlanner) request_count 는 0 이고, 서버는 리포트 문장만 돌려준다.
 */
@Serializable data class AiCharacter(val name: String, val stage: Int, val persona: String)
/** [at_min] 주행 시작 뒤 몇 분째. [at_hotspot] 최근 30일 다른 주행에서도 감점 행동이 났던 곳(반경 200m)인지. 좌표는 보내지 않는다 */
@Serializable data class AiEvent(val type: String, val at_min: Int, val severity: Int, val speed_kmh: Int, val at_hotspot: Boolean = false)
@Serializable data class AiSession(
    val weekday: String, val time_of_day: String,
    val distance_km: Int, val duration_min: Int, val trip_label: String,
    val score: Int, val accel_score: Int, val brake_score: Int, val manner_score: Int,
    val events: List<AiEvent>,
)
@Serializable data class AiHistory(
    val sessions_last_7d: Int, val avg_score_7d: Int?, val harsh_brake_7d: Int, val harsh_accel_7d: Int,
    val lane_no_signal_7d: Int, val signaled_rate_7d: Float?, val harsh_brake_prev_7d: Int,
    /** 그 전 7일의 유효 주행 수. 0 이면 harsh_brake_prev_7d 의 0 은 "급제동 0회"가 아니라 "비교할 주행 없음"이다 */
    val sessions_prev_7d: Int,
    val streak_days_no_event: Int, val best_score_30d: Int?,
    /** 최근 7일 100km 당 횟수. 거리가 모자라면 null (DrivePatterns) */
    val per100km_7d: AiRates? = null,
    /** 지난주 대비, 항목마다 "늘었음 (…)"·"줄었음 (…, 좋아짐)"·"같음 (…)"·"비교 없음". 서버는 이 말만 쓴다 */
    val trends: Map<String, String> = emptyMap(),
    /** 바로 전 주행 대비, 항목마다 trends 와 같은 말. 전 주행이 없거나 거리가 많이 다르면 "비교 없음" */
    val vs_last_drive: Map<String, String> = emptyMap(),
    /** 앱이 규칙으로 찾은 패턴 문장(시간대 쏠림, 반복 지점 수 등). 장소 이름·좌표는 없다 */
    val patterns: List<String> = emptyList(),
    /** 최근 30일 반복 지점 수 */
    val hotspots_30d: Int = 0,
)
@Serializable data class AiRates(val harsh_brake: Float, val harsh_accel: Float, val lane_no_signal: Float)
@Serializable data class AiVehicle(val ev: Boolean, val soc_pct: Int?, val charging: Boolean?, val tire_warning: Boolean?)
/** 습관 (HabitRanker). 리포트가 "요즘 급제동이 잦다" 같은 말을 데이터로 하게 */
@Serializable data class AiHabit(val condition: String, val daily_avg: Float, val target: Float, val note: String)
@Serializable data class AiHabits(val improve: AiHabit?, val keep: AiHabit?)
/** 어제 개인 미션 결과: SUCCEEDED / FAILED / UNDETERMINED / CANCELLED / EXPIRED */
@Serializable data class AiMissionOutcome(val title: String, val condition: String, val target: Float, val result: String)
/** 오늘 개인 미션(제공됨·도전 중·판정 끝)과 상태. 리포트가 "오늘 '급제동 1회 이하' 도전해 봐" 하고 권할 수 있게 */
@Serializable data class AiTodayMission(val title: String, val condition: String, val target: Float, val status: String)
@Serializable data class AiMissionContext(
    /** 서버에 만들어 달라는 미션 수. 미션은 앱이 정하므로 0 */
    val request_count: Int = 0,
    val habits: AiHabits,
    val yesterday: List<AiMissionOutcome>,
    val today: List<AiTodayMission>,
)
@Serializable data class DriveEndPayload(val character: AiCharacter, val session: AiSession, val history: AiHistory, val vehicle: AiVehicle, val missions: AiMissionContext)

/** 서버 응답. missions 는 request_count 0 이라 비어 온다 */
@Serializable data class DriveEndResponse(val report: String? = null, val tip: String? = null, val panels: List<String>? = null)

/** 주행 결과 화면 종합 분석과 다음 주행 팁 (11/14). 팁이 없으면 화면은 규칙 팁을 쓴다 */
data class AiReport(val report: String, val tip: String?, val panels: List<String>? = null) {
    /**
     * 대사 속 장소 빈칸 [PLACE] 을 도로 이름으로 채운다. 서버·Claude 는 장소를 모르고(좌표·도로명을 보내지 않는다),
     * 이 기기 안에서만 채운다. 도로 이름이 없으면 "그곳" (11/15)
     */
    fun withPlace(place: String?): AiReport {
        val p = place?.takeIf { it.isNotBlank() } ?: PLACE_FALLBACK
        fun fill(t: String) = t.replace(PLACE, p)
        return AiReport(fill(report), tip?.let(::fill), panels?.map(::fill))
    }

    companion object {
        const val PLACE = "{장소}"
        const val PLACE_FALLBACK = "그곳"

        /** 감점 행동이 가장 많이 난 도로. 같으면 먼저 난 곳 */
        fun placeOf(r: SessionRecord): String? = r.events
            .filter { it.event.type in SessionRecord.DEDUCTED }
            .mapNotNull { it.roadName?.takeIf { n -> n.isNotBlank() } }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

/** 주행 결과 만화 칸 수 (11/15 3컷 → 4컷) */
const val COMIC_PANELS = 4

object AiJson {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    fun encode(p: DriveEndPayload): String = json.encodeToString(DriveEndPayload.serializer(), p)
    /** 응답에서 리포트 문장. 파싱 실패·빈 문장이면 null (호출자가 규칙 문장을 쓴다) */
    fun parseReport(text: String): String? = parseAnalysis(text)?.report

    /** 응답에서 종합 분석과 팁. 분석이 없으면 null, 팁은 없거나 비면 null */
    fun parseAnalysis(text: String): AiReport? {
        val r = runCatching { json.decodeFromString(DriveEndResponse.serializer(), text) }.getOrNull() ?: return null
        val report = r.report?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val panels = r.panels?.map { it.trim() }?.takeIf { p -> p.size == COMIC_PANELS && p.all { it.isNotBlank() } }
        return AiReport(report, r.tip?.trim()?.takeIf { it.isNotBlank() }, panels)
    }
}

class AiPayloadBuilder(
    private val sessions: SessionStore,
    private val missions: MissionStore,
    private val tzOffsetMs: Long = Time.KST_OFFSET_MS,
) {
    private val zone = ZoneOffset.ofTotalSeconds((tzOffsetMs / 1000).toInt())
    private val weekdays = listOf("월", "화", "수", "목", "금", "토", "일")
    private val KIND = mapOf(
        EventType.HARSH_BRAKE to "급제동", EventType.HARSH_ACCEL to "급가속",
        EventType.LANE_CHANGE_NO_SIGNAL to "깜빡이 없는 차선변경", EventType.LANE_CHANGE_WRONG_SIGNAL to "반대 깜빡이 차선변경",
    )

    fun weekday(ms: Long): String = weekdays[Math.floorMod(Time.dayIndex(ms, tzOffsetMs) + 3, 7L).toInt()]
    private fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).hour

    /** 시간대. 정확한 시각 대신 보낸다 */
    fun timeOfDay(ms: Long): String = when (hour(ms)) {
        in 5..10 -> "아침"
        in 11..16 -> "낮"
        in 17..20 -> "저녁"
        in 21..23 -> "밤"
        else -> "새벽"
    }

    /** 출근길·퇴근길·주말 외출 추정. 요일과 시각만 본다 */
    fun tripLabel(startMs: Long): String {
        val wd = weekday(startMs); val h = hour(startMs)
        return when {
            wd in listOf("토", "일") -> "주말 외출(추정)"
            h in 6..9 -> "출근길(추정)"
            h in 17..20 -> "퇴근길(추정)"
            else -> "주행"
        }
    }

    /** 방금 끝난 주행 [r] 의 리포트 입력. [r] 은 저장돼 있어도, 아니어도 된다(7일 집계에 한 번만 들어간다) */
    fun driveEnd(r: SessionRecord, character: AiCharacter, status: VehicleStatus, nowMs: Long): DriveEndPayload {
        val p = r.profileId
        val day0 = Time.dayStart(nowMs, tzOffsetMs)
        val d7 = day0 - 6 * Time.DAY_MS
        val last7 = sessions.list(p, d7, nowMs).filter { it.valid }.let { if (it.none { x -> x.id == r.id } && r.valid) it + r else it }
        val prev7 = sessions.list(p, d7 - 7 * Time.DAY_MS, d7 - 1).filter { it.valid }
        val last30 = sessions.list(p, day0 - 29 * Time.DAY_MS, nowMs).filter { it.valid }.let { if (it.none { x -> x.id == r.id } && r.valid) it + r else it }
        val spots = Hotspots.find(last30)
        val rates = DrivePatterns.per100km(last7)
        val patterns = buildList {
            DrivePatterns.timeConcentration(last7, ::timeOfDay)?.let(::add)
            spots.firstOrNull()?.let { s ->
                val kinds = s.types.entries.sortedByDescending { it.value }.joinToString(", ") { "${KIND[it.key] ?: it.key.name} ${it.value}번" }
                add("최근 30일 같은 곳(반경 200m)에서 다른 주행 2번 이상 놀란 곳이 ${spots.size}군데. 가장 잦은 곳은 주행 ${s.drives}번에 걸쳐 $kinds")
            }
        }
        fun cnt(rs: List<SessionRecord>, t: EventType) = rs.sumOf { it.count(t) }
        val lane = last7.sumOf { it.laneChanges }
        val history = AiHistory(
            sessions_last_7d = last7.size,
            avg_score_7d = last7.map { it.score }.takeIf { it.isNotEmpty() }?.let { Scoring.roundHalfUp(it.average().toFloat()) },
            harsh_brake_7d = cnt(last7, EventType.HARSH_BRAKE), harsh_accel_7d = cnt(last7, EventType.HARSH_ACCEL),
            lane_no_signal_7d = cnt(last7, EventType.LANE_CHANGE_NO_SIGNAL) + cnt(last7, EventType.LANE_CHANGE_WRONG_SIGNAL),
            signaled_rate_7d = if (lane == 0) null else cnt(last7, EventType.LANE_CHANGE_SIGNALED).toFloat() / lane,
            harsh_brake_prev_7d = cnt(prev7, EventType.HARSH_BRAKE),
            sessions_prev_7d = prev7.size,
            streak_days_no_event = ConditionRules.streakDays(last7, tzOffsetMs),
            best_score_30d = last30.maxOfOrNull { it.score },
            per100km_7d = rates?.let { AiRates(it.harsh_brake, it.harsh_accel, it.lane_no_signal) },
            trends = DrivePatterns.trends(last7, prev7),
            vs_last_drive = DrivePatterns.vsLastDrive(r, last30.filter { it.id != r.id && it.startWallMs < r.startWallMs }.maxByOrNull { it.startWallMs }),
            patterns = patterns,
            hotspots_30d = spots.size,
        )
        val session = AiSession(
            weekday = weekday(r.startWallMs), time_of_day = timeOfDay(r.startWallMs),
            distance_km = Math.round(r.distanceKm), duration_min = ((r.endWallMs - r.startWallMs) / 60_000L).toInt(), trip_label = tripLabel(r.startWallMs),
            score = r.score, accel_score = r.result.accelScore, brake_score = r.result.brakeScore, manner_score = r.result.mannerScore,
            events = r.events.map { e ->
                AiEvent(
                    e.event.type.name, ((e.event.startMs - r.result.startMs) / 60_000L).toInt().coerceAtLeast(0), e.event.severity, Math.round(e.event.speedMps * 3.6f),
                    at_hotspot = e.event.type in SessionRecord.DEDUCTED && Hotspots.near(spots, e.lat, e.lng),
                )
            },
        )
        val habits = HabitRanker.rank(last7, emptyList(), tzOffsetMs)
        fun h(x: Habit?) = x?.let { AiHabit(it.condition.name, it.dailyAvg, it.target, it.note) }
        val personal = missions.list(p).filter { it.kind == MissionKind.PERSONAL }
        val yesterday = personal.filter { it.validUntilMs == day0 && it.status != MissionStatus.OFFERED && it.status != MissionStatus.IN_PROGRESS }
            .map { AiMissionOutcome(it.title, it.condition.name, it.target, it.status.name) }
        val today = personal.filter { it.validUntilMs == day0 + Time.DAY_MS }.map { AiTodayMission(it.title, it.condition.name, it.target, it.status.name) }
        return DriveEndPayload(
            character, session, history,
            AiVehicle(status.evSocPct != null, status.evSocPct, status.charging, status.tireWarning),
            AiMissionContext(0, AiHabits(h(habits.improve), h(habits.keep)), yesterday, today),
        )
    }
}

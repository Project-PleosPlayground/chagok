package ai.safetypoc.app.ui.report

import core.ai.Hotspot
import core.ai.Hotspots
import core.character.GrowthModule
import core.flow.SessionEndSummary
import core.session.SessionRecord
import core.session.SessionStore
import engine.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 세부 점수 하나 */
enum class SubScore { ACCEL, BRAKE, MANNER }

/** 세부 점수와 그 옆에 붙는 이벤트 횟수 (02 "세션 리포트": "급제동 2회") */
data class SubScoreLine(val kind: SubScore, val score: Int, val events: List<EventCount>)

data class EventCount(val type: EventType, val count: Int)

/** 세션 하나의 점수 묶음. 유효하지 않은 세션은 [valid] 가 false 이고 점수가 0 이다 */
data class SessionScores(
    val valid: Boolean,
    val score: Int,
    val distanceKm: Float,
    val durationMs: Long,
    /** 주행이 끝난 시각(wall-clock). 주행 결과 화면의 "도착" 시각 */
    val endWallMs: Long,
    val lines: List<SubScoreLine>,
)

/** 성공한 미션 한 건 */
data class CompletedMission(val title: String, val coins: Int, val xp: Int)

/** 주행 종료 화면이 보여 줄 값 */
data class DriveEndState(
    val session: SessionScores,
    val completed: List<CompletedMission>,
    val coinsGained: Int,
    val xpGained: Int,
    /** 레벨이 올랐으면 오른 레벨 */
    val leveledUpTo: Int?,
    val points: Int,
    val comment: String,
    /** AI 리포트를 켤지 이 화면에서 한 번 묻는다 (#101). 설정의 askAiOnDriveEnd */
    val askAiConsent: Boolean = false,
    /** [comment] 가 AI 리포트인지. 맞을 때만 "AI 분석" 표시를 붙인다 */
    val aiGenerated: Boolean,
    /** 다음 주행 팁. AI 팁, 없으면 규칙 팁(ruleTip). null 이면 tip_box 를 그리지 않는다 */
    val tip: String? = null,
    /** 종합 분석 3컷 만화 (11/14) */
    val comic: List<ComicPanel> = emptyList(),
    /** 주행 요약 네 칸 (영역사양_주행결과화면 5-7) */
    val summary: List<SummaryLine>,
    /** 받은 경험치 칩. 받은 것이 없으면 null */
    val expReward: ExpReward?,
    /** 이번 주행에서 받은 장소 선물. 있으면 결과 화면에 선물 팝업 (11/15) */
    val placeGifts: List<core.flow.PlaceGift> = emptyList(),
)

/** 감점 행동이 난 곳 하나 (지도 핀). 좌표는 기기 안에서만 쓴다 */
data class EventPlace(val lat: Double, val lng: Double, val type: EventType, val atWallMs: Long)

/**
 * 리포트 화면이 보여 줄 값. 유효 주행 기록이 없으면 [session] 이 null.
 * [hotspots] 는 최근 30일 자주 놀라는 곳(core Hotspots: 반경 200m, 서로 다른 주행 2번 이상), [places] 는 같은 기간 위치가 있는 감점 행동 전부.
 */
data class ReportState(
    val session: SessionScores? = null,
    val hotspots: List<Hotspot> = emptyList(),
    val places: List<EventPlace> = emptyList(),
)

/** 주행 종료 화면의 동작 */
interface DriveEndActions {
    /** AI 리포트를 켤지 물은 데 대한 답. 켜면 방금 주행의 한마디도 받아 온다 */
    fun answerAiConsent(on: Boolean) = Unit
}

/**
 * 세부 점수마다 옆에 붙이는 이벤트 (스펙 5-2). 깜빡이 켠 차선변경은 감점이 아니라 넣지 않는다.
 * WEAVING_EST 는 매너 감점에 들어가지만 엔진이 내지 않는 이벤트라 "0회" 로 보이지 않게 뺀다.
 */
val SUB_SCORE_EVENTS: Map<SubScore, List<EventType>> = mapOf(
    SubScore.ACCEL to listOf(EventType.HARSH_ACCEL),
    SubScore.BRAKE to listOf(EventType.HARSH_BRAKE),
    SubScore.MANNER to listOf(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL),
)

fun SessionRecord.scores(): SessionScores {
    val r = result
    val sub = mapOf(SubScore.ACCEL to r.accelScore, SubScore.BRAKE to r.brakeScore, SubScore.MANNER to r.mannerScore)
    return SessionScores(
        valid = valid, score = score, distanceKm = distanceKm, durationMs = endWallMs - startWallMs, endWallMs = endWallMs,
        lines = SubScore.entries.map { k ->
            SubScoreLine(k, sub.getValue(k), SUB_SCORE_EVENTS.getValue(k).map { EventCount(it, count(it)) })
        },
    )
}

/** [growth] 는 경험치 칩의 % 를 단계 막대 기준으로 계산하는 데 쓴다 */
fun SessionEndSummary.toDriveEnd(growth: GrowthModule) = DriveEndState(
    session = record.scores(),
    completed = completed.map { CompletedMission(it.instance.title, it.coins, it.xp) },
    coinsGained = coinsGained, xpGained = xpGained, leveledUpTo = leveledUpTo,
    points = profile.coins, comment = comment, aiGenerated = aiGenerated,
    tip = tip ?: record.ruleTip(),
    comic = record.comicPanels(completed.size, comic),
    summary = record.summaryLines(),
    expReward = expReward(growth, xpGained, character?.xp, leveledUpTo),
    placeGifts = placeGifts,
)

/** 리포트: 마지막 유효 주행 기록으로 [ReportState] 를 만든다. 짧은(유효하지 않은) 주행은 넣지 않는다. 리포트에 들어올 때 [refresh] 를 부른다 */
class ReportModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val sessions: SessionStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(ReportState())
    val state: StateFlow<ReportState> = _state.asStateFlow()

    fun refresh() {
        val all = sessions.list(profileId)
        val last30 = all.filter { it.valid && it.startWallMs >= now() - PLACES_DAYS * DAY_MS }
        _state.value = ReportState(
            session = all.lastOrNull { it.valid }?.scores(),
            hotspots = Hotspots.find(last30),
            places = last30.flatMap { r ->
                r.events.filter { it.event.type in SessionRecord.DEDUCTED && it.lat != null && it.lng != null }
                    .map { EventPlace(it.lat!!, it.lng!!, it.event.type, r.startWallMs + (it.event.startMs - r.result.startMs)) }
            },
        )
    }
}

/** 지도·자주 놀라는 곳에 넣을 기간 (core AiPayload 의 30일과 같다) */
private const val PLACES_DAYS = 30L
private const val DAY_MS = 86_400_000L

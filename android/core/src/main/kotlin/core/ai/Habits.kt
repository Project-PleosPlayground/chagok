package core.ai

import core.Time
import core.mission.ConditionRules
import core.mission.ConditionType
import core.mission.HarderTarget
import core.mission.MissionCatalog
import core.session.SessionRecord
import engine.EventType
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 최근 7일 주행에서 "어떤 습관을 고칠지·지킬지"를 규칙으로 정한다. AI 는 쓰지 않는다.
 * 결과는 [PersonalMissionPlanner] 가 개인 미션(조건·단계)으로 바꾸고, [AiPayloadBuilder] 가 리포트 입력(missions.habits)으로 넣는다.
 * 설계: 자료/AI_설계_리포트_미션_1109.md 2절.
 */

/** 습관 하나: 조건, 운전한 날 기준 하루 평균, 바라는 목표치(조건 단위: 횟수·비율 0~1), 근거 메모 */
data class Habit(val condition: ConditionType, val dailyAvg: Float, val target: Float, val note: String)
data class Habits(val improve: Habit?, val keep: Habit?)

object HabitRanker {
    /** 깜빡이 매칭이 이 비율 이상이면 "지킬 습관", 아래면 "고칠 습관" */
    const val SIGNAL_GOOD = 0.9f

    /**
     * - improve: 7일 운전한 날 기준 하루 평균이 가장 높은 나쁜 습관(급제동·급가속·깜빡이 없는 차선변경). 목표 = ceil(평균) − 1.
     *   셋 다 0이면 깜빡이 매칭이 [SIGNAL_GOOD] 미만일 때 깜빡이를 고칠 습관으로.
     * - keep: 깜빡이 매칭 ≥ [SIGNAL_GOOD] 이면 그 비율 유지, 아니면 7일 동안 0회였던 나쁜 습관 하나(목표 0).
     * - [exclude] 의 조건은 건너뛴다.
     */
    fun rank(last7: List<SessionRecord>, exclude: Collection<ConditionType> = emptyList(), tzOffsetMs: Long = Time.KST_OFFSET_MS): Habits {
        val valid = last7.filter { it.valid }
        val days = valid.map { Time.dayIndex(it.startWallMs, tzOffsetMs) }.distinct().size.coerceAtLeast(1)
        fun cnt(t: EventType) = valid.sumOf { it.count(t) }
        val counts = linkedMapOf(
            ConditionType.HARSH_BRAKE_MAX to cnt(EventType.HARSH_BRAKE),
            ConditionType.HARSH_ACCEL_MAX to cnt(EventType.HARSH_ACCEL),
            ConditionType.LANE_NO_SIGNAL_MAX to cnt(EventType.LANE_CHANGE_NO_SIGNAL) + cnt(EventType.LANE_CHANGE_WRONG_SIGNAL),
        )
        val lane = valid.sumOf { it.laneChanges }
        val rate: Float? = if (lane == 0) null else cnt(EventType.LANE_CHANGE_SIGNALED).toFloat() / lane

        val worst = counts.filter { it.value > 0 && it.key !in exclude }.maxByOrNull { it.value }
        val improve: Habit? = when {
            worst != null -> {
                val avg = worst.value.toFloat() / days
                Habit(worst.key, round1(avg), (ceil(avg) - 1).coerceAtLeast(0f), "7일 중 운전한 ${days}일, ${label(worst.key)} ${worst.value}회")
            }
            rate != null && rate < SIGNAL_GOOD && ConditionType.SIGNALED_RATE_MIN !in exclude ->
                Habit(ConditionType.SIGNALED_RATE_MIN, round1(rate), round1((floor(rate * 10) / 10f + 0.1f).coerceAtMost(1f)), "깜빡이 매칭 ${(rate * 100).roundToInt()}%")
            else -> null
        }
        val keep: Habit? = when {
            rate != null && rate >= SIGNAL_GOOD && ConditionType.SIGNALED_RATE_MIN !in exclude && improve?.condition != ConditionType.SIGNALED_RATE_MIN ->
                Habit(ConditionType.SIGNALED_RATE_MIN, round1(rate), (floor(rate * 10) / 10f).coerceIn(0.8f, 1f), "깜빡이 매칭 ${(rate * 100).roundToInt()}%")
            else -> counts.entries.firstOrNull { it.value == 0 && it.key !in exclude && it.key != improve?.condition }
                ?.let { Habit(it.key, 0f, 0f, "7일 동안 ${label(it.key)} 0회") }
        }
        return Habits(improve, keep)
    }

    /**
     * 바라는 목표치에 맞는 미션 단계. 조건 표([MissionCatalog])의 단계 목표치 중에서 고른다.
     * 어려워질수록 목표가 내려가는 조건(이하)은 "목표치 ≥ 바라는 값" 중 가장 어려운 단계, 올라가는 조건(이상)은 "목표치 ≤ 바라는 값" 중 가장 어려운 단계.
     * 맞는 단계가 없으면(바라는 값이 표 밖) 가장 쉬운 단계. 조건이 표에 없으면 null.
     * 비율 조건은 [Habit.target] 이 0~1 이고 표는 % 라서 여기서 맞춘다.
     */
    fun tierFor(catalog: MissionCatalog, condition: ConditionType, desired: Float): Int? {
        val row = catalog.row(condition) ?: return null
        val tiers = row.tiers.filter { catalog.reward(it) != null }.ifEmpty { return null }
        val want = if (condition == ConditionType.SIGNALED_RATE_MIN) desired * 100f else desired
        fun t(tier: Int) = row.target(tier)!!
        return when (ConditionRules.of(condition).harder) {
            HarderTarget.LOWER -> tiers.filter { t(it) >= want }.minByOrNull { t(it) } ?: tiers.maxByOrNull { t(it) }
            HarderTarget.HIGHER -> tiers.filter { t(it) <= want }.maxByOrNull { t(it) } ?: tiers.minByOrNull { t(it) }
            HarderTarget.NONE -> tiers.first()
        }
    }

    fun label(c: ConditionType) = when (c) {
        ConditionType.HARSH_BRAKE_MAX -> "급제동"; ConditionType.HARSH_ACCEL_MAX -> "급가속"; ConditionType.LANE_NO_SIGNAL_MAX -> "깜빡이 없는 차선변경"
        ConditionType.SIGNALED_RATE_MIN -> "깜빡이 매칭"; ConditionType.CLEAN_KM -> "감점 없는 거리"; ConditionType.SEATBELT_BEFORE_START -> "출발 전 벨트"
        ConditionType.CHARGE_STOP_AT_80 -> "충전 멈추기"; ConditionType.NO_BELOW_10_PCT -> "배터리 지키기"; ConditionType.STREAK_DAYS -> "감점 없는 날 연속"
    }

    private fun round1(x: Float) = (x * 10).roundToInt() / 10f
}

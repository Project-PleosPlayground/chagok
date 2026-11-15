package core.ai

import core.Time
import core.mission.ConditionRules
import core.mission.ConditionType
import core.mission.MissionCatalog
import core.mission.MissionEngine
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStore
import core.session.SessionStore
import kotlin.random.Random

/**
 * 개인 미션(하루 [core.mission.MissionRules.personalPerDay] 개)을 랜덤이 아니라 그 사람의 7일 습관으로 정해 제공한다.
 * 기획 07 "맞춤(AI) 미션은 개인 미션으로 제공한다"의 구현. 글은 조건 표의 이름 틀을 그대로 쓰고(AI 가 쓰지 않음), 조건·단계만 고른다.
 *
 * 쓰는 법: [MissionEngine.refresh] (또는 MissionActions.refresh, SessionEndPipeline.onSessionEnd) 를 부르기 **바로 전에** [offerToday] 를 부른다.
 * refresh 는 "오늘 개인 미션이 하나라도 있으면" 랜덤 제공을 건너뛰므로, 먼저 제공해 두면 그날 개인 미션은 전부 여기서 정한 것이 된다.
 * 순서가 바뀌어 refresh 가 먼저 돌았으면 그날은 랜덤 미션으로 가고 여기서는 아무것도 하지 않는다(중복 제공 없음).
 */
class PersonalMissionPlanner(
    private val engine: MissionEngine,
    private val missions: MissionStore,
    private val sessions: SessionStore,
    private val catalog: () -> MissionCatalog,
    private val tzOffsetMs: Long = Time.KST_OFFSET_MS,
    private val random: Random = Random.Default,
) {
    /** 7일 유효 주행이 이보다 적으면 습관을 말할 근거가 없다고 보고 랜덤 제공에 맡긴다 */
    val minSessions: Int = MIN_SESSIONS

    data class Plan(val improve: MissionInstance?, val keep: MissionInstance?, val filler: List<MissionInstance>) {
        val all: List<MissionInstance> get() = listOfNotNull(improve, keep) + filler
        companion object { val NONE = Plan(null, null, emptyList()) }
    }

    /** 오늘 유효한 개인 미션(제공됨·도전 중·판정 끝). 있으면 오늘은 이미 정해진 것 */
    fun todays(profileId: String, nowMs: Long): List<MissionInstance> {
        val end = engine.validUntil(MissionKind.PERSONAL, nowMs)
        return missions.list(profileId).filter { it.kind == MissionKind.PERSONAL && it.validUntilMs == end }
    }

    /** 최근 7일(오늘 포함) 유효 주행 */
    fun last7(profileId: String, nowMs: Long) =
        sessions.list(profileId, Time.dayStart(nowMs, tzOffsetMs) - 6 * Time.DAY_MS, nowMs).filter { it.valid }

    /** 7일 습관. 이미 오늘 나온 개인 미션의 조건은 뺀다 */
    fun habits(profileId: String, nowMs: Long): Habits =
        HabitRanker.rank(last7(profileId, nowMs), todays(profileId, nowMs).map { it.condition }, tzOffsetMs)

    /**
     * 오늘 개인 미션을 습관으로 정해 제공한다. 이미 오늘 개인 미션이 있거나 7일 유효 주행이 [minSessions] 미만이면 [Plan.NONE].
     * 1) 고칠 습관 → 그 평균보다 하나 적은 목표치에 맞는 단계, 2) 지킬 습관 → 지금 실적에 맞는 단계, 3) 모자라면 나머지 조건에서 랜덤.
     */
    fun offerToday(profileId: String, nowMs: Long): Plan {
        if (todays(profileId, nowMs).isNotEmpty()) return Plan.NONE
        val last7 = last7(profileId, nowMs)
        if (last7.size < minSessions) return Plan.NONE
        val cat = catalog()
        val n = cat.rules.personalPerDay.coerceAtLeast(0)
        if (n == 0) return Plan.NONE
        val h = HabitRanker.rank(last7, emptyList(), tzOffsetMs)
        val used = LinkedHashSet<ConditionType>()
        fun offer(habit: Habit?): MissionInstance? {
            val x = habit ?: return null
            if (used.size >= n || x.condition in used) return null
            val tier = HabitRanker.tierFor(cat, x.condition, x.target) ?: return null
            return engine.offer(profileId, MissionKind.PERSONAL, x.condition, tier, nowMs)?.also { used += x.condition }
        }
        val improve = offer(h.improve)
        val keep = offer(h.keep)
        val filler = cat.conditions
            .mapNotNull { row -> row.condition?.let { row to it } }
            .filter { (_, c) -> c !in used && MissionKind.PERSONAL in ConditionRules.of(c).kinds }
            .flatMap { (row, c) -> row.tiers.filter { cat.reward(it) != null }.map { c to it } }
            .shuffled(random)
            .distinctBy { it.first }
            .take(n - used.size)
            .mapNotNull { (c, tier) -> engine.offer(profileId, MissionKind.PERSONAL, c, tier, nowMs) }
        return Plan(improve, keep, filler)
    }

    companion object { const val MIN_SESSIONS = 3 }
}

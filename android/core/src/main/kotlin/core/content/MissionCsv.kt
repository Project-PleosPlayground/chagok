package core.content

import core.mission.ConditionRow
import core.mission.MissionCatalog
import core.mission.MissionRules
import core.mission.MissionText
import core.mission.TierReward

/**
 * 기획 스프레드시트의 미션 CSV 세 탭을 [MissionCatalog] 로 옮긴다. 컬럼은 기획/데이터_컬럼_사전.md 의
 * "미션(조합)", "미션 단계 보상", "콘텐츠 텍스트" 를 따른다. "메모용" 컬럼은 읽지 않는다.
 * 제공 개수·슬롯 같은 [MissionRules] 는 CSV 에 없어서 호출자가 넘긴다.
 * 콘텐츠 텍스트는 미션(조합)이 가리키는 키와 그 `키_zero` 만 가져온다.
 */
object MissionCsv {
    /** 탭 이름 = CSV 파일 이름 */
    const val CONDITIONS_FILE = "미션(조합).csv"
    const val REWARDS_FILE = "미션 단계 보상.csv"
    const val TEXTS_FILE = "콘텐츠 텍스트.csv"

    private const val CONDITION_ID = "조건ID"
    private const val NAME = "이름"
    private const val DESCRIPTION = "내용"
    private const val ICON = "아이콘"
    private const val TARGET_PREFIX = "목표치_"
    private const val TARGET_SUFFIX = "단계"
    private const val TIER = "단계"
    private const val REWARD_XP = "보상_경험치"
    private const val REWARD_POINTS = "보상_포인트"

    private val TARGETS = (1..ContentValidator.TIER_COUNT).map { "$TARGET_PREFIX$it$TARGET_SUFFIX" }

    /** 옮긴 결과. [problems] 가 비어 있지 않으면 [catalog] 는 null */
    class Result(val catalog: MissionCatalog?, val problems: List<String>)

    fun read(conditionsCsv: String, rewardsCsv: String, textsCsv: String, rules: MissionRules): Result {
        val reader = Csv.Reader()
        val cond = reader.table(CONDITIONS_FILE, conditionsCsv, listOf(CONDITION_ID, NAME, DESCRIPTION, ICON) + TARGETS)
        val rew = reader.table(REWARDS_FILE, rewardsCsv, listOf(TIER, REWARD_XP, REWARD_POINTS))
        val allTexts = reader.texts(TEXTS_FILE, textsCsv)
        if (reader.problems.isNotEmpty() || cond == null || rew == null || allTexts == null) return Result(null, reader.problems)

        val conditions = cond.rows.mapNotNull { r ->
            val id = reader.int(CONDITIONS_FILE, r, CONDITION_ID) ?: return@mapNotNull null
            ConditionRow(
                conditionId = id, nameKey = r[NAME], descriptionKey = r[DESCRIPTION],
                iconKey = r[ICON].ifEmpty { null },
                tierTargets = TARGETS.map { reader.number(CONDITIONS_FILE, r, it) }.dropLastWhile { it == null },
            )
        }
        val rewards = rew.rows.mapNotNull { r ->
            val tier = reader.int(REWARDS_FILE, r, TIER); val xp = reader.int(REWARDS_FILE, r, REWARD_XP); val points = reader.int(REWARDS_FILE, r, REWARD_POINTS)
            if (tier == null || xp == null || points == null) null else TierReward(tier, coins = points, xp = xp)
        }
        val used = conditions.flatMap { listOf(it.nameKey, it.descriptionKey) }.flatMap { listOf(it, it + MissionText.ZERO_SUFFIX) }.toSet()
        val texts = allTexts.filterKeys { it in used }
        val problems = reader.problems
        if (problems.isNotEmpty()) return Result(null, problems)
        return Result(MissionCatalog(conditions, rewards, rules, texts), emptyList())
    }
}

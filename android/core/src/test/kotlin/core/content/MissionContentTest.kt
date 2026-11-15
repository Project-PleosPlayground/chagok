package core.content

import core.mission.ConditionRow
import core.mission.ConditionType
import core.mission.MissionCatalog
import core.mission.MissionText
import core.mission.TierReward
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MissionContentTest {
    private val bundled = BundledContent.pack
    private val cat = bundled.missions
    private val brake = cat.row(ConditionType.HARSH_BRAKE_MAX)!!
    private fun problems(c: MissionCatalog) = ContentValidator.problems(bundled.copy(missions = c))
    private fun withRow(f: (ConditionRow) -> ConditionRow) =
        cat.copy(conditions = cat.conditions.map { if (it.condition == ConditionType.HARSH_BRAKE_MAX) f(it) else it })

    @Test fun `내장 콘텐츠는 검사를 통과하고 모든 조건이 조건 번호표 번호로 있다`() {
        assertEquals(emptyList<String>(), ContentValidator.problems(bundled))
        assertEquals(ConditionType.entries.toSet(), cat.conditions.mapNotNull { it.condition }.toSet())
        assertEquals((1..9).toList(), ConditionType.entries.map { it.id })
    }

    @Test fun `미션 데이터는 JSON 으로 오가도 같다`() {
        val p = bundled.copy(missions = withRow { it.copy(iconKey = "brake_01") })
        assertEquals(p, ContentCodec.decode(ContentCodec.encode(p)))
    }

    @Test fun `조건ID 가 겹치거나 번호표에 없으면 문제`() {
        assertTrue(problems(cat.copy(conditions = cat.conditions + brake)).any { "조건ID 중복" in it })
        assertTrue(problems(cat.copy(conditions = cat.conditions + brake.copy(conditionId = 99))).any { "번호표에 없는" in it })
    }

    @Test fun `빈 단계는 없는 단계다`() {
        val belt = cat.row(ConditionType.SEATBELT_BEFORE_START)!!
        assertEquals(listOf(1), belt.tiers)
        assertNull(belt.target(2))
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(3f, 2f, null, null)) }).isEmpty())
    }

    @Test fun `단계가 4칸보다 많거나 목표치가 있는 단계가 없으면 문제`() {
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(4f, 3f, 2f, 1f, 0f)) }).any { "4개보다 많다" in it })
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(null, null, null, null)) }).any { "목표치가 있는 단계가 없다" in it })
    }

    @Test fun `단계가 오를수록 어려워지지 않으면 문제`() {
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(0f, 1f, 2f, 3f)) }).any { "어려워지지 않는다" in it })
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(2f, 2f, 1f, 1f)) }).isEmpty(), "같은 값은 허용")
    }

    @Test fun `퍼센트 목표가 100 을 넘거나 목표가 음수면 문제`() {
        val rate = cat.copy(conditions = cat.conditions.map { if (it.condition == ConditionType.SIGNALED_RATE_MIN) it.copy(tierTargets = listOf(80f, 90f, 100f, 110f)) else it })
        assertTrue(problems(rate).any { "100" in it })
        assertTrue(problems(withRow { it.copy(tierTargets = listOf(3f, 2f, 1f, -1f)) }).any { "음수" in it })
    }

    @Test fun `바로 성공하는 0 목표나 정수여야 하는 목표의 소수는 문제`() {
        fun with(c: ConditionType, vararg t: Float?) = cat.copy(conditions = cat.conditions.map { if (it.condition == c) it.copy(tierTargets = t.toList()) else it })
        for (c in listOf(ConditionType.CLEAN_KM, ConditionType.SIGNALED_RATE_MIN, ConditionType.NO_BELOW_10_PCT, ConditionType.STREAK_DAYS))
            assertTrue(problems(with(c, 0f)).any { "목표치가 0 이다" in it || "1 이상 정수" in it }, "$c")
        assertTrue(problems(with(ConditionType.STREAK_DAYS, 2.5f)).any { "1 이상 정수" in it })
        assertTrue(problems(with(ConditionType.HARSH_BRAKE_MAX, 1.5f)).any { "정수가 아니다" in it })
        assertTrue(problems(with(ConditionType.HARSH_BRAKE_MAX, 0f)).isEmpty(), "횟수 0 은 허용 (없이)")
        assertTrue(problems(with(ConditionType.SEATBELT_BEFORE_START, 0f)).isEmpty(), "안전벨트는 목표치를 쓰지 않는다")
    }

    @Test fun `목표치를 넣은 이름이 15자, 설명이 30자를 넘으면 문제`() {
        assertTrue(problems(cat.copy(texts = cat.texts + (brake.nameKey to "아주 긴 급제동 미션 이름 {n}회 이하"))).any { "15자" in it })
        assertTrue(problems(cat.copy(texts = cat.texts + (brake.descriptionKey to "급제동을 {n}회 이하로 해서 오늘도 안전하게 집까지 잘 도착하기"))).any { "30자" in it })
    }

    @Test fun `문구가 없는 키, 규칙에 맞지 않는 키·아이콘은 문제`() {
        assertTrue(problems(withRow { it.copy(nameKey = "mission_nope") }).any { "콘텐츠 텍스트에 없는 키" in it })
        assertTrue(problems(cat.copy(texts = cat.texts + ("Mission Bad" to "x"))).any { "키가 규칙" in it })
        assertTrue(problems(withRow { it.copy(iconKey = "Brake 01.png") }).any { "아이콘" in it })
    }

    @Test fun `목표치가 0이면 키_zero 문구를 쓰고, 없으면 기본 문구에 0을 넣는다`() {
        assertEquals("급제동 없이", cat.name(brake, 0f))
        assertEquals("급제동 1회 이하", cat.name(brake, 1f))
        val noZero = cat.copy(texts = cat.texts - (brake.nameKey + MissionText.ZERO_SUFFIX))
        assertEquals("급제동 0회 이하", noZero.name(brake, 0f))
    }

    @Test fun `쓰는 단계의 단계 보상이 없거나, 보상 단계가 겹치거나 음수면 문제`() {
        assertTrue(problems(cat.copy(tierRewards = cat.tierRewards.dropLast(1))).any { "단계 보상이 없다" in it })
        assertTrue(problems(cat.copy(tierRewards = cat.tierRewards + TierReward(2, 1, 1))).any { "단계 중복" in it })
        assertTrue(problems(cat.copy(tierRewards = cat.tierRewards.map { if (it.tier == 1) it.copy(coins = -1) else it })).any { "음수" in it })
    }

    @Test fun `슬롯이 0칸이거나 제공 개수가 음수면 문제`() {
        assertTrue(problems(cat.copy(rules = cat.rules.copy(slotCount = 0))).any { "슬롯" in it })
        assertTrue(problems(cat.copy(rules = cat.rules.copy(personalPerDay = -1))).any { "개인 미션 수" in it })
    }

    @Test fun `개인 미션을 줘야 하는데 만들 수 있는 조건이 없으면 문제`() {
        val onlyStreak = cat.copy(conditions = cat.conditions.filter { it.condition == ConditionType.STREAK_DAYS })
        assertTrue(problems(onlyStreak).any { "PERSONAL 미션을 만들 수 있는 조건이 없다" in it })
    }
}

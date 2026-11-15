package core.content

import core.mission.ConditionType
import core.mission.TierReward
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MissionCsvTest {
    private val rules = BundledContent.pack.missions.rules
    private val conditions = """
        조건ID,조건,이름,내용,아이콘,목표치_1단계,목표치_2단계,목표치_3단계,목표치_4단계
        1,급제동 이하,mission_brake_name,mission_brake_desc,brake_icon,3,2,1,0
        6,출발 전 안전벨트,mission_seatbelt_name,mission_seatbelt_desc,,0,,,
    """.trimIndent()
    private val rewards = """
        단계,보상_경험치,보상_포인트
        1,5,20
        2,10,40
        3,15,60
        4,20,80
    """.trimIndent()
    private val texts = """
        키,문구,메모
        mission_brake_name,급제동 {n}회 이하,
        mission_brake_name_zero,급제동 없이,목표치 0일 때
        mission_brake_desc,"급제동을 {n}회 이하로, 운전하기",쉼표가 든 칸
        mission_seatbelt_name,출발 전 안전벨트,
        mission_seatbelt_desc,출발하기 전에 안전벨트 매기,
        item_1_name,현무 Lv1,미션이 아님
    """.trimIndent()

    @Test fun `데이터 컬럼 사전 형식의 CSV 를 미션 데이터로 옮긴다`() {
        val r = MissionCsv.read(conditions, rewards, texts, rules)
        assertEquals(emptyList<String>(), r.problems)
        val c = r.catalog!!
        val brake = c.row(ConditionType.HARSH_BRAKE_MAX)!!
        assertEquals(listOf(3f, 2f, 1f, 0f), brake.tierTargets); assertEquals("brake_icon", brake.iconKey)
        val belt = c.row(ConditionType.SEATBELT_BEFORE_START)!!
        assertEquals(listOf(1), belt.tiers); assertNull(belt.iconKey)
        assertEquals(TierReward(3, coins = 60, xp = 15), c.reward(3))
        assertEquals("급제동 없이", c.name(brake, 0f))
        assertEquals("급제동을 1회 이하로, 운전하기", c.description(brake, 1f))
        assertFalse("item_1_name" in c.texts, "미션이 가리키지 않는 문구는 가져오지 않는다")
        assertEquals(rules, c.rules)
    }

    @Test fun `BOM 과 CRLF, 빈 줄이 있어도 읽는다`() {
        val withBom = "﻿" + conditions.replace("\n", "\r\n") + "\r\n\r\n"
        assertEquals(MissionCsv.read(conditions, rewards, texts, rules).catalog, MissionCsv.read(withBom, rewards, texts, rules).catalog)
    }

    @Test fun `컬럼이 없거나 숫자가 아니면 줄 번호와 함께 알려 준다`() {
        val noIcon = MissionCsv.read(conditions.replace(",아이콘,", ",그림,"), rewards, texts, rules)
        assertNull(noIcon.catalog); assertTrue(noIcon.problems.any { "컬럼이 없다: 아이콘" in it })
        val badNumber = MissionCsv.read(conditions.replace("3,2,1,0", "3,둘,1,0"), rewards, texts, rules)
        assertTrue(badNumber.problems.any { "2행" in it && "목표치_2단계" in it }, "${badNumber.problems}")
        val badTier = MissionCsv.read(conditions, rewards.replace("3,15,60", "셋,15,60"), texts, rules)
        assertTrue(badTier.problems.any { MissionCsv.REWARDS_FILE in it && "4행" in it }, "${badTier.problems}")
    }

    @Test fun `엑셀이 남기는 쉼표만 있는 줄은 빈 줄로 본다`() {
        val withEmptyRows = conditions + "\n,,,,,,,,\n , , ,,,,,,"
        val r = MissionCsv.read(withEmptyRows, rewards + "\n,,", texts, rules)
        assertEquals(emptyList<String>(), r.problems)
        assertEquals(MissionCsv.read(conditions, rewards, texts, rules).catalog, r.catalog)
    }

    @Test fun `콘텐츠 텍스트 키가 겹치면 문제`() {
        val dup = MissionCsv.read(conditions, rewards, texts + "\nmission_brake_name,다른 문구,", rules)
        assertTrue(dup.problems.any { "키가 겹친다: mission_brake_name" in it })
    }

    @Test fun `CSV 형식이 깨지면 문제`() {
        assertTrue(MissionCsv.read(conditions, rewards, "키,문구\n\"닫히지 않음,x", rules).problems.any { "따옴표" in it })
        assertTrue(MissionCsv.read(conditions + ",남는칸,또", rewards, texts, rules).problems.any { "칸이 컬럼" in it })
        assertTrue(MissionCsv.read("", rewards, texts, rules).problems.any { "컬럼명 행이 없다" in it })
    }

    @Test fun `따옴표 안의 따옴표와 줄바꿈을 읽는다`() {
        val t = Csv.parse("키,문구\na,\"말 \"\"인용\"\"\n둘째 줄\"\nb,끝")
        assertEquals("말 \"인용\"\n둘째 줄", t.rows[0]["문구"]); assertEquals("끝", t.rows[1]["문구"])
        assertEquals(4, t.rows[1].line)
    }

    @Test fun `내장 데이터는 같은 형식의 CSV 에서 만든 것과 같다`() {
        val cat = BundledContent.pack.missions
        val csv = buildString {
            appendLine("조건ID,조건,이름,내용,아이콘,목표치_1단계,목표치_2단계,목표치_3단계,목표치_4단계")
            for (r in cat.conditions) appendLine(listOf(r.conditionId, "", r.nameKey, r.descriptionKey, r.iconKey.orEmpty()).joinToString(",") + "," +
                (0 until ContentValidator.TIER_COUNT).joinToString(",") { r.tierTargets.getOrNull(it)?.let(core.mission.MissionEngine::number).orEmpty() })
        }
        val rew = "단계,보상_경험치,보상_포인트\n" + cat.tierRewards.joinToString("\n") { "${it.tier},${it.xp},${it.coins}" }
        val txt = "키,문구\n" + cat.texts.entries.joinToString("\n") { "${it.key},${it.value}" }
        assertEquals(cat, MissionCsv.read(csv, rew, txt, cat.rules).catalog)
    }

    @Test fun `bundled 쓰기 모양은 읽으면 원래 JSON 과 같고 정수는 소수점 없이 쓴다`() {
        val text = BundledContent::class.java.getResourceAsStream(BundledContent.RESOURCE_PATH)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val json = kotlinx.serialization.json.Json.parseToJsonElement(text)
        val written = BundledJsonFormat.format(json)
        assertEquals(json, kotlinx.serialization.json.Json.parseToJsonElement(written))
        assertTrue("\"tierTargets\": [3, 2, 1, 0]" in written)
        assertEquals(BundledContent.pack, ContentCodec.decode(written))
    }
}

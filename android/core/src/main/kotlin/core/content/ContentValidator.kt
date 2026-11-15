package core.content

import core.item.ItemImage
import core.mission.ConditionRules
import core.mission.HarderTarget
import core.mission.TargetForm
import core.mission.MissionCatalog
import core.mission.MissionEngine
import core.mission.MissionKind
import engine.EngineTuning

/**
 * 형식은 맞지만 값이 이상한 묶음을 걸러낸다. 여기 규칙은 조정할 수치가 아니라 앱이 깨지지 않기 위한 구조 규칙이다.
 * 어긋나는 항목을 모두 모아 돌려준다(로그용). 빈 목록이면 통과.
 */
object ContentValidator {
    /** 안전점수의 가장 낮은 값. 감점식 100점 만점(스펙 1절)이라 0. 어떤 점수든 구간 하나에는 들어가야 한다 */
    private const val LOWEST_SCORE = 0
    /** 아이템·캐릭터 종류 번호는 1부터 센다 (#48) */
    private const val FIRST_ID = 1
    /** 캐릭터 그림 폴더·미션 아이콘 이름 규칙: 영문 소문자, 숫자, 밑줄 (06 "폴더 구조"). 아이템 이미지는 ItemImage */
    private val ASSET_KEY = Regex("[a-z0-9_]+")
    /** 미션 단계 칸 수: 목표치_1단계 ~ 목표치_4단계 (기획/데이터_컬럼_사전.md "미션(조합)") */
    const val TIER_COUNT = 4
    /** 미션 이름·설명 글자 수 상한. 띄어쓰기 포함 (07 "미션 데이터") */
    const val MISSION_NAME_MAX = 15
    const val MISSION_DESCRIPTION_MAX = 30
    /** 콘텐츠 텍스트 키 규칙: 영문 소문자, 숫자, 밑줄 (데이터 컬럼 사전 "콘텐츠 텍스트") */
    private val TEXT_KEY = Regex("[a-z0-9_]+")

    private fun missionProblems(m: MissionCatalog): List<String> = buildList {
        if (m.conditions.isEmpty()) add("미션 조건이 하나도 없다")
        duplicates(m.conditions.map { it.conditionId.toString() }).forEach { add("조건ID 중복: $it") }
        for (key in m.texts.keys) if (!TEXT_KEY.matches(key)) add("콘텐츠 텍스트 키가 규칙(영문 소문자·숫자·밑줄)에 맞지 않는다: $key")
        for (row in m.conditions) {
            val c = row.condition
            if (c == null) { add("조건 번호표에 없는 조건ID: ${row.conditionId}"); continue }
            val at = "조건 ${row.conditionId}($c)"
            val rule = ConditionRules.of(c)
            for (key in listOf(row.nameKey, row.descriptionKey))
                if (key.isBlank() || m.texts[key].isNullOrBlank()) add("$at: 콘텐츠 텍스트에 없는 키: '$key'")
            row.iconKey?.let { if (!ASSET_KEY.matches(it)) add("$at: 아이콘 이름이 규칙(영문 소문자·숫자·밑줄)에 맞지 않는다: $it") }
            if (row.tierTargets.size > TIER_COUNT) add("$at: 단계 목표치가 ${TIER_COUNT}개보다 많다: ${row.tierTargets.size}개")
            if (row.tiers.isEmpty()) add("$at: 목표치가 있는 단계가 없다")
            for (tier in row.tiers) {
                val t = row.target(tier)!!
                if (t.isNaN() || t.isInfinite() || t < 0f) { add("$at: ${tier}단계 목표치가 음수거나 숫자가 아니다: $t"); continue }
                rule.targetMax?.let { if (t > it) add("$at: ${tier}단계 목표치가 ${MissionEngine.number(it)} 을 넘는다: ${MissionEngine.number(t)}") }
                val whole = t == kotlin.math.floor(t)
                when (rule.targetForm) {
                    TargetForm.WHOLE_FROM_ZERO -> if (!whole) add("$at: ${tier}단계 목표치가 정수가 아니다: ${MissionEngine.number(t)}")
                    TargetForm.POSITIVE -> if (t <= 0f) add("$at: ${tier}단계 목표치가 0 이다(유효 주행 한 번에 바로 성공)")
                    TargetForm.POSITIVE_WHOLE -> if (t < 1f || !whole) add("$at: ${tier}단계 목표치가 1 이상 정수가 아니다: ${MissionEngine.number(t)}")
                    TargetForm.UNUSED -> {}
                }
                if (m.reward(tier) == null) add("$at: ${tier}단계의 단계 보상이 없다")
                val name = m.name(row, t); val desc = m.description(row, t)
                if (name.length > MISSION_NAME_MAX) add("$at: ${tier}단계 이름이 ${MISSION_NAME_MAX}자를 넘는다: $name")
                if (desc.length > MISSION_DESCRIPTION_MAX) add("$at: ${tier}단계 설명이 ${MISSION_DESCRIPTION_MAX}자를 넘는다: $desc")
            }
            val ordered = row.tierTargets.filterNotNull().zipWithNext().all { (a, b) ->
                when (rule.harder) { HarderTarget.LOWER -> b <= a; HarderTarget.HIGHER -> b >= a; HarderTarget.NONE -> true }
            }
            if (!ordered) add("$at: 단계가 오를수록 목표치가 어려워지지 않는다: ${row.tierTargets}")
        }
        if (m.tierRewards.isEmpty()) add("미션 단계 보상이 하나도 없다")
        duplicates(m.tierRewards.map { it.tier.toString() }).forEach { add("미션 단계 보상의 단계 중복: $it") }
        for (r in m.tierRewards) {
            if (r.tier < MissionEngine.FIRST_TIER) add("미션 단계 보상의 단계가 ${MissionEngine.FIRST_TIER}보다 작다: ${r.tier}")
            if (r.coins < 0) add("${r.tier}단계: 코인 보상이 음수다: ${r.coins}")
            if (r.xp < 0) add("${r.tier}단계: 경험치 보상이 음수다: ${r.xp}")
        }
        with(m.rules) {
            if (personalPerDay < 0) add("하루 개인 미션 수가 음수다: $personalPerDay")
            if (weeklyPerWeek < 0) add("한 주 주간 미션 수가 음수다: $weeklyPerWeek")
            if (slotCount < 1) add("도전 슬롯이 1칸보다 적다: $slotCount")
            if (validDriveXp < 0) add("유효 주행 경험치가 음수다: $validDriveXp")
            if (cancelPenaltyMultiplier < 0) add("취소 패널티 배수가 음수다: $cancelPenaltyMultiplier")
            if (failPenaltyMultiplier < 0) add("실패 패널티 배수가 음수다: $failPenaltyMultiplier")
            for ((kind, n) in listOf(MissionKind.PERSONAL to personalPerDay, MissionKind.WEEKLY to weeklyPerWeek))
                if (n > 0 && m.conditions.none { r -> r.condition?.let { kind in ConditionRules.of(it).kinds } == true })
                    add("${kind} 미션을 만들 수 있는 조건이 없다")
        }
    }

    fun problems(p: ContentPack): List<String> = buildList {
        if (p.contentVersion < 1) add("contentVersion 이 1보다 작다: ${p.contentVersion}")
        if (p.minAppVersion < 1) add("minAppVersion 이 1보다 작다: ${p.minAppVersion}")

        addAll(missionProblems(p.missions))

        // 아이템
        if (p.items.isEmpty()) add("아이템이 하나도 없다")
        duplicates(p.items.map { it.id.toString() }).forEach { add("아이템 id 중복: $it") }
        for (key in p.itemTexts.keys) if (!TEXT_KEY.matches(key)) add("콘텐츠 텍스트 키가 규칙(영문 소문자·숫자·밑줄)에 맞지 않는다: $key")
        for (i in p.items) {
            if (i.id.value < FIRST_ID) add("아이템 id 가 ${FIRST_ID}보다 작다: ${i.id} (${i.nameKey})")
            if (i.nameKey.isBlank() || p.itemTexts[i.nameKey].isNullOrBlank()) add("아이템 ${i.id}: 콘텐츠 텍스트에 없는 이름 키: '${i.nameKey}'")
            if (!ItemImage.isValid(i.imageKey)) add("아이템 ${i.id}: 이미지 경로가 규칙(/로 나뉜 조각마다 영문·숫자·밑줄, 확장자 없음)에 맞지 않는다: '${i.imageKey}'")
            if (i.price < 0) add("아이템 ${i.id}: 가격이 음수다: ${i.price}")
        }

        // 캐릭터 종류 (#48)
        if (p.characters.isEmpty()) add("캐릭터가 하나도 없다")
        duplicates(p.characters.map { it.id.toString() }).forEach { add("캐릭터 id 중복: $it") }
        duplicates(p.characters.map { it.assetKey }).forEach { add("캐릭터 그림 폴더 이름 중복: $it") }
        for (c in p.characters) {
            if (c.id.value < FIRST_ID) add("캐릭터 id 가 ${FIRST_ID}보다 작다: ${c.id} (${c.name})")
            if (c.name.isBlank()) add("캐릭터 ${c.id}: 이름이 비었다")
            if (!ASSET_KEY.matches(c.assetKey)) add("캐릭터 ${c.id}: 그림 폴더 이름이 규칙(영문 소문자·숫자·밑줄)에 맞지 않는다: ${c.assetKey}")
        }

        // 점수 구간
        if (p.scoreBands.none { it.minScore <= LOWEST_SCORE }) add("점수 ${LOWEST_SCORE}점을 받는 구간이 없다")
        duplicates(p.scoreBands.map { it.minScore.toString() }).forEach { add("점수 구간 하한 중복: $it") }

        // 엔진 수치 (스펙 6-1). 범위·간격·묶음·관계 규칙
        addAll(EngineTuning.problems(p.engine))
    }

    private fun duplicates(ids: List<String>): Set<String> = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
}

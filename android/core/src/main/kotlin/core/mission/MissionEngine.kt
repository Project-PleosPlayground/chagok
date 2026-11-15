package core.mission

import core.Time
import core.saturatingAdd
import core.saturatingTimes
import core.session.SessionRecord
import core.session.SessionStore
import core.session.VehicleStatus
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random

/**
 * 미션 제공·도전·판정·보상. 엔진 밖 순수 로직. 기준은 기획/07_미션.md.
 * 시간은 wall-clock 시각(nowMs, epoch ms)을 호출자가 넘긴다. 주행 중인지도 호출자가 넘긴다.
 * 보상과 패널티는 값만 돌려주고, 코인·경험치에 반영하는 것은 core.flow 가 한다.
 */
class MissionEngine(
    private val store: MissionStore,
    private val sessions: SessionStore,
    private val tzOffsetMs: Long = Time.KST_OFFSET_MS,
    /** 지금 쓰는 미션 데이터 (#31). :app 은 { ContentState.active.missions } 를 넘긴다 */
    private val missions: () -> MissionCatalog,
    /** 조건·종류별 세는 범위 */
    private val range: RangePolicy = RangePolicy.BY_CONDITION,
    /** 제공할 미션을 고르는 난수 */
    private val random: Random = Random.Default,
    /** 받은 미션 한 건의 uid 를 만든다 */
    private val newUid: () -> String = { UUID.randomUUID().toString() },
) {
    data class Completed(val instance: MissionInstance, val coins: Int, val xp: Int)

    /** 깎는 포인트·경험치 (07 "패널티") */
    data class Penalty(val coins: Int, val xp: Int)

    /** 실패로 판정한 미션과 실패 패널티 */
    data class Failed(val instance: MissionInstance, val penalty: Penalty)

    /** 한 번에 판정한 결과: 성공한 미션과 보상, 실패한 미션과 패널티 */
    data class Judged(val completed: List<Completed> = emptyList(), val failed: List<Failed> = emptyList()) {
        val isEmpty: Boolean get() = completed.isEmpty() && failed.isEmpty()
        /** 실패 패널티의 합 */
        val penalty: Penalty get() = Penalty(
            failed.fold(0) { a, f -> saturatingAdd(a, f.penalty.coins) },
            failed.fold(0) { a, f -> saturatingAdd(a, f.penalty.xp) },
        )
        operator fun plus(other: Judged) = Judged(completed + other.completed, failed + other.failed)
    }

    sealed interface SlotResult {
        data class Ok(val instance: MissionInstance, val penalty: Penalty? = null) : SlotResult
        data class Rejected(val reason: Reason) : SlotResult
    }

    enum class Reason {
        /** 주행 중에는 도전 슬롯을 바꿀 수 없다 */
        DRIVING,
        NOT_FOUND,
        /** 이 상태에서는 할 수 없는 동작 */
        WRONG_STATE,
        /** 유효시간이 지났다 */
        EXPIRED,
        /** 도전 슬롯이 다 찼다 */
        SLOT_FULL,
    }

    // ───────── 조회 ─────────

    /** 지금 유효시간 안에서 제공된, 도전하지 않은 미션 */
    fun offers(profileId: String, nowMs: Long): List<MissionInstance> =
        store.list(profileId).filter { it.status == MissionStatus.OFFERED && nowMs < it.validUntilMs }

    /** 도전 슬롯에 있는 미션 */
    fun slot(profileId: String): List<MissionInstance> = store.list(profileId).filter { it.inSlot }

    /** [sinceMs] 부터 성공한 미션. 최근 성공이 앞 (완료 미션 목록) */
    fun completed(profileId: String, sinceMs: Long): List<MissionInstance> =
        store.list(profileId)
            .filter { it.status == MissionStatus.SUCCEEDED && (it.endedMs ?: Long.MIN_VALUE) >= sinceMs }
            .sortedByDescending { it.endedMs }

    // ───────── 제공·갱신 ─────────

    /**
     * 유효시간이 끝난 미션을 정리하고, 이번 날의 개인 미션이 없으면 제공하고, 주간 미션 목록을 채운다. 앱 시작, 1분 틱, 주행 시작·끝 때 부른다.
     * [driveStartMs] 는 지금 주행 중이면 그 주행의 시작 시각, 아니면 null. 주행 보전에 쓴다.
     * 유효시간 끝에 판정한 미션의 보상·패널티를 돌려준다.
     */
    fun refresh(profileId: String, nowMs: Long, driveStartMs: Long?): Judged {
        val out = closeEnded(profileId, nowMs, driveStartMs)
        relabel(profileId)
        offerIfMissing(profileId, nowMs, MissionKind.PERSONAL)
        offerIfMissing(profileId, nowMs, MissionKind.WEEKLY)
        return out
    }

    /** 미션 글은 받을 때 저장된다. 콘텐츠 문구가 바뀌면 아직 끝나지 않은 미션의 이름·설명도 지금 문구로 바꾼다 (11/15) */
    private fun relabel(profileId: String) {
        val cat = missions()
        for (m in store.list(profileId)) {
            if (m.status != MissionStatus.OFFERED && m.status != MissionStatus.IN_PROGRESS) continue
            val row = cat.conditions.firstOrNull { it.condition == m.condition } ?: continue
            val title = cat.name(row, m.target); val description = cat.description(row, m.target)
            if (title != m.title || description != m.description) store.upsert(m.copy(title = title, description = description))
        }
    }

    private fun closeEnded(profileId: String, nowMs: Long, driveStartMs: Long?): Judged {
        val completed = ArrayList<Completed>()
        val failed = ArrayList<Failed>()
        for (m in store.list(profileId)) {
            when {
                m.status == MissionStatus.OFFERED && nowMs >= m.validUntilMs ->
                    store.upsert(m.copy(status = MissionStatus.EXPIRED, endedMs = m.validUntilMs))
                // 유효시간 끝에 판정한 미션은 판정과 함께 슬롯에서 뺀다
                m.status == MissionStatus.IN_PROGRESS && nowMs >= m.validUntilMs && !preserved(m, driveStartMs) -> {
                    val r = range.of(m.condition, m.kind)
                    val cleared = m.copy(clearedMs = nowMs)
                    when (ConditionRules.atExpiry(ConditionRules.of(m.condition).compare, r, m.counted, m.target, m.drives)) {
                        Verdict.SUCCEED -> completed += succeed(cleared, nowMs)
                        Verdict.FAIL -> failed += fail(cleared, nowMs)
                        Verdict.UNDETERMINED, Verdict.CONTINUE -> store.upsert(cleared.copy(status = MissionStatus.UNDETERMINED, endedMs = nowMs))
                    }
                }
                m.status.judged && m.clearedMs == null && m.endedMs != null -> {
                    val nextDay = Time.dayStart(m.endedMs, tzOffsetMs) + Time.DAY_MS
                    if (nowMs >= nextDay) store.upsert(m.copy(clearedMs = nextDay))
                }
            }
        }
        return Judged(completed, failed)
    }

    /** 주행 보전: 도전한 뒤 유효시간 안에 시작한 주행이 아직 끝나지 않았다 */
    private fun preserved(m: MissionInstance, driveStartMs: Long?): Boolean =
        driveStartMs != null && driveStartMs < m.validUntilMs && driveStartMs >= (m.challengedMs ?: Long.MAX_VALUE)

    /** 이 종류의 지금 유효시간이 끝나는 시각: 개인 미션은 다음 날 00:00, 주간 미션은 다음 주 월요일 00:00 */
    fun validUntil(kind: MissionKind, nowMs: Long): Long = when (kind) {
        MissionKind.PERSONAL -> Time.dayStart(nowMs, tzOffsetMs) + Time.DAY_MS
        MissionKind.WEEKLY -> Time.weekStart(nowMs, tzOffsetMs) + WEEK_DAYS * Time.DAY_MS
    }

    /**
     * 정한 조건·단계로 미션 하나를 제공한다(맞춤 미션 등). 지금 유효시간 안에 제공된다.
     * 조건 표에 없는 조건, 보상 표에 없는 단계, 이 종류로 만들 수 없는 조건이면 null.
     * 여기서 제공한 개인 미션도 [refresh] 가 "이 날은 이미 제공했다"고 보는 판단에 들어간다. [refresh] 보다 먼저 부르면 그 날의 랜덤 제공이 없다.
     * 여기서 제공한 주간 미션은 주간 목록 개수에 들어간다.
     */
    fun offer(profileId: String, kind: MissionKind, condition: ConditionType, tier: Int, nowMs: Long): MissionInstance? {
        val cat = missions()
        val row = cat.row(condition) ?: return null
        val reward = cat.reward(tier) ?: return null
        val target = row.target(tier) ?: return null
        if (kind !in ConditionRules.of(condition).kinds) return null
        return make(profileId, kind, cat, row, condition, tier, target, reward, nowMs, validUntil(kind, nowMs)).also { store.upsert(it) }
    }

    /**
     * 개인 미션: 이 날 제공한 것이 없으면 [MissionRules.personalPerDay] 개를 제공한다.
     * 주간 미션: 도전하지 않은 이 주의 목록이 [MissionRules.weeklyPerWeek] 개가 되도록 채운다.
     * 이때 목록과 도전 슬롯에 있는 주간 미션과 조건·단계가 같은 것은 뽑지 않는다.
     */
    private fun offerIfMissing(profileId: String, nowMs: Long, kind: MissionKind) {
        val end = validUntil(kind, nowMs)
        val all = store.list(profileId)
        val cat = missions()
        val ofKind = all.filter { it.kind == kind }
        val listed = ofKind.filter { it.status == MissionStatus.OFFERED && it.validUntilMs == end }
        val (count, taken) = when (kind) {
            MissionKind.PERSONAL ->
                (if (ofKind.any { it.validUntilMs == end }) 0 else cat.rules.personalPerDay) to emptySet()
            MissionKind.WEEKLY ->
                (cat.rules.weeklyPerWeek - listed.size) to (listed + ofKind.filter { it.inSlot }).map { it.condition to it.tier }.toSet()
        }
        if (count <= 0) return
        val combos = cat.conditions
            .mapNotNull { row -> row.condition?.let { row to it } }
            .filter { (_, c) -> kind in ConditionRules.of(c).kinds }
            .flatMap { (row, c) -> row.tiers.map { Triple(row, c, it) } }
            .filter { (_, c, tier) -> cat.reward(tier) != null && (c to tier) !in taken }
            .shuffled(random)
            .take(count)
        for ((row, c, tier) in combos) store.upsert(make(profileId, kind, cat, row, c, tier, row.target(tier)!!, cat.reward(tier)!!, nowMs, end))
    }

    private fun make(
        profileId: String, kind: MissionKind, cat: MissionCatalog, row: ConditionRow, condition: ConditionType,
        tier: Int, target: Float, reward: TierReward, nowMs: Long, end: Long,
    ) = MissionInstance(
        uid = newUid(), profileId = profileId, kind = kind, condition = condition, tier = tier, target = target,
        rewardCoins = reward.coins, rewardXp = reward.xp,
        title = cat.name(row, target), description = cat.description(row, target), iconKey = row.iconKey,
        offeredMs = nowMs, validUntilMs = end, status = MissionStatus.OFFERED,
    )

    // ───────── 도전 슬롯 ─────────

    /** 제공된 미션을 도전 슬롯에 놓는다. 주간 미션이면 주간 목록을 다시 채운다 */
    fun challenge(profileId: String, uid: String, nowMs: Long, driving: Boolean): SlotResult {
        if (driving) return SlotResult.Rejected(Reason.DRIVING)
        val m = find(profileId, uid) ?: return SlotResult.Rejected(Reason.NOT_FOUND)
        if (m.status != MissionStatus.OFFERED) return SlotResult.Rejected(Reason.WRONG_STATE)
        if (nowMs >= m.validUntilMs) return SlotResult.Rejected(Reason.EXPIRED)
        if (slot(profileId).size >= missions().rules.slotCount) return SlotResult.Rejected(Reason.SLOT_FULL)
        val next = m.copy(status = MissionStatus.IN_PROGRESS, challengedMs = nowMs)
        store.upsert(next)
        if (m.kind == MissionKind.WEEKLY) offerIfMissing(profileId, nowMs, MissionKind.WEEKLY)
        return SlotResult.Ok(next)
    }

    /** 도전 중인 미션을 취소한다. 미션은 사라지고 취소 패널티([penalty])를 돌려준다 */
    fun cancel(profileId: String, uid: String, nowMs: Long, driving: Boolean): SlotResult {
        if (driving) return SlotResult.Rejected(Reason.DRIVING)
        val m = find(profileId, uid) ?: return SlotResult.Rejected(Reason.NOT_FOUND)
        if (m.status != MissionStatus.IN_PROGRESS) return SlotResult.Rejected(Reason.WRONG_STATE)
        val next = m.copy(status = MissionStatus.CANCELLED, endedMs = nowMs)
        store.upsert(next)
        return SlotResult.Ok(next, penalty(m, missions().rules.cancelPenaltyMultiplier))
    }

    /** 판정이 끝난 미션을 도전 슬롯에서 비운다. 패널티 없음 */
    fun clear(profileId: String, uid: String, nowMs: Long, driving: Boolean): SlotResult {
        if (driving) return SlotResult.Rejected(Reason.DRIVING)
        val m = find(profileId, uid) ?: return SlotResult.Rejected(Reason.NOT_FOUND)
        if (!m.status.judged || m.clearedMs != null) return SlotResult.Rejected(Reason.WRONG_STATE)
        val next = m.copy(clearedMs = nowMs)
        store.upsert(next)
        return SlotResult.Ok(next)
    }

    private fun find(profileId: String, uid: String) = store.list(profileId).firstOrNull { it.uid == uid }

    // ───────── 판정 ─────────

    /**
     * 주행이 끝났을 때. 유효 주행이면 도전 중인 미션을 판정하고, 주행 보전으로 남아 있던 미션을 정리한다.
     * 성공한 미션과 보상, 실패한 미션과 패널티를 돌려준다. 유효 주행 경험치는 포함하지 않는다.
     */
    fun onDriveEnd(record: SessionRecord, status: VehicleStatus, nowMs: Long): Judged {
        var out = Judged()
        if (record.valid) for (m in counting(record)) out += judge(m, record, status, nowMs)
        return out + refresh(record.profileId, nowMs, driveStartMs = null)
    }

    /**
     * 주행 중 진행도 (07 "판정 시점"). [drive] 는 유효 주행으로 인정된 뒤 지금까지의 주행이다. 판정하지 않고 저장하지도 않는다.
     * 이 주행으로 세는 도전 중 미션 가운데 [ConditionRules.LIVE] 조건만, uid → 이 주행까지 센 값으로 돌려준다.
     * 주행 끝 판정과 같은 계산을 쓰므로, 주행이 끝날 때 이벤트가 같으면 센 값도 같다. 셀 근거가 아직 없는 미션은 빠진다.
     */
    fun liveProgress(drive: SessionRecord, status: VehicleStatus = VehicleStatus()): Map<String, Float> =
        counting(drive).filter { it.condition in ConditionRules.LIVE }
            .mapNotNull { m -> measure(m, drive, status)?.let { m.uid to it } }
            .toMap()

    /** [record] 주행으로 세는 도전 중 미션: 도전한 뒤, 유효시간 안에 시작한 주행 */
    private fun counting(record: SessionRecord): List<MissionInstance> = store.list(record.profileId).filter {
        val challenged = it.challengedMs
        it.status == MissionStatus.IN_PROGRESS && challenged != null && record.startWallMs >= challenged && record.startWallMs < it.validUntilMs
    }

    /** [record] 주행까지 센 값. 주행 끝 판정과 주행 중 진행도가 같이 쓴다 */
    private fun measure(m: MissionInstance, record: SessionRecord, status: VehicleStatus): Float? {
        val r = range.of(m.condition, m.kind)
        val drives = sessions.list(m.profileId, m.challengedMs ?: record.startWallMs, m.validUntilMs - 1).filter { it.valid }
            .let { if (it.none { x -> x.id == record.id }) it + record else it }
        val prev = if (r == CountRange.SINGLE_DRIVE) null else m.counted
        return ConditionRules.of(m.condition).count(prev, MeasureInput(record, status, drives, m.target, tzOffsetMs))
    }

    private fun judge(m: MissionInstance, record: SessionRecord, status: VehicleStatus, nowMs: Long): Judged {
        val r = range.of(m.condition, m.kind)
        val rule = ConditionRules.of(m.condition)
        val counted = measure(m, record, status)
        val nextDay = Time.dayStart(record.startWallMs, tzOffsetMs) + Time.DAY_MS
        val daysLeft = ((m.validUntilMs - nextDay) / Time.DAY_MS).coerceAtLeast(0)
        val seen = m.copy(counted = counted ?: m.counted, drives = m.drives + 1)
        return when (ConditionRules.afterDrive(rule.compare, r, counted, m.target, daysLeft)) {
            Verdict.SUCCEED -> Judged(completed = listOf(succeed(seen, nowMs)))
            Verdict.FAIL -> Judged(failed = listOf(fail(seen, nowMs)))
            Verdict.CONTINUE, Verdict.UNDETERMINED -> { store.upsert(seen); Judged() }
        }
    }

    private fun succeed(m: MissionInstance, nowMs: Long): Completed {
        val done = m.copy(status = MissionStatus.SUCCEEDED, endedMs = nowMs)
        store.upsert(done)
        return Completed(done, m.rewardCoins, m.rewardXp)
    }

    /** 실패로 판정하고 실패 패널티([penalty])를 돌려준다 */
    private fun fail(m: MissionInstance, nowMs: Long): Failed {
        val done = m.copy(status = MissionStatus.FAILED, endedMs = nowMs)
        store.upsert(done)
        return Failed(done, penalty(m, missions().rules.failPenaltyMultiplier))
    }

    /** 패널티 = 받은 미션의 보상 × 깎는 때의 미션 데이터 배수 (07 "패널티") */
    private fun penalty(m: MissionInstance, multiplier: Int) =
        Penalty(saturatingTimes(m.rewardCoins, multiplier), saturatingTimes(m.rewardXp, multiplier))

    companion object {
        /** 단계는 1단계부터 센다 (07 "미션 만들기") */
        const val FIRST_TIER = 1
        private const val WEEK_DAYS = 7
        /** 이름·설명 틀에서 목표치가 들어가는 자리 */
        const val TARGET_SLOT = "{n}"

        /** 이름·설명 틀의 [TARGET_SLOT] 에 목표치를 넣는다 */
        fun fill(template: String, target: Float): String = template.replace(TARGET_SLOT, number(target))

        /** 목표치를 글자로. 정수면 소수점 없이 쓴다 */
        fun number(value: Float): String = BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
    }
}

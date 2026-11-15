package ai.safetypoc.app.ui.mission

import core.flow.MissionActions
import core.mission.Compare
import core.mission.ConditionRules
import core.mission.MissionEngine
import core.mission.MissionInstance
import core.mission.MissionKind
import core.mission.MissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import core.Time

/** 미션 날짜를 셀 때 쓰는 시간대: 엔진의 날짜 경계와 같은 KST ([Time.KST_OFFSET_MS]) */
val MISSION_ZONE: ZoneId = ZoneOffset.ofTotalSeconds((Time.KST_OFFSET_MS / MS_PER_SECOND).toInt())

private const val MS_PER_SECOND = 1_000L

/** 미션 카드 하나 */
data class MissionCard(
    val uid: String,
    val title: String,
    val description: String,
    val kind: MissionKind,
    val status: MissionStatus,
    val tier: Int,
    /** 진행도 "센 값/목표치" (07 "판정") */
    val progress: String,
    val rewardCoins: Int,
    val rewardXp: Int,
    /** 유효시간이 끝날 때까지 남은 시간 */
    val remainingMs: Long,
    val canChallenge: Boolean,
    val canCancel: Boolean,
    val canClear: Boolean,
    /** 진행 막대 채움: 센 값 ÷ 목표치 (0~1). 센 값이 없으면 0 */
    val progressFraction: Float = 0f,
    /** 유효시간이 끝나는 시각. 화면이 남은 시간을 초 단위로 센다 */
    val validUntilMs: Long = 0L,
    /** 성공한 시각. 완료 미션 목록에서만 있다 */
    val completedAtMs: Long? = null,
)

/** 동작 결과로 화면에 잠깐 알릴 것 */
enum class MissionNotice { DRIVING, SLOT_FULL, EXPIRED, CANCELLED }

/** 미션 탭의 확인 팝업 (디자인 시스템 7절 확인 팝업) */
sealed interface MissionDialog {
    val card: MissionCard
    /** 제공 카드를 누름: 도전할지 */
    data class Challenge(override val card: MissionCard) : MissionDialog
    /** 도전 중 카드를 길게 누름: 도전을 취소할지 */
    data class Cancel(override val card: MissionCard) : MissionDialog
    /** 성공·실패 카드를 길게 누름: 슬롯에서 비울지 */
    data class Clear(override val card: MissionCard) : MissionDialog
}

/** 미션 탭이 보여 줄 값 (07 "미션 탭", 영역사양_미션화면) */
data class MissionTabState(
    /** 도전 슬롯: 도전 중이거나 판정 끝나고 비우지 않은 미션. 도전한 순서 */
    val slot: List<MissionCard> = emptyList(),
    val slotCount: Int = 0,
    /** 제공된 개인·주간 미션 (도전 전). 가나다 순 */
    val personal: List<MissionCard> = emptyList(),
    val weekly: List<MissionCard> = emptyList(),
    /** 최근 1개월 동안 성공한 미션. 최근 성공이 앞 */
    val completed: List<MissionCard> = emptyList(),
    /** 주행 중이면 도전·취소·비우기를 막는다 */
    val driving: Boolean = false,
    val notice: MissionNotice? = null,
    val dialog: MissionDialog? = null,
) {
    /** 도전 슬롯이 다 찼다. 제공 카드를 흐리게 한다 */
    val slotFull: Boolean get() = slotCount in 1..slot.size
}

/** 미션 탭에서 하는 동작 */
interface MissionTabActions {
    fun challenge(uid: String)
    fun cancel(uid: String)
    fun clear(uid: String)
    /** 카드를 누름: 제공 카드면 도전 팝업, 슬롯이 찼으면 알림 */
    fun tapCard(uid: String) = Unit
    /** 카드를 길게 누름: 도전 중이면 도전 취소 팝업, 성공·실패면 슬롯 비우기 팝업 */
    fun longPressCard(uid: String) = Unit
    /** 열린 팝업의 왼쪽 버튼(하려는 일) */
    fun confirmDialog() = Unit
    fun dismissDialog() = Unit
    /** 화면이 알림을 띄운 뒤 지운다 */
    fun dismissNotice() = Unit
}

/**
 * 미션 엔진에서 [MissionTabState] 를 만들고 동작을 엔진으로 넘긴다. 탭에 들어올 때 [refresh] 를 부른다.
 * 취소 패널티는 [MissionActions] 가 포인트·경험치에 반영한다. 상태가 바뀌면 [onChanged](홈 갱신 등)를 부른다.
 */
class MissionTabModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val engine: MissionEngine,
    private val actions: MissionActions,
    private val slotCount: () -> Int,
    private val driving: () -> Boolean,
    private val now: () -> Long,
    private val onChanged: () -> Unit = {},
    /** 주행 중 진행도: 도전 중 미션 uid → 지금 주행까지 센 값 (07 "판정 시점"). 있으면 저장된 센 값 대신 보인다 */
    private val live: () -> Map<String, Float> = { emptyMap() },
    /** 완료 미션 목록의 기준 시각을 셀 때 쓰는 시간대 */
    private val zone: ZoneId = MISSION_ZONE,
) : MissionTabActions {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(MissionTabState())
    val state: StateFlow<MissionTabState> = _state.asStateFlow()

    fun refresh(notice: MissionNotice? = null) {
        val t = now(); val drivingNow = driving()
        val offers = engine.offers(profileId, t)
        val liveNow = live()
        fun cards(list: List<MissionInstance>) = list.map { card(it, t, drivingNow, liveNow[it.uid]) }
        val since = Instant.ofEpochMilli(t).atZone(zone).minusMonths(COMPLETED_MONTHS).toInstant().toEpochMilli()
        _state.value = MissionTabState(
            slot = cards(engine.slot(profileId)),
            slotCount = slotCount(),
            personal = cards(offers.filter { it.kind == MissionKind.PERSONAL }).sortedBy { it.title },
            weekly = cards(offers.filter { it.kind == MissionKind.WEEKLY }).sortedBy { it.title },
            completed = cards(engine.completed(profileId, since)),
            driving = drivingNow,
            notice = notice,
            dialog = _state.value.dialog,
        )
    }

    /** 지금 도전 슬롯의 카드. 주행 중이면 지금 주행까지 센 값으로 보인다. 화면 상태([state])는 바꾸지 않는다 */
    fun slotNow(): List<MissionCard> {
        val t = now(); val drivingNow = driving(); val liveNow = live()
        return engine.slot(profileId).map { card(it, t, drivingNow, liveNow[it.uid]) }
    }

    /** 지금 도전할 수 있는 제공 미션(개인·주간) 수 */
    fun offeredNow(): Int = engine.offers(profileId, now()).size

    override fun challenge(uid: String) = after(actions.challenge(profileId, uid, now(), driving()))
    override fun cancel(uid: String) = after(actions.cancel(profileId, uid, now(), driving()), onOk = MissionNotice.CANCELLED)
    override fun clear(uid: String) = after(actions.clear(profileId, uid, now(), driving()))

    override fun tapCard(uid: String) {
        val s = _state.value
        val card = (s.personal + s.weekly).firstOrNull { it.uid == uid } ?: return
        when {
            s.driving -> notify(MissionNotice.DRIVING)
            s.slotFull -> notify(MissionNotice.SLOT_FULL)
            else -> _state.value = s.copy(dialog = MissionDialog.Challenge(card))
        }
    }

    override fun longPressCard(uid: String) {
        val s = _state.value
        val card = s.slot.firstOrNull { it.uid == uid } ?: return
        val dialog = when {
            card.status == MissionStatus.IN_PROGRESS -> MissionDialog.Cancel(card)
            card.status.judged -> MissionDialog.Clear(card)
            else -> return
        }
        if (s.driving) notify(MissionNotice.DRIVING) else _state.value = s.copy(dialog = dialog)
    }

    override fun confirmDialog() {
        val dialog = _state.value.dialog ?: return
        _state.value = _state.value.copy(dialog = null)
        when (dialog) {
            is MissionDialog.Challenge -> challenge(dialog.card.uid)
            is MissionDialog.Cancel -> cancel(dialog.card.uid)
            is MissionDialog.Clear -> clear(dialog.card.uid)
        }
    }

    override fun dismissDialog() { _state.value = _state.value.copy(dialog = null) }

    override fun dismissNotice() { _state.value = _state.value.copy(notice = null) }

    private fun notify(notice: MissionNotice) { _state.value = _state.value.copy(notice = notice) }

    private fun after(r: MissionEngine.SlotResult, onOk: MissionNotice? = null) {
        val notice = when (r) {
            is MissionEngine.SlotResult.Ok -> onOk
            is MissionEngine.SlotResult.Rejected -> when (r.reason) {
                MissionEngine.Reason.DRIVING -> MissionNotice.DRIVING
                MissionEngine.Reason.SLOT_FULL -> MissionNotice.SLOT_FULL
                MissionEngine.Reason.EXPIRED -> MissionNotice.EXPIRED
                MissionEngine.Reason.NOT_FOUND, MissionEngine.Reason.WRONG_STATE -> null
            }
        }
        refresh(notice)
        onChanged()
    }

    private fun card(m: MissionInstance, t: Long, drivingNow: Boolean, liveCounted: Float?): MissionCard {
        val goal = if (ConditionRules.of(m.condition).compare == Compare.ANY) ANY_GOAL else m.target
        val counted = liveCounted ?: m.counted
        return MissionCard(
            uid = m.uid, title = m.title, description = m.description, kind = m.kind, status = m.status, tier = m.tier,
            progress = "${progressCount(m, liveCounted)}/${MissionEngine.number(goal)}",
            rewardCoins = m.rewardCoins, rewardXp = m.rewardXp, remainingMs = m.remainingMs(t),
            canChallenge = !drivingNow && m.status == MissionStatus.OFFERED,
            canCancel = !drivingNow && m.status == MissionStatus.IN_PROGRESS,
            canClear = !drivingNow && m.status.judged,
            progressFraction = if (counted == null || goal <= 0f) 0f else (counted / goal).coerceIn(0f, 1f),
            validUntilMs = m.validUntilMs,
            completedAtMs = m.endedMs.takeIf { m.status == MissionStatus.SUCCEEDED },
        )
    }

    /** 진행도의 센 값. 주행 중이면 지금 주행까지 센 값이다. 아직 센 값이 없으면 "이하" 조건은 0, "이상" 조건은 [NOT_COUNTED] ([제안] 0 이면 0%로 읽힌다) */
    private fun progressCount(m: MissionInstance, liveCounted: Float?): String {
        val counted = liveCounted ?: m.counted ?: return if (ConditionRules.of(m.condition).compare == Compare.AT_LEAST) NOT_COUNTED else MissionEngine.number(0f)
        return MissionEngine.number(counted)
    }

    private companion object {
        /** 아직 센 값이 없음 */
        const val NOT_COUNTED = "-"
        /** "한 번이라도" 조건은 목표치 대신 1번으로 보인다 */
        const val ANY_GOAL = 1f
        /** 완료 미션 목록이 보이는 기간: 최근 1개월 (07 "완료 미션 화면") */
        const val COMPLETED_MONTHS = 1L
    }
}

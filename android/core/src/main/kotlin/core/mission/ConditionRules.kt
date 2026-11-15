package core.mission

import core.Time
import core.session.SessionRecord
import core.session.VehicleStatus
import engine.EventType

/*
 * 조건별 판정 규칙. 조건마다 다른 것은 "무엇을 세나"(측정 함수)뿐이고, 비교·확정 시점·만료 판정은
 * [ConditionRules.afterDrive]·[ConditionRules.atExpiry] 가 모든 조건에 같이 쓴다. 기준은 기획/07_미션.md "판정", "조건".
 *
 * 이미 있는 조건의 뜻(세는 방식·비교)은 바꾸지 않는다. 바꿀 일이 생기면 새 조건을 추가한다.
 * 도전 중인 미션이 앱 업데이트로 다른 기준으로 판정되지 않게 하기 위해서다.
 */

/** 센 값과 목표치의 비교 (07 "조건"의 비교) */
enum class Compare {
    /** 센 값이 목표치 이하 */
    AT_MOST,
    /** 센 값이 목표치 이상 */
    AT_LEAST,
    /** 목표치만큼 연이어 일어남 */
    STREAK,
    /** 한 번 일어나면 결과가 정해짐. 센 값은 일어났으면 1, 근거는 있었지만 일어나지 않았으면 0 */
    ANY,
}

/** 미션 하나가 세는 주행의 범위 */
enum class CountRange {
    /** 도전한 뒤 판정 근거가 있는 첫 유효 주행 하나로 판정한다 */
    SINGLE_DRIVE,
    /** 유효시간 동안의 유효 주행을 모두 센다 */
    VALIDITY,
}

/** 목표치로 쓸 수 있는 값. 콘텐츠 검사용 */
enum class TargetForm {
    /** 0 이상 정수 (횟수) */
    WHOLE_FROM_ZERO,
    /** 0 보다 큰 수. 0 이면 유효 주행 한 번에 바로 성공한다 (비율, 거리, 잔량) */
    POSITIVE,
    /** 1 이상 정수 (일수) */
    POSITIVE_WHOLE,
    /** 판정에 쓰지 않는다 */
    UNUSED,
}

/** 단계가 오를수록(어려울수록) 목표치가 어느 쪽으로 가나. 콘텐츠 검사용 */
enum class HarderTarget { LOWER, HIGHER, NONE }

/** 측정 함수의 입력: 방금 끝난 유효 주행과, 이 미션이 센 주행들(방금 주행 포함) */
class MeasureInput(
    val drive: SessionRecord,
    val status: VehicleStatus,
    /** 도전한 뒤 이 미션이 센 유효 주행. 방금 주행을 포함한다 */
    val drives: List<SessionRecord>,
    val target: Float,
    val tzOffsetMs: Long,
)

class ConditionRule(
    val compare: Compare,
    /** 이 조건으로 미션을 만들 수 있는 종류 */
    val kinds: Set<MissionKind>,
    /** 조건마다 정해진 세는 범위 */
    val range: CountRange,
    val harder: HarderTarget,
    /** 목표치 상한. 퍼센트 조건은 100. 없으면 null */
    val targetMax: Float?,
    /** 목표치로 쓸 수 있는 값의 모양. 콘텐츠 검사용 */
    val targetForm: TargetForm,
    /**
     * 이번 주행까지의 센 값. [prev] 는 지난번 센 값(한 주행만 세면 늘 null).
     * 이번 주행으로 셀 근거가 없으면 [prev] 를 그대로 돌려준다.
     */
    val count: (prev: Float?, input: MeasureInput) -> Float?,
)

/** 세는 범위를 정하는 방법. 엔진이 생성자로 받는다 */
fun interface RangePolicy {
    fun of(condition: ConditionType, kind: MissionKind): CountRange

    companion object {
        /** 조건마다 정해진 범위. 이하 조건은 개인·주간 모두 한 주행만 센다 */
        val BY_CONDITION = RangePolicy { c, _ -> ConditionRules.of(c).range }

        /** 이하 조건만 종류로 나눈다: 개인 미션은 한 주행, 주간 미션은 유효시간 동안 센다. 나머지는 [BY_CONDITION] */
        val AT_MOST_WEEKLY_ACCUMULATES = RangePolicy { c, k ->
            val r = ConditionRules.of(c)
            if (r.compare == Compare.AT_MOST && k == MissionKind.WEEKLY) CountRange.VALIDITY else r.range
        }
    }
}

/** 주행 하나·유효시간 끝의 판정 결과 */
enum class Verdict { SUCCEED, FAIL, CONTINUE, UNDETERMINED }

object ConditionRules {
    private const val PERCENT_MAX = 100f
    private const val PERCENT = 100f
    private val BOTH = setOf(MissionKind.PERSONAL, MissionKind.WEEKLY)
    private const val HAPPENED = 1f
    private const val NOT_HAPPENED = 0f

    /**
     * 센 값이 이번 주행의 엔진 이벤트(급제동·급가속·차선변경)로만 정해지는 조건. 주행 중에도 진행도를 셀 수 있다 (07 "판정 시점").
     * 거리·차량 상태·주행 날짜로 세는 조건은 주행이 끝나야 셀 수 있어 빠진다.
     */
    val LIVE: Set<ConditionType> = setOf(
        ConditionType.HARSH_BRAKE_MAX, ConditionType.HARSH_ACCEL_MAX, ConditionType.LANE_NO_SIGNAL_MAX, ConditionType.SIGNALED_RATE_MIN,
    )

    private fun sumCount(vararg types: EventType): (Float?, MeasureInput) -> Float? =
        { prev, i -> (prev ?: 0f) + types.sumOf { i.drive.count(it) } }

    fun of(c: ConditionType): ConditionRule = when (c) {
        ConditionType.HARSH_BRAKE_MAX -> ConditionRule(Compare.AT_MOST, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.LOWER, null, TargetForm.WHOLE_FROM_ZERO,
            sumCount(EventType.HARSH_BRAKE))
        ConditionType.HARSH_ACCEL_MAX -> ConditionRule(Compare.AT_MOST, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.LOWER, null, TargetForm.WHOLE_FROM_ZERO,
            sumCount(EventType.HARSH_ACCEL))
        ConditionType.LANE_NO_SIGNAL_MAX -> ConditionRule(Compare.AT_MOST, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.LOWER, null, TargetForm.WHOLE_FROM_ZERO,
            sumCount(EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL))
        // 차선변경이 없는 주행은 근거가 없다(미판정). 다음 주행으로 넘어간다
        ConditionType.SIGNALED_RATE_MIN -> ConditionRule(Compare.AT_LEAST, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.HIGHER, PERCENT_MAX, TargetForm.POSITIVE) { prev, i ->
            i.drive.signaledRate?.let { it * PERCENT } ?: prev
        }
        ConditionType.CLEAN_KM -> ConditionRule(Compare.AT_LEAST, BOTH, CountRange.VALIDITY, HarderTarget.HIGHER, null, TargetForm.POSITIVE) { prev, i ->
            (prev ?: 0f) + if (i.drive.clean) i.drive.distanceKm else 0f
        }
        ConditionType.SEATBELT_BEFORE_START -> ConditionRule(Compare.ANY, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.NONE, null, TargetForm.UNUSED) { prev, i ->
            i.status.seatBeltBuckledAtStart?.let { if (it) HAPPENED else NOT_HAPPENED } ?: prev
        }
        // 충전은 정차 중에 일어나므로, 멈춘 뒤의 첫 유효 주행이 끝날 때 센다
        ConditionType.CHARGE_STOP_AT_80 -> ConditionRule(Compare.ANY, BOTH, CountRange.VALIDITY, HarderTarget.LOWER, PERCENT_MAX, TargetForm.POSITIVE) { prev, i ->
            val stop = i.status.chargeStoppedAtPct ?: return@ConditionRule prev
            if (prev == HAPPENED || stop <= i.target) HAPPENED else NOT_HAPPENED
        }
        ConditionType.NO_BELOW_10_PCT -> ConditionRule(Compare.AT_LEAST, BOTH, CountRange.SINGLE_DRIVE, HarderTarget.HIGHER, PERCENT_MAX, TargetForm.POSITIVE) { prev, i ->
            i.status.minSocThisDayPct?.toFloat() ?: prev
        }
        ConditionType.STREAK_DAYS -> ConditionRule(Compare.STREAK, setOf(MissionKind.WEEKLY), CountRange.VALIDITY, HarderTarget.HIGHER, null, TargetForm.POSITIVE_WHOLE) { _, i ->
            streakDays(i.drives, i.tzOffsetMs).toFloat()
        }
    }

    /**
     * 최근 운전한 날부터 거꾸로, 감점 없는 날이 며칠 연속인지. 운전하지 않은 날은 건너뛴다.
     * 감점이 있는 주행이 하나라도 있는 날에서 멈춘다.
     */
    fun streakDays(drives: List<SessionRecord>, tzOffsetMs: Long = Time.KST_OFFSET_MS): Int {
        val days = drives.groupBy { Time.dayIndex(it.startWallMs, tzOffsetMs) }.toSortedMap(compareByDescending { it })
        var n = 0
        for (rs in days.values) { if (rs.all { it.clean }) n++ else break }
        return n
    }

    /**
     * 유효 주행 하나를 센 뒤의 판정.
     * [daysLeftAfterToday] 는 오늘 다음 날부터 유효시간 끝까지 남은 날 수. 연속 조건이 더는 채울 수 없는지 볼 때 쓴다.
     */
    fun afterDrive(compare: Compare, range: CountRange, counted: Float?, target: Float, daysLeftAfterToday: Long): Verdict {
        if (counted == null) return Verdict.CONTINUE
        return when (compare) {
            Compare.AT_MOST -> when {
                counted > target -> Verdict.FAIL
                range == CountRange.SINGLE_DRIVE -> Verdict.SUCCEED
                else -> Verdict.CONTINUE
            }
            Compare.AT_LEAST -> when {
                counted >= target -> Verdict.SUCCEED
                range == CountRange.SINGLE_DRIVE -> Verdict.FAIL
                else -> Verdict.CONTINUE
            }
            Compare.ANY -> when {
                counted >= HAPPENED -> Verdict.SUCCEED
                range == CountRange.SINGLE_DRIVE -> Verdict.FAIL
                else -> Verdict.CONTINUE
            }
            Compare.STREAK -> when {
                counted >= target -> Verdict.SUCCEED
                counted + daysLeftAfterToday < target -> Verdict.FAIL
                else -> Verdict.CONTINUE
            }
        }
    }

    /**
     * 유효시간이 끝날 때까지 판정되지 않은 도전 미션의 판정.
     * 센 근거가 없거나 유효 주행이 없었으면 미판정, 있었으면 실패.
     * 유효시간 동안 세는 이하 조건만은 끝까지 목표를 넘지 않았으면 성공이다.
     */
    fun atExpiry(compare: Compare, range: CountRange, counted: Float?, target: Float, drives: Int): Verdict = when {
        counted == null || drives == 0 -> Verdict.UNDETERMINED
        compare == Compare.AT_MOST && range == CountRange.VALIDITY && counted <= target -> Verdict.SUCCEED
        else -> Verdict.FAIL
    }
}

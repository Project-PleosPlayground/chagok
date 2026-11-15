package core.mission

import kotlinx.serialization.Serializable

/*
 * 미션 모델: 조건과 종류(enum), 미션 데이터 표(콘텐츠), 받은 미션. 기준은 기획/07_미션.md (#14).
 * ConditionType·MissionKind 는 앱이 처리할 수 있는 값의 목록이다. 값을 더하거나 빼려면 판정·표시 코드가
 * 같이 바뀌어야 하므로 원격 콘텐츠 업데이트(#31)로는 바꾸지 않고 스토어 업데이트로 배포한다.
 * 목표치·보상·제공 개수 같은 숫자는 콘텐츠 묶음의 [MissionCatalog] 에 있다.
 */

/**
 * 미션 조건. 엔진·차량 상태로 자동 판정할 수 있는 것만 둔다. 판정 방법은 [ConditionRules].
 * 목표치 단위: 횟수(회), 거리(km), 비율·잔량(%), 일수(일).
 * [id] 는 기획/데이터_컬럼_사전.md "조건 번호표"의 조건ID 다. 콘텐츠는 이 번호로 조건을 가리킨다.
 * 번호는 다시 쓰지 않는다. 조건을 없애도 그 번호는 비워 두고, 새 조건은 다음 번호를 받는다.
 */
enum class ConditionType(val id: Int) {
    HARSH_BRAKE_MAX(1),         // 급제동 이하 (회)
    HARSH_ACCEL_MAX(2),         // 급가속 이하 (회)
    LANE_NO_SIGNAL_MAX(3),      // 깜빡이 없는·반대 차선변경 이하 (회)
    SIGNALED_RATE_MIN(4),       // 깜빡이 켠 차선변경 비율 이상 (%)
    CLEAN_KM(5),                // 감점 없는 거리 (km)
    SEATBELT_BEFORE_START(6),   // 출발 전 안전벨트 (목표치 없음)
    CHARGE_STOP_AT_80(7),       // 충전을 목표치 % 이하에서 멈춤 (%)
    NO_BELOW_10_PCT(8),         // 배터리가 목표치 % 밑으로 안 내려감 (%)
    STREAK_DAYS(9);             // 감점 없는 날 연속 (일). 운전하지 않은 날은 건너뛴다

    companion object {
        fun byId(id: Int): ConditionType? = entries.firstOrNull { it.id == id }
    }
}

/** 미션 종류. 제공 개수, 유효시간, 갱신 시각이 다르다: 개인 미션은 하루, 주간 미션은 한 주 */
@Serializable
enum class MissionKind { PERSONAL, WEEKLY }

/** 받은 미션 한 건의 상태. 런타임 값이라 콘텐츠에는 나오지 않는다. */
enum class MissionStatus {
    /** 제공됨. 도전하지 않아 판정하지 않는다 */
    OFFERED,
    /** 도전 중. 도전 슬롯에 있고 판정한다 */
    IN_PROGRESS,
    /** 성공. 보상을 줬다 */
    SUCCEEDED,
    /** 실패 */
    FAILED,
    /** 도전했지만 유효시간이 끝날 때까지 판정할 근거가 없었다. 슬롯에서 지운다 */
    UNDETERMINED,
    /** 사용자가 도전을 취소했다. 패널티를 깎았다 */
    CANCELLED,
    /** 도전하지 않은 채 갱신 때 사라졌다 */
    EXPIRED;

    /** 판정이 끝남 (성공, 실패) */
    val judged: Boolean get() = this == SUCCEEDED || this == FAILED
}

/** 미션(조합) 표 한 행 (기획/데이터_컬럼_사전.md "미션(조합)"). 조건마다 하나 */
@Serializable
data class ConditionRow(
    /** 조건ID ("조건 번호표") */
    val conditionId: Int,
    /** 카드 제목 틀의 콘텐츠 텍스트 키 */
    val nameKey: String,
    /** 카드 설명 틀의 콘텐츠 텍스트 키 */
    val descriptionKey: String,
    /** 대표 아이콘 파일 이름 (영문 소문자·숫자·밑줄). 없으면 기본 아이콘 */
    val iconKey: String? = null,
    /** 1단계부터 차례로 단계마다의 목표치. 단위는 조건마다 다르다. null 이면 그 단계 없음 */
    val tierTargets: List<Float?>,
) {
    /** 조건ID 에 맞는 조건. 앱이 모르는 번호면 null */
    val condition: ConditionType? get() = ConditionType.byId(conditionId)
    /** 그 단계의 목표치. 단계가 없으면 null */
    fun target(tier: Int): Float? = tierTargets.getOrNull(tier - MissionEngine.FIRST_TIER)
    /** 목표치가 있는 단계 */
    val tiers: List<Int> get() = tierTargets.indices.filter { tierTargets[it] != null }.map { it + MissionEngine.FIRST_TIER }
}

/**
 * 콘텐츠 텍스트 (기획/데이터_컬럼_사전.md "콘텐츠 텍스트") 중 미션 글. 키 → 문구. 문구의 `{n}` 자리에 목표치가 들어간다.
 * 목표치가 0이면 "키_zero" 문구가 있을 때 그것을 쓴다 (07: "0회 이하" 대신 "없이").
 */
object MissionText {
    const val ZERO_SUFFIX = "_zero"

    /** 목표치를 넣은 글. 문구가 없으면 키를 그대로 쓴다 */
    fun of(texts: Map<String, String>, key: String, target: Float): String {
        val text = (if (target == 0f) texts[key + ZERO_SUFFIX] else null) ?: texts[key] ?: key
        return MissionEngine.fill(text, target)
    }
}

/** 미션 단계 보상 표 한 행 (기획/데이터_컬럼_사전.md "미션 단계 보상"). 모든 조건이 같이 쓴다 */
@Serializable
data class TierReward(val tier: Int, val coins: Int, val xp: Int)

/** 미션 제공·도전 수치 (07 "미션 종류와 제공", "도전", "보상") */
@Serializable
data class MissionRules(
    /** 하루에 제공하는 개인 미션 수 */
    val personalPerDay: Int,
    /** 도전하지 않은 주간 미션 목록의 개수. 도전하면 이 개수로 다시 채운다 */
    val weeklyPerWeek: Int,
    /** 도전 슬롯 칸 수. 개인·주간 미션이 같이 쓴다 */
    val slotCount: Int,
    /** 유효 주행 한 번을 마치면 주는 경험치 */
    val validDriveXp: Int,
    /** 취소 패널티 배수. 취소하면 그 미션 보상 × 이 값을 깎는다 (07 "패널티") */
    val cancelPenaltyMultiplier: Int,
    /** 실패 패널티 배수. 실패하면 그 미션 보상 × 이 값을 깎는다 (07 "패널티") */
    val failPenaltyMultiplier: Int,
)

/** 미션 데이터 한 묶음. 콘텐츠 묶음([core.content.ContentPack])에 들어간다 */
@Serializable
data class MissionCatalog(
    val conditions: List<ConditionRow>,
    val tierRewards: List<TierReward>,
    val rules: MissionRules,
    /** 미션 이름·설명 문구. 키 → 문구 ([MissionText]) */
    val texts: Map<String, String> = emptyMap(),
) {
    fun name(row: ConditionRow, target: Float) = MissionText.of(texts, row.nameKey, target)
    fun description(row: ConditionRow, target: Float) = MissionText.of(texts, row.descriptionKey, target)
    fun row(c: ConditionType): ConditionRow? = conditions.firstOrNull { it.condition == c }
    fun reward(tier: Int): TierReward? = tierRewards.firstOrNull { it.tier == tier }
}

/**
 * 프로필이 받은 미션 한 건. 시각은 모두 wall-clock 시각(epoch ms).
 * 목표·보상·이름·설명은 제공할 때 표에서 정해 적어 둔다. 콘텐츠가 바뀌어도 이 미션은 바뀌지 않는다 (#31).
 */
data class MissionInstance(
    /** 받은 미션 한 건의 고유 id (UUID). 저장 키, 로그 참조 */
    val uid: String,
    val profileId: String,
    val kind: MissionKind,
    val condition: ConditionType,
    /** 단계 1~4 */
    val tier: Int,
    val target: Float,
    val rewardCoins: Int,
    val rewardXp: Int,
    val title: String,
    val description: String,
    val iconKey: String?,
    val offeredMs: Long,
    /** 유효시간이 끝나는 시각. 이 시각부터는 유효시간 밖이다 */
    val validUntilMs: Long,
    val status: MissionStatus,
    val challengedMs: Long? = null,
    /** 성공·실패·미판정·취소·사라짐이 정해진 시각 */
    val endedMs: Long? = null,
    /** 판정 끝난 미션을 도전 슬롯에서 비운 시각. 사용자가 비울 때, 날짜가 바뀔 때, 유효시간 끝에 판정할 때 */
    val clearedMs: Long? = null,
    /** 진행도의 센 값. 화면은 "센 값/목표치"로 보인다. 아직 셀 근거가 없으면 null */
    val counted: Float? = null,
    /** 판정에 반영한 유효 주행 수 */
    val drives: Int = 0,
) {
    /** 도전 슬롯을 차지하는지: 도전 중이거나, 판정이 끝났고 아직 비우지 않음 */
    val inSlot: Boolean get() = status == MissionStatus.IN_PROGRESS || (status.judged && clearedMs == null)
    fun remainingMs(nowMs: Long) = (validUntilMs - nowMs).coerceAtLeast(0)
}

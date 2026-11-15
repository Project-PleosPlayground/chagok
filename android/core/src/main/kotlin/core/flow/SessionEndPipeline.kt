package core.flow

import core.character.CharacterProgress
import core.character.GrowthModule
import core.character.ProfileState
import core.character.ProfileStore
import core.character.Wallet
import core.content.BundledContent
import core.content.ContentPack
import core.mission.MissionEngine
import core.saturatingAdd
import core.session.EndAnimation
import core.session.SessionRecord
import core.session.SessionStore
import core.session.VehicleStatus
import engine.EventType

/** 주행 종료 화면이 필요로 하는 것 한 묶음. */
data class SessionEndSummary(
    val record: SessionRecord,
    val completed: List<MissionEngine.Completed>,
    /** 이 주행이 끝날 때 실패로 판정한 미션과 실패 패널티 */
    val failed: List<MissionEngine.Failed>,
    /** 성공 보상으로 늘어난 포인트·경험치 */
    val coinsGained: Int,
    val xpGained: Int,
    /** 실패 패널티로 실제로 깎인 포인트·경험치. 0 이상 */
    val coinsLost: Int,
    val xpLost: Int,
    /** 보상과 패널티를 모두 반영한 뒤에도 올라 있는 단계 */
    val leveledUpTo: Int?,
    /** 코인·아이템 등 프로필 상태 (반영 후) */
    val profile: ProfileState,
    /** 지금 고른 캐릭터의 성장 (반영 후). 캐릭터를 고르지 않은 프로필이면 null 이고 경험치는 주지 않는다 */
    val character: CharacterProgress?,
    /** 점수대 → 종료 애니메이션. 구간은 ContentPack.scoreBands, 미인정 세션은 NONE */
    val animation: EndAnimation,
    /** AI 코멘트 자리. AiClient 가 나중에 채우고, 없으면 규칙 문장 */
    val comment: String,
    /** [comment] 가 AI 리포트인지. 규칙 문장이면 false. 주행 결과 화면이 "AI 분석" 표시를 붙일지 정한다 */
    val aiGenerated: Boolean = false,
    /** AI 가 준 다음 주행 팁. 없으면 화면이 규칙 팁을 쓴다 */
    val tip: String? = null,
    /** AI 가 쓴 3컷 만화 대사. 없으면 화면이 규칙 대사를 쓴다 */
    val comic: List<String>? = null,
    /** 이번 주행에서 받은 장소 선물 (11/15 드라이빙 이벤트) */
    val placeGifts: List<PlaceGift> = emptyList(),
)

/** 장소 선물 한 건: 받은 아이템 이름, 그 장소 이름, 그림 */
data class PlaceGift(val itemName: String, val placeName: String, val imageKey: String)

/**
 * 세션 종료 → 저장 → 미션 판정 → 경험치·코인 지급 → 요약. 엔진 sessions Flow 를 받는 쪽에서 한 번 부른다.
 * 전부 동기·순수 로직이라 JUnit 으로 검증한다. AI 코멘트는 밖에서 비동기로 덧씌운다.
 */
class SessionEndPipeline(
    private val sessions: SessionStore,
    private val profiles: ProfileStore,
    private val missions: MissionEngine,
    private val growth: GrowthModule = GrowthModule(),
    /** 지금 쓰는 콘텐츠 묶음 (#31). :app 은 ContentState.active 를 넘긴다 */
    private val content: () -> ContentPack = { BundledContent.pack },
    private val wallet: Wallet = Wallet { content().items },
) {
    private val rewards = Rewards(profiles, growth, wallet)

    /**
     * 주행이 끝났을 때. 유효 주행이 아니어도 부른다: 주행 보전으로 남아 있던 미션을 이때 정리한다.
     * 보상 = 성공한 미션의 보상 + 유효 주행이면 유효 주행 경험치. 보상을 준 뒤 실패한 미션의 실패 패널티를 깎는다.
     */
    fun onSessionEnd(record: SessionRecord, status: VehicleStatus, nowMs: Long): SessionEndSummary {
        sessions.save(record)
        val judged = missions.onDriveEnd(record, status, nowMs)
        val completed = judged.completed
        val driveXp = if (record.valid) content().missions.rules.validDriveXp else 0
        val coins = completed.fold(0) { a, c -> saturatingAdd(a, c.coins) }
        val xp = completed.fold(driveXp) { a, c -> saturatingAdd(a, c.xp) }
        // 유효하지 않은 주행에서 받은 보상이 없으면 프로필·캐릭터를 저장하지 않는다
        val gained = if (!record.valid && completed.isEmpty()) rewards.current(record.profileId) else rewards.give(record.profileId, coins, xp)
        val penalty = judged.penalty
        val lost = if (judged.failed.isEmpty()) null else rewards.take(record.profileId, penalty.coins, penalty.xp)
        val final = lost ?: gained
        val leveledUpTo = gained.leveledUpTo?.takeIf { up -> (final.character?.stage ?: 0) >= up }
        val anim = if (record.valid) content().animationFor(record.score) else EndAnimation.NONE
        return SessionEndSummary(
            record, completed, judged.failed, gained.coins, gained.xp, -(lost?.coins ?: 0), -(lost?.xp ?: 0),
            leveledUpTo, final.profile, final.character, anim, fallbackComment(record),
        )
    }

    /** 통신 없을 때 쓰는 규칙 문장 (ai-poc fallback 과 같은 톤) */
    fun fallbackComment(r: SessionRecord): String {
        if (!r.valid) return "조금 짧은 드라이브였네. 다음에 더 같이 달리자."
        val hb = r.count(EventType.HARSH_BRAKE); val ha = r.count(EventType.HARSH_ACCEL)
        val ln = r.count(EventType.LANE_CHANGE_NO_SIGNAL) + r.count(EventType.LANE_CHANGE_WRONG_SIGNAL)
        if (hb + ha + ln == 0) return "오늘은 한 번도 안 놀랐어. 이런 날이 제일 좋아."
        val parts = buildList { if (hb > 0) add("급하게 멈춘 게 ${hb}번"); if (ha > 0) add("확 밟은 게 ${ha}번"); if (ln > 0) add("깜빡이 없이 옮긴 게 ${ln}번") }
        return parts.joinToString(", ") + " 있었어. 다음엔 한 박자만 더 여유 있게 가 보자."
    }
}

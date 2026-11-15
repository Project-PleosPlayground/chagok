package core.flow

import core.mission.MissionEngine

/**
 * 화면에서 하는 미션 동작(도전·취소·비움)과 틱 갱신. 엔진의 결과를 받아 보상·패널티를 코인·경험치에 반영한다.
 * [driving] 은 지금 주행 중인지, [driveStartMs] 는 주행 중이면 그 주행의 시작 시각.
 */
class MissionActions(
    private val missions: MissionEngine,
    private val rewards: Rewards,
) {
    fun challenge(profileId: String, uid: String, nowMs: Long, driving: Boolean) = missions.challenge(profileId, uid, nowMs, driving)

    /** 취소하면 취소 패널티(그 미션의 보상만큼)를 코인·경험치에서 깎는다 */
    fun cancel(profileId: String, uid: String, nowMs: Long, driving: Boolean): MissionEngine.SlotResult {
        val r = missions.cancel(profileId, uid, nowMs, driving)
        if (r is MissionEngine.SlotResult.Ok) r.penalty?.let { rewards.take(profileId, it.coins, it.xp) }
        return r
    }

    fun clear(profileId: String, uid: String, nowMs: Long, driving: Boolean) = missions.clear(profileId, uid, nowMs, driving)

    /** 앱 시작·1분 틱 때. 제공·갱신하고, 유효시간 끝에 성공한 미션의 보상을 준 뒤 실패한 미션의 실패 패널티를 깎는다 */
    fun refresh(profileId: String, nowMs: Long, driveStartMs: Long?): MissionEngine.Judged {
        val done = missions.refresh(profileId, nowMs, driveStartMs)
        val c = done.completed
        if (c.isNotEmpty()) rewards.give(profileId, c.sumOf { it.coins }, c.sumOf { it.xp })
        if (done.failed.isNotEmpty()) done.penalty.let { rewards.take(profileId, it.coins, it.xp) }
        return done
    }
}

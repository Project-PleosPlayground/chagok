package ai.safetypoc.app.gleo

import ai.safetypoc.app.ui.home.HomeState
import ai.safetypoc.app.ui.mission.MISSION_ZONE
import ai.safetypoc.app.ui.mission.MissionCard
import core.session.SessionRecord
import engine.EventType
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/**
 * Gleo 질의에 답한다. 셋 다 조회만 하고 앱 데이터를 바꾸지 않는다 (#3).
 * 점수는 주행 중에 알려 주지 않는다. 점수와 리포트는 정차 후에 보여 준다 (01_앱개요).
 */
class GleoAnswers(
    /** 주행 중인지 (주행 버튼, 엔진 세션, 시스템 주행 규제) */
    private val driving: () -> Boolean,
    /** 홈 화면 값: 캐릭터 이름, 단계, 경험치, 포인트 */
    private val pet: () -> HomeState,
    private val maxStage: Int,
    /** 도전 슬롯 카드. 주행 중이면 지금 주행까지 센 값 */
    private val slot: () -> List<MissionCard>,
    /** 도전할 수 있는 제공 미션 수 */
    private val offered: () -> Int,
    /** 마지막 유효 주행. 없으면 null */
    private val lastDrive: () -> SessionRecord?,
    private val zone: ZoneId = MISSION_ZONE,
) {
    fun answer(function: GleoFunction): GleoReply = when (function) {
        GleoFunction.GET_DRIVE_SCORE -> driveScore()
        GleoFunction.GET_PET_STATUS -> petStatus()
        GleoFunction.GET_MISSION_PROGRESS -> missionProgress()
    }

    private fun driveScore(): GleoReply {
        val f = GleoFunction.GET_DRIVE_SCORE
        if (driving()) return GleoReply(f, GleoStatus.SUCCESS, "The safety score will be available after the current drive ends.")
        val s = lastDrive() ?: return GleoReply(f, GleoStatus.SUCCESS, "There is no completed drive with a safety score yet.")
        val ended = Instant.ofEpochMilli(s.endWallMs).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        return GleoReply(
            f, GleoStatus.SUCCESS, "The safety score of the last drive was ${s.score} out of $SCORE_MAX.",
            listOf(
                mapOf(
                    "score" to s.score,
                    "maxScore" to SCORE_MAX,
                    "distanceKm" to (s.distanceKm * DISTANCE_SCALE).roundToInt() / DISTANCE_SCALE.toDouble(),
                    "harshBraking" to s.count(EventType.HARSH_BRAKE),
                    "harshAcceleration" to s.count(EventType.HARSH_ACCEL),
                    "laneChangeWithoutSignal" to s.count(EventType.LANE_CHANGE_NO_SIGNAL),
                    "laneChangeWrongSignal" to s.count(EventType.LANE_CHANGE_WRONG_SIGNAL),
                    "endedAt" to ended.toString(),
                ),
            ),
        )
    }

    private fun petStatus(): GleoReply {
        val f = GleoFunction.GET_PET_STATUS
        val p = pet()
        val name = p.characterName ?: return GleoReply(f, GleoStatus.SUCCESS, "No pet character has been chosen yet.")
        val atMax = p.stage >= maxStage
        val toNext = if (atMax) 0 else (p.xpSpan - p.xpInStage).coerceAtLeast(0)
        val message = if (atMax) "$name has reached the final stage, stage $maxStage."
        else "$name is at stage ${p.stage} of $maxStage and needs ${plural(toNext, "more experience point")} to grow."
        return GleoReply(
            f, GleoStatus.SUCCESS, message,
            listOf(mapOf("petName" to name, "stage" to p.stage, "maxStage" to maxStage, "experienceToNextStage" to toNext, "points" to p.coins)),
        )
    }

    private fun missionProgress(): GleoReply {
        val f = GleoFunction.GET_MISSION_PROGRESS
        val cards = slot()
        if (cards.isEmpty()) {
            val n = offered()
            val message = if (n == 0) "No mission is being challenged now."
            else "No mission is being challenged now, and ${plural(n, "mission")} can be started in the app while parked."
            return GleoReply(f, GleoStatus.SUCCESS, message, listOf(mapOf("availableMissions" to n)))
        }
        return GleoReply(
            f, GleoStatus.SUCCESS, "${plural(cards.size, "mission")} ${if (cards.size == 1) "is" else "are"} in the challenge slot.",
            cards.map { c ->
                mapOf(
                    "title" to c.title,
                    "state" to c.status.name.lowercase(),
                    "progress" to c.progress,
                    "rewardPoints" to c.rewardCoins,
                    "rewardExperience" to c.rewardXp,
                )
            },
        )
    }

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    private companion object {
        /** 세션 점수 만점 (스펙_안전점수: 기본 100점에서 깎는다) */
        const val SCORE_MAX = 100
        /** 거리는 소수 첫째 자리까지 */
        const val DISTANCE_SCALE = 10
    }
}

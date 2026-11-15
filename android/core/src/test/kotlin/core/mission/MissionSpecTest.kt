package core.mission

import core.Fx
import core.Mx
import core.character.CharacterId
import core.character.CharacterProgress
import core.character.GrowthModule
import core.character.InMemoryProfileStore
import core.character.Profile
import core.character.ProfileState
import core.character.Wallet
import core.content.BundledContent
import core.content.ContentCodec
import core.flow.MissionActions
import core.flow.Rewards
import core.flow.SessionEndPipeline
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 기획/07_미션.md, 기획/04_캐릭터_성장.md 사양만 보고 쓴 테스트 (독립 테스터 1단계).
 * 시각은 Fx.MON(월요일 00:00 KST) 기준.
 */
class MissionSpecTest {

    private val P = Profile.DEFAULT
    private val H = 3_600_000L
    private val MIN = 60_000L
    private val DAY = Fx.DAY
    private val MON = Fx.MON

    private inner class Env(personal: Int = 0, weekly: Int = 0, seed: Int = 7) {
        val store = InMemoryMissionStore()
        val sessions = InMemorySessionStore()
        var catalog: MissionCatalog = Mx.catalog(personal, weekly)
        val engine = MissionEngine(store, sessions, missions = { catalog }, random = Random(seed))
        private var n = 0

        fun offerAndChallenge(kind: MissionKind, cond: ConditionType, tier: Int, at: Long): MissionInstance {
            val m = engine.offer(P, kind, cond, tier, at)
            assertNotNull(m, "offer($kind, $cond, $tier) 가 null")
            val r = engine.challenge(P, m!!.uid, at, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "도전 실패: $r")
            return (r as MissionEngine.SlotResult.Ok).instance
        }

        fun drive(
            start: Long, km: Float = 10f, minutes: Int = 30, valid: Boolean = true,
            events: List<DrivingEvent> = emptyList(), status: VehicleStatus = VehicleStatus(),
        ): List<MissionEngine.Completed> {
            val r = Fx.record("s${n++}", start, km, minutes, valid, events)
            sessions.save(r)
            return engine.onDriveEnd(r, status, r.endWallMs).completed
        }

        fun get(uid: String): MissionInstance = store.list(P).first { it.uid == uid }
        fun status(uid: String) = get(uid).status
    }

    private fun evs(type: EventType, count: Int) = List(count) { Fx.ev(type, 10_000L + it * 1_000L) }

    // ───────────────────────── 제공과 갱신 ─────────────────────────

    @Nested
    inner class OfferAndRenewal {

        @Test
        fun `하루 개인 미션 2개, 한 주 주간 미션 10개를 제공한다`() {
            val e = Env(personal = 2, weekly = 10)
            val now = MON + 9 * H
            e.engine.refresh(P, now, null)
            val offers = e.engine.offers(P, now)
            assertEquals(2, offers.count { it.kind == MissionKind.PERSONAL })
            assertEquals(10, offers.count { it.kind == MissionKind.WEEKLY })
            assertTrue(offers.all { it.status == MissionStatus.OFFERED })
        }

        @Test
        fun `같은 날 다시 refresh 해도 개인 미션은 바뀌지 않는다`() {
            val e = Env(personal = 2, weekly = 10)
            e.engine.refresh(P, MON + 9 * H, null)
            val a = e.engine.offers(P, MON + 9 * H).map { it.uid }.toSet()
            e.engine.refresh(P, MON + 20 * H, null)
            val b = e.engine.offers(P, MON + 20 * H).map { it.uid }.toSet()
            assertEquals(a, b)
        }

        @Test
        fun `00시에 개인 미션은 새로 바뀌고 주간 미션은 그 주 동안 유지된다`() {
            val e = Env(personal = 2, weekly = 10)
            e.engine.refresh(P, MON + 9 * H, null)
            val before = e.engine.offers(P, MON + 9 * H)
            val tue = MON + DAY + MIN
            e.engine.refresh(P, tue, null)
            val after = e.engine.offers(P, tue)
            val oldPersonal = before.filter { it.kind == MissionKind.PERSONAL }.map { it.uid }.toSet()
            val newPersonal = after.filter { it.kind == MissionKind.PERSONAL }
            assertEquals(2, newPersonal.size)
            assertTrue(newPersonal.none { it.uid in oldPersonal }, "도전하지 않은 어제 개인 미션이 남아 있다")
            assertEquals(
                before.filter { it.kind == MissionKind.WEEKLY }.map { it.uid }.toSet(),
                after.filter { it.kind == MissionKind.WEEKLY }.map { it.uid }.toSet(),
            )
        }

        @Test
        fun `월요일 00시에 주간 미션이 새로 바뀐다`() {
            val e = Env(personal = 2, weekly = 10)
            e.engine.refresh(P, MON + 9 * H, null)
            val oldWeekly = e.engine.offers(P, MON + 9 * H).filter { it.kind == MissionKind.WEEKLY }.map { it.uid }.toSet()
            val nextMon = MON + 7 * DAY + MIN
            e.engine.refresh(P, nextMon, null)
            val newWeekly = e.engine.offers(P, nextMon).filter { it.kind == MissionKind.WEEKLY }
            assertEquals(10, newWeekly.size)
            assertTrue(newWeekly.none { it.uid in oldWeekly })
        }

        @Test
        fun `주행 중이어도 00시에 도전하지 않은 미션은 갱신한다`() {
            val e = Env(personal = 2, weekly = 0)
            e.engine.refresh(P, MON + 20 * H, null)
            val old = e.engine.offers(P, MON + 20 * H).map { it.uid }.toSet()
            val driveStart = MON + 23 * H + 30 * MIN
            val tue = MON + DAY + MIN
            e.engine.refresh(P, tue, driveStart)
            val now = e.engine.offers(P, tue)
            assertEquals(2, now.count { it.kind == MissionKind.PERSONAL })
            assertTrue(now.none { it.uid in old })
        }

        @Test
        fun `주 중간에 처음 켜도 그 주 주간 미션을 주고 유효시간은 그 주 일요일 23시59분까지`() {
            val e = Env(personal = 2, weekly = 10)
            val thu = MON + 3 * DAY + 15 * H
            e.engine.refresh(P, thu, null)
            val weekly = e.engine.offers(P, thu).filter { it.kind == MissionKind.WEEKLY }
            assertEquals(10, weekly.size)
            val weekEnd = MON + 7 * DAY
            weekly.forEach {
                assertTrue(it.validUntilMs in (weekEnd - MIN)..weekEnd, "validUntil=${it.validUntilMs}, 주 끝=$weekEnd")
            }
        }

        @Test
        fun `validUntil - 개인은 그날 23시59분, 주간은 그 주 일요일 23시59분`() {
            val e = Env()
            val wed = MON + 2 * DAY + 10 * H
            val p = e.engine.validUntil(MissionKind.PERSONAL, wed)
            val w = e.engine.validUntil(MissionKind.WEEKLY, wed)
            val dayEnd = MON + 3 * DAY
            val weekEnd = MON + 7 * DAY
            assertTrue(p in (dayEnd - MIN)..dayEnd, "개인 validUntil=$p")
            assertTrue(w in (weekEnd - MIN)..weekEnd, "주간 validUntil=$w")
        }

        @Test
        fun `연속 일수는 주간 미션에만 있다`() {
            val e = Env()
            assertNull(e.engine.offer(P, MissionKind.PERSONAL, ConditionType.STREAK_DAYS, 1, MON + H))
            assertNotNull(e.engine.offer(P, MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H))
        }

        @Test
        fun `랜덤 제공 개인 미션에 연속 일수가 나오지 않는다`() {
            val e = Env(personal = 2, weekly = 10, seed = 42)
            for (d in 0 until 60) {
                val t = MON + d * DAY + H
                e.engine.refresh(P, t, null)
                assertTrue(e.engine.offers(P, t).none { it.kind == MissionKind.PERSONAL && it.condition == ConditionType.STREAK_DAYS })
            }
        }

        @Test
        fun `목표치와 보상은 조건 표·단계별 보상 표에서 온다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, MON + H)!!
            assertEquals(1f, m.target)
            assertEquals(60, m.rewardCoins)
            assertEquals(15, m.rewardXp)
            assertEquals(3, m.tier)
        }

        @Test
        fun `이름 틀의 n 자리에 목표치가 들어간다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            assertFalse(m.title.contains("{n}"))
            assertFalse(m.description.contains("{n}"))
            assertTrue(m.title.contains("3"), "title=${m.title}")
        }

        @Test
        fun `목표치가 0이면 이름과 설명에 0회 이하 대신 없이를 쓴다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H)!!
            assertEquals(0f, m.target)
            assertTrue(m.title.contains("없이"), "title=${m.title}")
            assertFalse(m.title.contains("0회"), "title=${m.title}")
            assertTrue(m.description.contains("없이"), "description=${m.description}")
            assertFalse(m.description.contains("0회"), "description=${m.description}")
        }

        @Test
        fun `미션 이름은 15자, 설명은 30자 이하`() {
            val e = Env()
            for (c in ConditionType.values()) for (tier in 1..4) {
                val m = e.engine.offer(P, MissionKind.WEEKLY, c, tier, MON + H) ?: continue
                assertTrue(m.title.length <= 15, "$c/$tier title=${m.title} (${m.title.length})")
                assertTrue(m.description.length <= 30, "$c/$tier description=${m.description} (${m.description.length})")
            }
        }
    }

    // ───────────────────────── 도전 슬롯 ─────────────────────────

    @Nested
    inner class Slot {

        @Test
        fun `슬롯은 3칸이고 개인과 주간이 같이 쓴다`() {
            val e = Env()
            val t = MON + H
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 1, t)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            val fourth = e.engine.offer(P, MissionKind.WEEKLY, ConditionType.CLEAN_KM, 1, t)!!
            val r = e.engine.challenge(P, fourth.uid, t, driving = false)
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.SLOT_FULL), r)
            assertEquals(3, e.engine.slot(P).size)
        }

        @Test
        fun `취소한 슬롯에는 바로 다른 미션을 넣을 수 있고 취소한 미션은 사라진다`() {
            val e = Env()
            val t = MON + H
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 1, t)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            val c = e.engine.cancel(P, a.uid, t + MIN, driving = false)
            assertTrue(c is MissionEngine.SlotResult.Ok, "$c")
            assertEquals(MissionStatus.CANCELLED, e.status(a.uid))
            assertTrue(e.engine.slot(P).none { it.uid == a.uid })
            assertTrue(e.engine.offers(P, t + MIN).none { it.uid == a.uid }, "취소한 미션이 도전 전 상태로 돌아갔다")
            val d = e.engine.offer(P, MissionKind.WEEKLY, ConditionType.CLEAN_KM, 1, t + MIN)!!
            assertTrue(e.engine.challenge(P, d.uid, t + 2 * MIN, driving = false) is MissionEngine.SlotResult.Ok)
        }

        @Test
        fun `판정 끝난 미션도 비우기 전에는 슬롯을 차지하고 비우면 바로 넣을 수 있다`() {
            val e = Env()
            val t = MON + H
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.CLEAN_KM, 4, t)
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 4, t)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))
            assertTrue(e.get(a.uid).inSlot)
            val d = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 3 * H)!!
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.SLOT_FULL),
                e.engine.challenge(P, d.uid, MON + 3 * H, driving = false))
            assertTrue(e.engine.clear(P, a.uid, MON + 3 * H, driving = false) is MissionEngine.SlotResult.Ok)
            assertFalse(e.get(a.uid).inSlot)
            assertTrue(e.engine.slot(P).none { it.uid == a.uid })
            assertTrue(e.engine.challenge(P, d.uid, MON + 3 * H, driving = false) is MissionEngine.SlotResult.Ok)
        }

        @Test
        fun `실패한 미션도 비울 수 있다`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
            assertEquals(MissionStatus.FAILED, e.status(a.uid))
            assertTrue(e.engine.clear(P, a.uid, MON + 3 * H, driving = false) is MissionEngine.SlotResult.Ok)
            assertTrue(e.engine.slot(P).isEmpty())
        }

        @Test
        fun `판정 전 미션은 비울 수 없다`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            val r = e.engine.clear(P, a.uid, MON + 2 * H, driving = false)
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.WRONG_STATE), r)
        }

        @Test
        fun `없는 미션은 NOT_FOUND`() {
            val e = Env()
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.NOT_FOUND),
                e.engine.challenge(P, "no-such-uid", MON + H, driving = false))
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.NOT_FOUND),
                e.engine.cancel(P, "no-such-uid", MON + H, driving = false))
        }

        @Test
        fun `주행 중에는 도전·취소·비우기를 못 한다`() {
            val e = Env()
            val t = MON + H
            val offered = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)!!
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING),
                e.engine.challenge(P, offered.uid, t, driving = true))
            assertEquals(MissionStatus.OFFERED, e.status(offered.uid))

            val inProgress = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, t)
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING),
                e.engine.cancel(P, inProgress.uid, t, driving = true))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(inProgress.uid))

            val judged = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(judged.uid))
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING),
                e.engine.clear(P, judged.uid, MON + 3 * H, driving = true))
            assertTrue(e.get(judged.uid).inSlot)
        }

        @Test
        fun `유효시간이 지난 미션은 도전할 수 없다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            val r = e.engine.challenge(P, m.uid, MON + DAY + H, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Rejected, "$r")
        }

        @Test
        fun `비우지 않은 판정 끝난 미션은 다음 00시에 슬롯에서 없어진다 - 개인과 주간`() {
            val e = Env()
            val p = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            val w = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(p.uid))
            assertEquals(MissionStatus.SUCCEEDED, e.status(w.uid))
            e.engine.refresh(P, MON + 23 * H, null)
            assertEquals(2, e.engine.slot(P).size, "00시 전에는 남아 있어야 한다")
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertTrue(e.engine.slot(P).isEmpty(), "slot=${e.engine.slot(P).map { it.uid }}")
            assertEquals(MissionStatus.SUCCEEDED, e.status(p.uid))
            assertEquals(MissionStatus.SUCCEEDED, e.status(w.uid))
        }
    }

    // ───────────────────────── 판정 공통 ─────────────────────────

    @Nested
    inner class JudgingCommon {

        @Test
        fun `도전한 미션만 판정한다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            val done = e.drive(MON + 2 * H)
            assertTrue(done.isEmpty())
            assertEquals(MissionStatus.OFFERED, e.status(m.uid))
        }

        @Test
        fun `유효 주행 한 번이 도전 중인 모든 미션에 반영된다`() {
            val e = Env()
            val t = MON + H
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)
            val b = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 2, t)
            val c = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            val done = e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(setOf(a.uid, b.uid, c.uid), done.map { it.instance.uid }.toSet())
            assertEquals(20 + 40 + 20, done.sumOf { it.coins })
            assertEquals(5 + 10 + 5, done.sumOf { it.xp })
        }

        @Test
        fun `유효 주행이 아니면 세지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            val done = e.drive(MON + 2 * H, km = 0.5f, minutes = 2, valid = false, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertTrue(done.isEmpty())
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 3 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `성공하면 Completed 에 그 미션의 보상이 담긴다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H)
            val done = e.drive(MON + 2 * H)
            assertEquals(1, done.size)
            assertEquals(m.uid, done[0].instance.uid)
            assertEquals(40, done[0].coins)
            assertEquals(10, done[0].xp)
            assertEquals(MissionStatus.SUCCEEDED, done[0].instance.status)
            assertNotNull(e.get(m.uid).clearedMs ?: e.get(m.uid).endedMs)
        }

        @Test
        fun `실패하면 Completed 에 없다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H)
            val done = e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertTrue(done.none { it.instance.uid == m.uid && it.instance.status == MissionStatus.SUCCEEDED && it.coins > 0 })
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `도전 후 유효시간 끝까지 유효 주행이 없으면 미판정이고 슬롯에서 지운다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            e.drive(MON + 2 * H, km = 0.5f, minutes = 2, valid = false)
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertFalse(e.get(m.uid).inSlot)
            assertTrue(e.engine.slot(P).isEmpty())
        }

        @Test
        fun `주간 미션도 그 주 동안 유효 주행이 없으면 미판정`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.CLEAN_KM, 1, MON + H)
            e.engine.refresh(P, MON + 3 * DAY + H, null)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.engine.refresh(P, MON + 7 * DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertTrue(e.engine.slot(P).isEmpty())
        }

        @Test
        fun `한 번 판정된 미션은 다음 주행으로 바뀌지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H)
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            val done = e.drive(MON + 4 * H)
            assertTrue(done.none { it.instance.uid == m.uid })
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }
    }

    // ───────────────────────── 조건별 판정 ─────────────────────────

    @Nested
    inner class Conditions {

        @Test
        fun `급제동 이하 - 목표치와 같으면 성공, 센 값은 한 횟수`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 3회
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            val r = e.get(m.uid)
            assertEquals(MissionStatus.SUCCEEDED, r.status)
            assertEquals(3f, r.counted)
        }

        @Test
        fun `급제동 이하 - 목표치를 넘으면 실패, 다음 주행에 다시 도전하지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 3회
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `급제동 이하 - 도전 뒤 첫 유효 주행 하나로만 판정한다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 2회
            // 도전 전 주행은 세지 않는다
            val before = e.drive(MON - 2 * H, events = evs(EventType.HARSH_BRAKE, 9))
            assertTrue(before.isEmpty())
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 2))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `급제동 이하 - 다른 감점은 세지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H) // 0회
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_ACCEL, 3) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 2))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `급가속 이하 - 0회 목표에 1회면 실패, 0회면 성공`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 4, MON + H)
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_ACCEL, 1))
            assertEquals(MissionStatus.FAILED, e.status(a.uid))

            val e2 = Env()
            val b = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 4, MON + H)
            e2.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 2))
            assertEquals(MissionStatus.SUCCEEDED, e2.status(b.uid))
        }

        @Test
        fun `깜빡이 없는 차선변경 이하 - 성공과 실패`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.LANE_NO_SIGNAL_MAX, 2, MON + H) // 2회
            e.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_NO_SIGNAL, 2) + evs(EventType.LANE_CHANGE_SIGNALED, 5))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))

            val e2 = Env()
            val b = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.LANE_NO_SIGNAL_MAX, 2, MON + H)
            e2.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_NO_SIGNAL, 3))
            assertEquals(MissionStatus.FAILED, e2.status(b.uid))
        }

        @Test
        fun `깜빡이 비율 이상 - 차선변경 없는 주행은 건너뛰고 다음 주행으로 판정`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, MON + H) // 70%
            e.drive(MON + 2 * H)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 4 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 3) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1)) // 75%
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `깜빡이 비율 이상 - 목표치와 같으면 성공`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 2, MON + H) // 80%
            e.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 4) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1)) // 80%
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `깜빡이 비율 이상 - 목표치 미만이면 그 주행에서 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, MON + H) // 70%
            e.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 2) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1)) // 66.7%
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `깜빡이 비율 이상 - 유효시간 동안 차선을 안 바꾸면 미판정`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, MON + H)
            e.drive(MON + 2 * H)
            e.drive(MON + 5 * H)
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertFalse(e.get(m.uid).inSlot)
        }

        @Test
        fun `감점 없는 거리 - 감점 없는 유효 주행의 km를 더해 목표치에 닿은 주행 끝에 성공`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1, MON + H) // 5 km
            e.drive(MON + 2 * H, km = 3f)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            assertEquals(3f, e.get(m.uid).counted!!, 0.001f)
            e.drive(MON + 4 * H, km = 10f, events = evs(EventType.HARSH_BRAKE, 1)) // 감점 있음 → 안 셈
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            assertEquals(3f, e.get(m.uid).counted!!, 0.001f)
            e.drive(MON + 6 * H, km = 1.5f, valid = false) // 유효 아님 → 안 셈
            assertEquals(3f, e.get(m.uid).counted!!, 0.001f)
            val done = e.drive(MON + 8 * H, km = 2f)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            assertTrue(done.any { it.instance.uid == m.uid })
        }

        @Test
        fun `감점 없는 거리 - 깜빡이 켠 차선변경은 감점이 아니다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1, MON + H) // 5 km
            e.drive(MON + 2 * H, km = 6f, events = evs(EventType.LANE_CHANGE_SIGNALED, 3))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `감점 없는 거리 - 유효 주행이 있었는데 유효시간 끝까지 못 채우면 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H) // 10 km
            e.drive(MON + 2 * H, km = 3f)
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `감점 없는 거리 - 감점 있는 유효 주행만 있었어도 유효시간 끝에 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1, MON + H)
            e.drive(MON + 2 * H, km = 20f, events = evs(EventType.HARSH_ACCEL, 1))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `감점 없는 거리 - 주간 미션은 여러 날의 km를 더한다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.CLEAN_KM, 2, MON + H) // 10 km
            e.drive(MON + 2 * H, km = 4f)
            e.engine.refresh(P, MON + DAY + MIN, null)
            e.drive(MON + DAY + 2 * H, km = 4f)
            e.engine.refresh(P, MON + 2 * DAY + MIN, null)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 2 * DAY + 2 * H, km = 2f)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `출발 전 안전벨트 - 맸으면 성공, 안 맸으면 실패, 신호 없으면 다음 주행`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = null))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(a.uid))
            e.drive(MON + 4 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))

            val e2 = Env()
            val b = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e2.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
            assertEquals(MissionStatus.FAILED, e2.status(b.uid))
        }

        @Test
        fun `충전 멈춤 - 목표치 이하에서 멈춘 뒤 첫 유효 주행 끝에 성공`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CHARGE_STOP_AT_80, 3, MON + H) // 80%
            e.drive(MON + 2 * H, status = VehicleStatus(chargeStoppedAtPct = 80))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `충전 멈춤 - 목표치 위에서 멈춰도 바로 실패하지 않고 다음 충전으로 성공할 수 있다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CHARGE_STOP_AT_80, 3, MON + H) // 80%
            e.drive(MON + 2 * H, status = VehicleStatus(chargeStoppedAtPct = 95))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 5 * H, status = VehicleStatus(chargeStoppedAtPct = 75))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `충전 멈춤 - 충전을 한 번도 안 멈췄으면 유효시간 끝에 미판정`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CHARGE_STOP_AT_80, 3, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(chargeStoppedAtPct = null))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
        }

        @Test
        fun `충전 멈춤 - 목표치 위에서만 멈추고 유효시간이 끝나면 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CHARGE_STOP_AT_80, 3, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(chargeStoppedAtPct = 95))
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `배터리 지키기 - 그날 최저 잔량이 목표치 이상이면 성공, 밑이면 실패`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.NO_BELOW_10_PCT, 2, MON + H) // 15%
            e.drive(MON + 2 * H, status = VehicleStatus(minSocThisDayPct = 15))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))

            val e2 = Env()
            val b = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.NO_BELOW_10_PCT, 2, MON + H)
            e2.drive(MON + 2 * H, status = VehicleStatus(minSocThisDayPct = 14))
            assertEquals(MissionStatus.FAILED, e2.status(b.uid))
        }

        @Test
        fun `연속 일수 - 운전하지 않은 날은 건너뛰고 감점 없는 날을 이어 센다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 2 * H)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.engine.refresh(P, MON + DAY + MIN, null)
            e.engine.refresh(P, MON + 2 * DAY + MIN, null)   // 화요일은 운전 안 함
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            val done = e.drive(MON + 2 * DAY + 9 * H)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            assertTrue(done.any { it.instance.uid == m.uid })
        }

        @Test
        fun `연속 일수 - 하루에 감점 없는 주행을 여러 번 해도 하루로 센다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 2 * H)
            e.drive(MON + 5 * H)
            e.drive(MON + 9 * H)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 남은 날로 목표치를 못 채우게 되면 바로 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            for (d in 1..5) e.engine.refresh(P, MON + d * DAY + MIN, null)
            // 토요일 감점 있는 주행 → 남은 날은 일요일 하루, 최대 1일 < 2일
            e.drive(MON + 5 * DAY + 10 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 일요일 감점 주행으로 끊기면 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 5 * DAY + 10 * H) // 토 감점 없음 (1일)
            e.engine.refresh(P, MON + 6 * DAY + MIN, null)
            e.drive(MON + 6 * DAY + 10 * H, events = evs(EventType.HARSH_BRAKE, 1)) // 일 감점
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }
    }

    // ───────────────────────── 주행 보전 ─────────────────────────

    @Nested
    inner class DriveCarryOver {

        @Test
        fun `월요일 23시30분 출발, 화요일 00시20분 도착 - 도전 중 개인 미션은 그 주행으로 판정`() {
            val e = Env(personal = 2)
            e.engine.refresh(P, MON + 8 * H, null)
            val offered = e.engine.offers(P, MON + 8 * H).filter { it.kind == MissionKind.PERSONAL }
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            val start = MON + 23 * H + 30 * MIN
            e.engine.refresh(P, MON + DAY + MIN, start)   // 주행 중 00시
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            assertTrue(e.engine.slot(P).any { it.uid == m.uid })
            // 도전하지 않은 월요일 개인 미션은 갱신
            val nowOffers = e.engine.offers(P, MON + DAY + MIN)
            assertTrue(offered.none { o -> nowOffers.any { it.uid == o.uid } })
            assertEquals(2, nowOffers.count { it.kind == MissionKind.PERSONAL })
            val done = e.drive(start, minutes = 50)   // 00:20 도착
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            assertTrue(done.any { it.instance.uid == m.uid })
        }

        @Test
        fun `주행 중 00시가 지나도 유효시간 전 도전한 미션은 감점 없는 거리도 보전`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1, MON + 20 * H) // 5km
            val start = MON + 23 * H + 30 * MIN
            e.engine.refresh(P, MON + DAY + MIN, start)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(start, km = 30f, minutes = 50)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `일요일 23시30분 출발, 월요일 00시30분 도착 - 도전 중 주간 미션은 그 주행으로 판정`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1, MON + 6 * DAY + 20 * H)
            val start = MON + 6 * DAY + 23 * H + 30 * MIN
            e.engine.refresh(P, MON + 7 * DAY + MIN, start)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            val done = e.drive(start, minutes = 60)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            assertTrue(done.any { it.instance.uid == m.uid })
        }

        @Test
        fun `주행 중이 아니면 00시에 도전 중 미판정 미션은 미판정으로 끝난다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
        }
    }

    // ───────────────────────── 보상·패널티·스냅샷 ─────────────────────────

    @Nested
    inner class RewardsAndPenalty {

        private val CH = CharacterId(1)

        private fun profiles(coins: Int, xp: Int): InMemoryProfileStore {
            val s = InMemoryProfileStore()
            s.upsert(Profile(P, "p", 0))
            s.saveState(ProfileState(P, selected = CH, coins = coins))
            val g = GrowthModule()
            s.saveProgress(CharacterProgress(P, CH, xp = xp, stage = g.stageFor(xp)))
            return s
        }

        @Test
        fun `엔진의 취소 결과 패널티는 그 미션의 보상과 같다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, MON + H)
            val r = e.engine.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "$r")
            assertEquals(MissionEngine.Penalty(60, 15), (r as MissionEngine.SlotResult.Ok).penalty)
        }

        @Test
        fun `엔진만으로 취소하면 프로필 값은 바뀌지 않는다 - 패널티는 MissionActions 로만`() {
            val e = Env()
            val ps = profiles(coins = 100, xp = 50)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            e.engine.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertEquals(100, ps.state(P).coins)
            assertEquals(50, ps.progress(P, CH).xp)
        }

        @Test
        fun `MissionActions 취소는 포인트와 경험치를 깎는다`() {
            val e = Env()
            val ps = profiles(coins = 100, xp = 50)
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 40, 10
            val r = actions.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "$r")
            assertEquals(60, ps.state(P).coins)
            assertEquals(40, ps.progress(P, CH).xp)
            assertEquals(MissionStatus.CANCELLED, e.status(m.uid))
        }

        @Test
        fun `취소 패널티는 0 밑으로 깎지 않고 경험치가 깎이면 레벨이 내려갈 수 있다`() {
            val e = Env()
            val ps = profiles(coins = 30, xp = 105)   // 2단계
            assertEquals(2, ps.progress(P, CH).stage)
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H) // 80, 20
            actions.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertEquals(0, ps.state(P).coins)
            assertEquals(85, ps.progress(P, CH).xp)
            assertEquals(1, ps.progress(P, CH).stage)
        }

        @Test
        fun `경험치도 0 밑으로 깎지 않는다`() {
            val e = Env()
            val ps = profiles(coins = 0, xp = 3)
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 20, 5
            actions.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertEquals(0, ps.state(P).coins)
            assertEquals(0, ps.progress(P, CH).xp)
        }

        @Test
        fun `판정 끝난 실패 미션을 비울 때 패널티는 없다`() {
            val e = Env()
            val ps = profiles(coins = 100, xp = 50)
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertTrue(actions.clear(P, m.uid, MON + 3 * H, driving = false) is MissionEngine.SlotResult.Ok)
            assertEquals(100, ps.state(P).coins)
            assertEquals(50, ps.progress(P, CH).xp)
        }

        @Test
        fun `MissionActions 도 주행 중 취소를 막고 값을 깎지 않는다`() {
            val e = Env()
            val ps = profiles(coins = 100, xp = 50)
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            val r = actions.cancel(P, m.uid, MON + 2 * H, driving = true)
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING), r)
            assertEquals(100, ps.state(P).coins)
            assertEquals(50, ps.progress(P, CH).xp)
        }

        @Test
        fun `유효 주행을 마치면 주행 완료 경험치를 받고 미션 성공 보상은 그 주행 끝에 받는다`() {
            val ps = profiles(coins = 0, xp = 0)
            val sessions = InMemorySessionStore()
            val engine = MissionEngine(InMemoryMissionStore(), sessions, missions = { Mx.catalog() })
            val pipeline = SessionEndPipeline(sessions, ps, engine)
            val m = engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            engine.challenge(P, m.uid, MON + H, driving = false)
            val r = Fx.record("x1", MON + 2 * H)
            val sum = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
            assertEquals(10 + 5, sum.xpGained)
            assertEquals(20, sum.coinsGained)
            assertEquals(20, ps.state(P).coins)
            assertEquals(15, ps.progress(P, CH).xp)
            assertEquals(1, sum.completed.size)
        }

        @Test
        fun `유효 주행이 아니면 주행 완료 경험치가 없다`() {
            val ps = profiles(coins = 0, xp = 0)
            val sessions = InMemorySessionStore()
            val engine = MissionEngine(InMemoryMissionStore(), sessions, missions = { Mx.catalog() })
            val pipeline = SessionEndPipeline(sessions, ps, engine)
            val r = Fx.record("x1", MON + 2 * H, km = 0.5f, minutes = 2, valid = false)
            val sum = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
            assertEquals(0, sum.xpGained)
            assertEquals(0, ps.progress(P, CH).xp)
        }

        @Test
        fun `미션 데이터가 바뀌어도 받은 미션의 목표치와 보상은 제공 때 값 그대로`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            assertEquals(3f, m.target); assertEquals(20, m.rewardCoins); assertEquals(5, m.rewardXp)
            val text = BundledContent::class.java.getResourceAsStream(BundledContent.RESOURCE_PATH)!!
                .use { it.readBytes().toString(Charsets.UTF_8) }
            val changed = text
                .replaceFirst("\"tierTargets\": [3, 2, 1, 0]", "\"tierTargets\": [0, 0, 0, 0]")
                .replace("{ \"tier\": 1, \"coins\": 20, \"xp\": 5 }", "{ \"tier\": 1, \"coins\": 999, \"xp\": 77 }")
            assertTrue(changed != text, "테스트 데이터 치환 실패")
            val newCatalog = ContentCodec.decode(changed).missions.copy(rules = e.catalog.rules)
            e.catalog = newCatalog
            // 새 데이터로 만든 미션은 새 값
            val fresh = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            assertEquals(0f, fresh.target); assertEquals(999, fresh.rewardCoins); assertEquals(77, fresh.rewardXp)
            // 받은 미션은 그대로: 급제동 2회는 옛 목표치(3회) 기준 성공
            assertEquals(3f, e.get(m.uid).target)
            val done = e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 2))
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            val c = done.first { it.instance.uid == m.uid }
            assertEquals(20, c.coins)
            assertEquals(5, c.xp)
        }
    }

    // ───────────────────────── 2단계: 경계값·사이드 이펙트 ─────────────────────────

    @Nested
    inner class Stage2Boundaries {

        private val CH = CharacterId(1)
        private fun personalToday(e: Env, at: Long) =
            e.store.list(P).filter { it.kind == MissionKind.PERSONAL && it.validUntilMs == e.engine.validUntil(MissionKind.PERSONAL, at) }

        // ── 센 값 ──

        @Test
        fun `판정 근거가 없는 주행은 센 값을 바꾸지 않고, 근거가 있는 주행은 퍼센트로 센다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, MON + H)
            val before = e.get(m.uid).counted
            e.drive(MON + 2 * H)   // 차선변경 없음
            assertEquals(before, e.get(m.uid).counted)
            e.drive(MON + 4 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 3) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1))
            assertEquals(75f, e.get(m.uid).counted!!, 0.01f)
        }

        @Test
        fun `벨트 신호가 없는 주행은 센 값을 바꾸지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            val before = e.get(m.uid).counted
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = null))
            assertEquals(before, e.get(m.uid).counted)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
        }

        @Test
        fun `이하 조건 실패 때 센 값은 그 주행의 횟수`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 3회
            e.drive(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(4f, e.get(m.uid).counted)
        }

        // ── 퍼센트 경계 ──

        @Test
        fun `깜빡이 비율 경계 - 7 of 10 은 70퍼센트 성공, 9 of 10 은 90퍼센트 성공`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, MON + H) // 70
            val b = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 3, MON + H) // 90
            e.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 7) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 3))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))
            assertEquals(MissionStatus.FAILED, e.status(b.uid))

            val e2 = Env()
            val c = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 3, MON + H) // 90
            e2.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 9) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1))
            assertEquals(MissionStatus.SUCCEEDED, e2.status(c.uid))
        }

        @Test
        fun `깜빡이 100퍼센트 목표 - 모두 켜면 성공, 하나라도 안 켜면 실패`() {
            val e = Env()
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 4, MON + H)
            e.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 5))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))

            val e2 = Env()
            val b = e2.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 4, MON + H)
            e2.drive(MON + 2 * H, events = evs(EventType.LANE_CHANGE_SIGNALED, 9) + evs(EventType.LANE_CHANGE_NO_SIGNAL, 1))
            assertEquals(MissionStatus.FAILED, e2.status(b.uid))
        }

        @Test
        fun `충전 멈춤 - 목표치보다 높은 멈춤이 여러 번이어도 유효시간 안에는 실패하지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CHARGE_STOP_AT_80, 3, MON + H) // 80
            e.drive(MON + 2 * H, status = VehicleStatus(chargeStoppedAtPct = 81))
            e.drive(MON + 4 * H, status = VehicleStatus(chargeStoppedAtPct = 100))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
        }

        // ── 날짜 경계 ──

        @Test
        fun `개인 미션은 23시59분59초에는 그대로, 00시 정각에 바뀐다`() {
            val e = Env(personal = 2)
            e.engine.refresh(P, MON + 9 * H, null)
            val a = e.engine.offers(P, MON + 9 * H).map { it.uid }.toSet()
            e.engine.refresh(P, MON + DAY - 1_000, null)
            assertEquals(a, e.engine.offers(P, MON + DAY - 1_000).map { it.uid }.toSet())
            e.engine.refresh(P, MON + DAY, null)
            val b = e.engine.offers(P, MON + DAY).filter { it.kind == MissionKind.PERSONAL }
            assertEquals(2, b.size)
            assertTrue(b.none { it.uid in a })
        }

        @Test
        fun `주간 미션은 일요일 23시59분59초에는 그대로, 월요일 00시 정각에 바뀐다`() {
            val e = Env(weekly = 10)
            e.engine.refresh(P, MON + H, null)
            val a = e.engine.offers(P, MON + H).map { it.uid }.toSet()
            val sunLate = MON + 7 * DAY - 1_000
            e.engine.refresh(P, sunLate, null)
            assertEquals(a, e.engine.offers(P, sunLate).map { it.uid }.toSet())
            e.engine.refresh(P, MON + 7 * DAY, null)
            val b = e.engine.offers(P, MON + 7 * DAY)
            assertEquals(10, b.size)
            assertTrue(b.none { it.uid in a })
        }

        @Test
        fun `도전하지 않고 사라진 미션은 도전할 수 없다`() {
            val e = Env(personal = 2)
            e.engine.refresh(P, MON + 9 * H, null)
            val old = e.engine.offers(P, MON + 9 * H).first()
            e.engine.refresh(P, MON + DAY + MIN, null)
            assertTrue(e.engine.challenge(P, old.uid, MON + DAY + 2 * MIN, driving = false) is MissionEngine.SlotResult.Rejected)
            assertTrue(e.get(old.uid).status != MissionStatus.OFFERED && !e.get(old.uid).inSlot)
        }

        @Test
        fun `판정 끝난 미션은 23시59분59초까지 슬롯에 남고 00시 정각에 빠진다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            e.engine.refresh(P, MON + DAY - 1_000, null)
            assertTrue(e.engine.slot(P).any { it.uid == m.uid })
            e.engine.refresh(P, MON + DAY, null)
            assertTrue(e.engine.slot(P).none { it.uid == m.uid })
        }

        @Test
        fun `주행 보전으로 화요일 00시20분에 판정된 미션은 화요일 동안 슬롯에 남고 수요일 00시에 빠진다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            val start = MON + 23 * H + 30 * MIN
            e.engine.refresh(P, MON + DAY + MIN, start)
            e.drive(start, minutes = 50)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
            e.engine.refresh(P, MON + DAY + 10 * H, null)
            assertTrue(e.engine.slot(P).any { it.uid == m.uid }, "판정된 날(화) 안에 빠졌다")
            e.engine.refresh(P, MON + 2 * DAY + MIN, null)
            assertTrue(e.engine.slot(P).none { it.uid == m.uid })
        }

        @Test
        fun `23시59분30초에 출발한 주행도 주행 보전 대상`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            val start = MON + DAY - 30_000
            e.engine.refresh(P, MON + DAY + MIN, start)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(start, minutes = 20)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `00시 정각에 출발한 주행은 전날 미션을 보전하지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            val start = MON + DAY
            e.engine.refresh(P, MON + DAY + MIN, start)
            e.drive(start, minutes = 20)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
        }

        @Test
        fun `refresh 없이 다음 날 주행이 끝나도 전날 미션은 그 주행으로 판정하지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + 20 * H)
            val done = e.drive(MON + DAY + 10 * H)
            assertTrue(done.none { it.instance.uid == m.uid })
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertFalse(e.get(m.uid).inSlot)
        }

        @Test
        fun `도전과 같은 시각에 출발한 유효 주행은 센다`() {
            val e = Env()
            val t = MON + 9 * H
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)
            e.drive(t)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `주간 미션은 월요일 00시 지나서 출발한 주행으로 판정하지 않는다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1, MON + 6 * DAY + 20 * H)
            e.drive(MON + 7 * DAY + 10 * H)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
        }

        // ── 제공 ──

        @Test
        fun `하루 개인 미션은 도전하거나 취소해도 2개를 넘지 않는다`() {
            val e = Env(personal = 2)
            e.engine.refresh(P, MON + 9 * H, null)
            val first = e.engine.offers(P, MON + 9 * H).first { it.kind == MissionKind.PERSONAL }
            e.engine.challenge(P, first.uid, MON + 9 * H, driving = false)
            e.engine.refresh(P, MON + 10 * H, null)
            assertEquals(2, personalToday(e, MON + 10 * H).size)
            e.engine.cancel(P, first.uid, MON + 11 * H, driving = false)
            e.engine.refresh(P, MON + 12 * H, null)
            assertEquals(2, personalToday(e, MON + 12 * H).size)
        }

        @Test
        fun `조합 수보다 많이 요청해도 개인 미션에 연속 일수가 없다`() {
            val e = Env(personal = 40, weekly = 40)
            e.engine.refresh(P, MON + H, null)
            val offers = e.engine.offers(P, MON + H)
            val personal = offers.filter { it.kind == MissionKind.PERSONAL }
            assertTrue(personal.isNotEmpty())
            assertTrue(personal.size <= 40)
            assertTrue(personal.none { it.condition == ConditionType.STREAK_DAYS })
        }

        // ── 연속 일수 경계 ──

        @Test
        fun `연속 일수 - 금요일 감점이어도 토·일 남아 2일 목표는 아직 실패가 아니고 토·일로 성공`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 4 * DAY + 10 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 5 * DAY + 10 * H)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 6 * DAY + 10 * H)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 같은 날 감점 없는 주행 뒤 감점 주행이면 그날은 감점 있는 날`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 9 * H)
            e.drive(MON + 18 * H, events = evs(EventType.HARSH_ACCEL, 1))
            e.drive(MON + DAY + 9 * H)   // 화 감점 없음 → 연속 1일
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            e.drive(MON + 2 * DAY + 9 * H)   // 수 → 연속 2일
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 5일 목표에 수요일 감점이면 남은 4일로 못 채워 실패`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 4, MON + H) // 5일
            e.drive(MON + 2 * DAY + 10 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 5일 목표에 화요일 감점이면 수~일 5일이 남아 실패가 아니다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 4, MON + H) // 5일
            e.drive(MON + DAY + 10 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            for (d in 2..6) e.drive(MON + d * DAY + 10 * H)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `연속 일수 - 유효하지 않은 주행만 있는 날은 운전하지 않은 날로 건너뛴다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, MON + H) // 2일
            e.drive(MON + 9 * H)
            e.drive(MON + DAY + 9 * H, km = 0.5f, minutes = 2, valid = false, events = evs(EventType.HARSH_BRAKE, 1))
            e.drive(MON + 2 * DAY + 9 * H)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        // ── 슬롯 상태 ──

        @Test
        fun `판정 끝난 미션은 취소할 수 없고 패널티도 없다`() {
            val e = Env()
            val ps = InMemoryProfileStore().apply {
                saveState(ProfileState(P, selected = CH, coins = 100)); saveProgress(CharacterProgress(P, CH, xp = 50))
            }
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            val r = actions.cancel(P, m.uid, MON + 3 * H, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Rejected, "$r")
            assertEquals(100, ps.state(P).coins)
            assertEquals(50, ps.progress(P, CH).xp)
            assertEquals(MissionStatus.SUCCEEDED, e.status(m.uid))
        }

        @Test
        fun `도전하지 않은 미션은 취소할 수 없다`() {
            val e = Env()
            val m = e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            assertTrue(e.engine.cancel(P, m.uid, MON + H, driving = false) is MissionEngine.SlotResult.Rejected)
            assertEquals(MissionStatus.OFFERED, e.status(m.uid))
        }

        @Test
        fun `이미 도전 중인 미션을 다시 도전할 수 없다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            assertTrue(e.engine.challenge(P, m.uid, MON + 2 * H, driving = false) is MissionEngine.SlotResult.Rejected)
            assertEquals(1, e.engine.slot(P).size)
        }

        @Test
        fun `비운 미션을 다시 비울 수 없다`() {
            val e = Env()
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.drive(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertTrue(e.engine.clear(P, m.uid, MON + 3 * H, driving = false) is MissionEngine.SlotResult.Ok)
            assertTrue(e.engine.clear(P, m.uid, MON + 4 * H, driving = false) is MissionEngine.SlotResult.Rejected)
        }

        // ── 파이프라인 사이드 이펙트 ──

        @Test
        fun `유효하지 않은 주행으로도 지난 미션의 유효시간 끝을 처리하고 프로필 값은 그대로`() {
            val ps = InMemoryProfileStore().apply {
                saveState(ProfileState(P, selected = CH, coins = 70)); saveProgress(CharacterProgress(P, CH, xp = 40))
            }
            val sessions = InMemorySessionStore()
            val store = InMemoryMissionStore()
            val engine = MissionEngine(store, sessions, missions = { Mx.catalog() })
            val pipeline = SessionEndPipeline(sessions, ps, engine)
            val m = engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)!!
            engine.challenge(P, m.uid, MON + H, driving = false)
            val r = Fx.record("inv", MON + DAY + 9 * H, km = 0.5f, minutes = 2, valid = false)
            val sum = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
            assertEquals(0, sum.xpGained)
            assertEquals(0, sum.coinsGained)
            assertEquals(70, ps.state(P).coins)
            assertEquals(40, ps.progress(P, CH).xp)
            assertEquals(MissionStatus.UNDETERMINED, store.list(P).first { it.uid == m.uid }.status)
        }

        @Test
        fun `주행 완료 경험치로 레벨 경계를 넘으면 레벨이 오른다`() {
            val ps = InMemoryProfileStore().apply {
                saveState(ProfileState(P, selected = CH)); saveProgress(CharacterProgress(P, CH, xp = 95, stage = 1))
            }
            val sessions = InMemorySessionStore()
            val engine = MissionEngine(InMemoryMissionStore(), sessions, missions = { Mx.catalog() })
            val pipeline = SessionEndPipeline(sessions, ps, engine)
            val r = Fx.record("v", MON + 9 * H)
            val sum = pipeline.onSessionEnd(r, VehicleStatus(), r.endWallMs)
            assertEquals(10, sum.xpGained)
            assertEquals(2, sum.leveledUpTo)
            assertEquals(105, ps.progress(P, CH).xp)
            assertEquals(2, ps.progress(P, CH).stage)
        }

        @Test
        fun `취소 패널티로 레벨 경계 정확히 100 에서 1을 깎으면 1단계`() {
            val e = Env()
            val ps = InMemoryProfileStore().apply {
                saveState(ProfileState(P, selected = CH, coins = 20)); saveProgress(CharacterProgress(P, CH, xp = 105, stage = 2))
            }
            val actions = MissionActions(e.engine, Rewards(ps, GrowthModule(), Wallet(BundledContent.pack.items)))
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 20, 5
            actions.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertEquals(0, ps.state(P).coins)
            assertEquals(100, ps.progress(P, CH).xp)
            assertEquals(2, ps.progress(P, CH).stage)
            val m2 = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, MON + 3 * H)
            actions.cancel(P, m2.uid, MON + 4 * H, driving = false)
            assertEquals(95, ps.progress(P, CH).xp)
            assertEquals(1, ps.progress(P, CH).stage)
        }

        @Test
        fun `목표치 0 이하 조건 3개는 모두 없이 이름을 쓴다`() {
            val e = Env()
            for (c in listOf(ConditionType.HARSH_BRAKE_MAX, ConditionType.HARSH_ACCEL_MAX, ConditionType.LANE_NO_SIGNAL_MAX)) {
                val m = e.engine.offer(P, MissionKind.PERSONAL, c, 4, MON + H)!!
                assertEquals(0f, m.target)
                assertFalse(m.title.contains("0회"), "$c title=${m.title}")
                assertFalse(m.description.contains("0회"), "$c description=${m.description}")
                assertTrue(m.title.length <= 15 && m.description.length <= 30, "$c ${m.title} / ${m.description}")
            }
        }
    }
}

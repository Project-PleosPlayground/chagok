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
import core.content.ContentValidator
import core.flow.MissionActions
import core.flow.Rewards
import core.flow.SessionEndPipeline
import core.flow.SessionEndSummary
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.DrivingEvent
import engine.EventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * 기획/07_미션.md "미션 종류와 제공", "도전", "판정", "보상 > 패널티", 기획/04_캐릭터_성장.md "정해진 원칙"만 보고 쓴 테스트
 * (독립 테스터 1단계). 실패 패널티(보상의 2배)와 주간 미션 채우기.
 * 시각은 Fx.MON(월요일 00:00 KST) 기준. 보상: 1단계 20/5, 2단계 40/10, 3단계 60/15, 4단계 80/20.
 */
class FailPenaltyWeeklyRefillSpecTest {

    private val P = Profile.DEFAULT
    private val H = 3_600_000L
    private val MIN = 60_000L
    private val DAY = Fx.DAY
    private val MON = Fx.MON
    private val CH = CharacterId(1)

    private inner class Env(
        personal: Int = 0, weekly: Int = 0, seed: Int = 7,
        var catalog: MissionCatalog = Mx.catalog(personal, weekly),
        coins: Int = 200, xp: Int = 150,
    ) {
        val store = InMemoryMissionStore()
        val sessions = InMemorySessionStore()
        val engine = MissionEngine(store, sessions, missions = { catalog }, random = Random(seed))
        val profiles = InMemoryProfileStore().apply {
            upsert(Profile(P, "p", 0))
            saveState(ProfileState(P, selected = CH, coins = coins))
            saveProgress(CharacterProgress(P, CH, xp = xp, stage = GrowthModule().stageFor(xp)))
        }
        val actions = MissionActions(engine, Rewards(profiles, GrowthModule(), Wallet(BundledContent.pack.items)))
        val pipeline = SessionEndPipeline(sessions, profiles, engine)
        private var n = 0

        val coins get() = profiles.state(P).coins
        val xp get() = profiles.progress(P, CH).xp
        val stage get() = profiles.progress(P, CH).stage

        fun offerAndChallenge(kind: MissionKind, cond: ConditionType, tier: Int, at: Long): MissionInstance {
            val m = engine.offer(P, kind, cond, tier, at)
            assertNotNull(m, "offer($kind, $cond, $tier) 가 null")
            val r = engine.challenge(P, m!!.uid, at, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "도전 실패: $r")
            return (r as MissionEngine.SlotResult.Ok).instance
        }

        fun challenge(uid: String, at: Long): MissionInstance {
            val r = engine.challenge(P, uid, at, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "도전 실패: $r")
            return (r as MissionEngine.SlotResult.Ok).instance
        }

        /** 앱 흐름대로 파이프라인으로 주행을 끝낸다 */
        fun driveViaPipeline(
            start: Long, km: Float = 10f, minutes: Int = 30, valid: Boolean = true,
            events: List<DrivingEvent> = emptyList(), status: VehicleStatus = VehicleStatus(),
        ): SessionEndSummary {
            val r = Fx.record("s${n++}", start, km, minutes, valid, events)
            sessions.save(r)
            return pipeline.onSessionEnd(r, status, r.endWallMs)
        }

        /** 엔진만으로 주행을 끝낸다 (프로필 값은 안 바뀐다) */
        fun driveEngineOnly(
            start: Long, km: Float = 10f, minutes: Int = 30, valid: Boolean = true,
            events: List<DrivingEvent> = emptyList(), status: VehicleStatus = VehicleStatus(),
        ): MissionEngine.Judged {
            val r = Fx.record("s${n++}", start, km, minutes, valid, events)
            sessions.save(r)
            return engine.onDriveEnd(r, status, r.endWallMs)
        }

        fun get(uid: String): MissionInstance = store.list(P).first { it.uid == uid }
        fun status(uid: String) = get(uid).status

        fun weeklyOffers(at: Long) = engine.offers(P, at).filter { it.kind == MissionKind.WEEKLY }
        fun personalOffers(at: Long) = engine.offers(P, at).filter { it.kind == MissionKind.PERSONAL }
        fun weeklyInSlot() = engine.slot(P).filter { it.kind == MissionKind.WEEKLY }
    }

    private fun evs(type: EventType, count: Int) = List(count) { Fx.ev(type, 10_000L + it * 1_000L) }
    private fun combo(m: MissionInstance) = m.condition to m.tier
    private fun weekEnd(weekStart: Long) = weekStart + 7 * DAY

    // ───────────────────────── 실패 패널티 ─────────────────────────

    @Nested
    inner class FailPenalty {

        @Test
        fun `주행 끝에 실패하면 보상 40·10 의 2배 80·20 을 깎는다 - 파이프라인과 요약`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 2회, 40/10
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(1, sum.failed.size, "failed=${sum.failed}")
            assertEquals(m.uid, sum.failed[0].instance.uid)
            assertEquals(MissionEngine.Penalty(80, 20), sum.failed[0].penalty)
            assertEquals(80, sum.coinsLost)
            assertEquals(20, sum.xpLost)
            assertEquals(0, sum.coinsGained)
            assertEquals(10, sum.xpGained, "주행 완료 경험치만 받는다")
            assertEquals(120, e.coins)
            assertEquals(150 + 10 - 20, e.xp)
            assertEquals(120, sum.profile.coins)
            assertEquals(140, sum.character!!.xp)
        }

        @Test
        fun `엔진 onDriveEnd 의 Failed 에 2배 패널티가 담기고 엔진만으로는 프로필이 바뀌지 않는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, MON + H) // 1회, 60/15
            val j = e.driveEngineOnly(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 2))
            assertTrue(j.completed.isEmpty())
            assertEquals(1, j.failed.size)
            assertEquals(m.uid, j.failed[0].instance.uid)
            assertEquals(MissionStatus.FAILED, j.failed[0].instance.status)
            assertEquals(MissionEngine.Penalty(120, 30), j.failed[0].penalty)
            assertEquals(MissionEngine.Penalty(120, 30), j.penalty)
            assertEquals(200, e.coins)
            assertEquals(150, e.xp)
        }

        @Test
        fun `유효시간 끝에 실패하면 MissionActions refresh 가 2배를 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H) // 10km, 40/10
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            val j = e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(listOf(m.uid), j.failed.map { it.instance.uid })
            assertEquals(MissionEngine.Penalty(80, 20), j.failed[0].penalty)
            assertEquals(120, e.coins)
            assertEquals(130, e.xp)
        }

        @Test
        fun `유효시간 끝 실패 패널티는 한 번만 깎는다 - refresh 를 여러 번 해도`() {
            val e = Env(coins = 200, xp = 150)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H)
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            e.actions.refresh(P, MON + DAY + MIN, null)
            val again = e.actions.refresh(P, MON + DAY + 2 * MIN, null)
            e.actions.refresh(P, MON + 2 * DAY + MIN, null)
            assertTrue(again.failed.isEmpty(), "다시 refresh 했는데 failed=${again.failed}")
            assertEquals(120, e.coins)
            assertEquals(130, e.xp)
        }

        @Test
        fun `다음 날 주행 끝에 처리된 유효시간 끝 실패도 패널티를 한 번 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H) // 40/10
            e.driveViaPipeline(MON + 2 * H, km = 3f)   // +10 xp (주행 완료)
            assertEquals(160, e.xp)
            e.driveViaPipeline(MON + DAY + 9 * H, km = 3f)   // 화요일 주행: +10 xp, 월요일 미션 유효시간 끝 → 실패
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            e.actions.refresh(P, MON + DAY + 12 * H, null)
            assertEquals(120, e.coins)
            assertEquals(160 + 10 - 20, e.xp)
        }

        @Test
        fun `실패 패널티 배수는 미션 데이터의 값을 따른다`() {
            val e = Env(catalog = Mx.catalog(failMultiplier = 3), coins = 500, xp = 500)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 3회, 20/5
            val j = e.driveEngineOnly(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(MissionEngine.Penalty(60, 15), j.failed.single().penalty)
        }

        @Test
        fun `유효시간 끝 실패 - 0 밑으로 깎지 않고 경험치가 깎여 레벨이 내려간다`() {
            val e = Env(coins = 30, xp = 110)   // 2단계 (100 이상)
            assertEquals(2, e.stage)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H) // 40/10 → 80/20
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(0, e.coins)
            assertEquals(90, e.xp)
            assertEquals(1, e.stage)
        }

        @Test
        fun `유효시간 끝 실패 - 경험치도 0 밑으로 깎지 않는다`() {
            val e = Env(coins = 0, xp = 7)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 4, MON + H) // 30km, 80/20 → 160/40
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(0, e.coins)
            assertEquals(0, e.xp)
        }

        @Test
        fun `주행 끝 실패 - 포인트는 0 밑으로 안 가고 요약의 깎인 값은 실제로 깎인 값`() {
            val e = Env(coins = 30, xp = 5)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 80/20
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(0, e.coins)
            assertEquals(30, sum.coinsLost)
            assertTrue(e.xp >= 0)
            assertTrue(sum.xpLost in 0..20, "xpLost=${sum.xpLost}")
            assertEquals(e.xp, 5 + sum.xpGained - sum.xpLost, "xp=${e.xp}, gained=${sum.xpGained}, lost=${sum.xpLost}")
            assertEquals(sum.character!!.xp, e.xp)
        }

        @Test
        fun `주행 끝 실패 패널티로 레벨이 내려갈 수 있다`() {
            val e = Env(coins = 500, xp = 105)   // 2단계
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 4, MON + H) // 0회, 80/20 → 160/40
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 1))
            assertEquals(105 + 10 - 40, e.xp)
            assertEquals(1, e.stage)
            assertEquals(null, sum.leveledUpTo)
            assertEquals(340, e.coins)
        }

        @Test
        fun `미판정이면 깎지 않는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H)
            val j = e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertTrue(j.failed.isEmpty())
            assertEquals(MissionEngine.Penalty(0, 0), j.penalty)
            assertEquals(200, e.coins)
            assertEquals(150, e.xp)
        }

        @Test
        fun `유효 주행이 있어도 미판정이면 깎지 않는다 - 깜빡이 비율`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 2, MON + H)
            e.driveViaPipeline(MON + 2 * H)   // 차선변경 없음, +10
            e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.UNDETERMINED, e.status(m.uid))
            assertEquals(200, e.coins)
            assertEquals(160, e.xp)
        }

        @Test
        fun `실패한 미션을 비울 때는 더 깎지 않는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H) // 20/5
            e.driveViaPipeline(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            val coinsAfterFail = e.coins
            val xpAfterFail = e.xp
            assertEquals(160, coinsAfterFail)
            assertEquals(150 + 10 - 10, xpAfterFail)
            val r = e.actions.clear(P, m.uid, MON + 3 * H, driving = false)
            assertTrue(r is MissionEngine.SlotResult.Ok, "$r")
            assertEquals(null, (r as MissionEngine.SlotResult.Ok).penalty)
            assertEquals(coinsAfterFail, e.coins)
            assertEquals(xpAfterFail, e.xp)
        }

        @Test
        fun `비우지 않은 실패 미션이 00시에 슬롯에서 빠질 때도 더 깎지 않는다`() {
            val e = Env(coins = 200, xp = 150)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, MON + H)
            e.driveViaPipeline(MON + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
            assertEquals(160, e.coins)
            val j = e.actions.refresh(P, MON + DAY + MIN, null)
            assertTrue(j.failed.isEmpty())
            assertTrue(e.engine.slot(P).isEmpty())
            assertEquals(160, e.coins)
            assertEquals(150, e.xp)
        }

        @Test
        fun `취소 패널티는 그대로 보상의 1배`() {
            val e = Env(coins = 200, xp = 150)
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 40/10
            val ra = e.engine.cancel(P, a.uid, MON + 2 * H, driving = false)
            assertEquals(MissionEngine.Penalty(40, 10), (ra as MissionEngine.SlotResult.Ok).penalty)
            assertEquals(200, e.coins, "엔진 취소만으로는 깎지 않는다")

            val b = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 2, MON + 3 * H) // 40/10
            val rb = e.actions.cancel(P, b.uid, MON + 4 * H, driving = false)
            assertTrue(rb is MissionEngine.SlotResult.Ok, "$rb")
            assertEquals(MissionStatus.CANCELLED, e.status(b.uid))
            assertEquals(160, e.coins)
            assertEquals(140, e.xp)
        }

        @Test
        fun `한 주행에 성공과 실패가 같이 나면 보상을 받고 패널티를 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val ok = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, MON + H)  // 3회, 20/5
            val bad = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 2회, 40/10 → 80/20
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(MissionStatus.SUCCEEDED, e.status(ok.uid))
            assertEquals(MissionStatus.FAILED, e.status(bad.uid))
            assertEquals(listOf(ok.uid), sum.completed.map { it.instance.uid })
            assertEquals(listOf(bad.uid), sum.failed.map { it.instance.uid })
            assertEquals(20, sum.coinsGained)
            assertEquals(10 + 5, sum.xpGained)
            assertEquals(80, sum.coinsLost)
            assertEquals(20, sum.xpLost)
            assertEquals(200 + 20 - 80, e.coins)
            assertEquals(150 + 15 - 20, e.xp)
        }

        @Test
        fun `한 주행에 두 미션이 실패하면 둘 다 2배씩 깎는다`() {
            val e = Env(coins = 500, xp = 500)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)  // 3회, 20/5
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 4, MON + H)    // 0회, 80/20
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4) + evs(EventType.HARSH_ACCEL, 1))
            assertEquals(2, sum.failed.size)
            assertEquals(40 + 160, sum.coinsLost)
            assertEquals(10 + 40, sum.xpLost)
            assertEquals(300, e.coins)
            assertEquals(500 + 10 - 50, e.xp)
        }

        @Test
        fun `성공한 미션은 깎지 않는다`() {
            val e = Env(coins = 200, xp = 150)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H)
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 2))
            assertTrue(sum.failed.isEmpty())
            assertEquals(0, sum.coinsLost)
            assertEquals(0, sum.xpLost)
            assertEquals(240, e.coins)
            assertEquals(170, e.xp)
        }
    }

    // ───────────────────────── 주간 미션 채우기 ─────────────────────────

    @Nested
    inner class WeeklyRefill {

        @Test
        fun `주간 미션 하나를 도전하면 새 주간 미션 하나를 줘서 목록이 10개로 유지된다`() {
            val e = Env(personal = 2, weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val before = e.weeklyOffers(t)
            assertEquals(10, before.size)
            val picked = before.first()
            e.challenge(picked.uid, t + MIN)
            val after = e.weeklyOffers(t + MIN)
            assertEquals(10, after.size, "도전 직후 주간 목록 ${after.size}개")
            assertTrue(after.none { it.uid == picked.uid })
            val added = after.filter { a -> before.none { it.uid == a.uid } }
            assertEquals(1, added.size)
            assertEquals(MissionStatus.OFFERED, added[0].status)
        }

        @Test
        fun `채운 주간 미션의 유효시간은 그 주 일요일 23시59분까지`() {
            val e = Env(personal = 2, weekly = 10)
            val wed = MON + 2 * DAY + 14 * H
            e.engine.refresh(P, wed, null)
            val before = e.weeklyOffers(wed).map { it.uid }.toSet()
            e.challenge(e.weeklyOffers(wed).first().uid, wed)
            val added = e.weeklyOffers(wed).filter { it.uid !in before }
            assertEquals(1, added.size)
            assertTrue(added[0].validUntilMs in (weekEnd(MON) - MIN)..weekEnd(MON), "validUntil=${added[0].validUntilMs}")
        }

        @Test
        fun `주간 목록과 슬롯의 주간 미션은 조건·단계가 겹치지 않는다`() {
            val e = Env(personal = 2, weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            e.challenge(e.weeklyOffers(t).first().uid, t)
            val offers = e.weeklyOffers(t)
            assertEquals(10, offers.map(::combo).toSet().size, "주간 목록 안에 같은 조건·단계: ${offers.map(::combo)}")
            val slotCombos = e.weeklyInSlot().map(::combo).toSet()
            assertTrue(offers.none { combo(it) in slotCombos }, "목록=${offers.map(::combo)}, 슬롯=$slotCombos")
        }

        @Test
        fun `주간 미션 3개를 도전해도 목록은 10개이고 겹치지 않는다`() {
            for (seed in 1..20) {
                val e = Env(personal = 2, weekly = 10, seed = seed)
                val t = MON + 9 * H
                e.engine.refresh(P, t, null)
                repeat(3) { i -> e.challenge(e.weeklyOffers(t + i * MIN).first().uid, t + i * MIN) }
                val now = t + 3 * MIN
                val offers = e.weeklyOffers(now)
                assertEquals(10, offers.size, "seed=$seed")
                assertEquals(3, e.weeklyInSlot().size)
                val all = offers.map(::combo) + e.weeklyInSlot().map(::combo)
                assertEquals(13, all.toSet().size, "seed=$seed 겹침: $all")
                assertTrue(offers.all { it.validUntilMs in (weekEnd(MON) - MIN)..weekEnd(MON) })
            }
        }

        @Test
        fun `개인 미션은 도전하면 목록에서 빠지고 채우지 않는다`() {
            val e = Env(personal = 2, weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val p = e.personalOffers(t)
            assertEquals(2, p.size)
            e.challenge(p[0].uid, t)
            assertEquals(listOf(p[1].uid), e.personalOffers(t).map { it.uid })
            e.engine.refresh(P, t + H, null)
            assertEquals(listOf(p[1].uid), e.personalOffers(t + H).map { it.uid })
            assertEquals(10, e.weeklyOffers(t + H).size, "개인 미션 도전이 주간 목록을 바꿨다")
            e.challenge(p[1].uid, t + 2 * H)
            e.engine.refresh(P, t + 3 * H, null)
            assertTrue(e.personalOffers(t + 3 * H).isEmpty())
        }

        @Test
        fun `월요일 00시에 도전하지 않은 주간 미션은 채운 것까지 모두 바뀐다`() {
            val e = Env(personal = 2, weekly = 10)
            val t = MON + 2 * DAY + 9 * H
            e.engine.refresh(P, t, null)
            e.challenge(e.weeklyOffers(t).first().uid, t)
            val oldUids = e.weeklyOffers(t).map { it.uid }.toSet()
            assertEquals(10, oldUids.size)
            val nextMon = MON + 7 * DAY
            e.engine.refresh(P, nextMon, null)
            val fresh = e.weeklyOffers(nextMon)
            assertEquals(10, fresh.size)
            assertTrue(fresh.none { it.uid in oldUids }, "지난주 주간 미션이 남았다")
            assertTrue(fresh.all { it.validUntilMs in (weekEnd(nextMon) - MIN)..weekEnd(nextMon) })
            assertEquals(10, fresh.map(::combo).toSet().size)
        }

        @Test
        fun `주 중간에 처음 켜면 주간 미션 10개를 받고 도전해도 10개`() {
            val e = Env(personal = 2, weekly = 10)
            val thu = MON + 3 * DAY + 15 * H
            e.engine.refresh(P, thu, null)
            val w = e.weeklyOffers(thu)
            assertEquals(10, w.size)
            assertTrue(w.all { it.validUntilMs in (weekEnd(MON) - MIN)..weekEnd(MON) })
            e.challenge(w.first().uid, thu)
            assertEquals(10, e.weeklyOffers(thu).size)
        }

        @Test
        fun `도전 뒤 refresh 를 해도 주간 목록은 10개 그대로`() {
            val e = Env(personal = 2, weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            e.challenge(e.weeklyOffers(t).first().uid, t)
            val a = e.weeklyOffers(t).map { it.uid }.toSet()
            e.engine.refresh(P, t + H, null)
            e.engine.refresh(P, MON + DAY + MIN, null)   // 화요일 00시: 주간은 그대로
            val b = e.weeklyOffers(MON + DAY + MIN).map { it.uid }.toSet()
            assertEquals(10, b.size)
            assertEquals(a, b)
        }

        // ── 조합을 좁힌 미션 데이터로 뽑기 제외 규칙을 확정적으로 본다 ──
        // 급제동 이하 4단계만 있고 주간 목록 3개: 목록 3개 + 슬롯 1개가 4조합을 다 쓰므로 뽑을 조합이 하나로 정해진다

        private fun brakeOnly(weekly: Int = 3, personal: Int = 0): MissionCatalog {
            val base = Mx.catalog(personal, weekly)
            return base.copy(conditions = base.conditions.filter { it.condition == ConditionType.HARSH_BRAKE_MAX })
        }

        @Test
        fun `뽑을 수 있는 조합이 하나뿐이면 그 조합을 채운다`() {
            val e = Env(catalog = brakeOnly())
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val before = e.weeklyOffers(t)
            assertEquals(3, before.size)
            val missing = (1..4).first { tier -> before.none { it.tier == tier } }
            e.challenge(before.first().uid, t)
            val after = e.weeklyOffers(t)
            assertEquals(3, after.size)
            assertEquals(1, after.count { it.tier == missing }, "after=${after.map(::combo)}")
            assertTrue(after.none { it.tier == before.first().tier })
        }

        @Test
        fun `개인 미션과는 조합이 같아도 된다`() {
            val e = Env(catalog = brakeOnly())
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            for (tier in 1..4) assertNotNull(e.engine.offer(P, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, tier, t))
            val before = e.weeklyOffers(t)
            val missing = (1..4).first { tier -> before.none { it.tier == tier } }
            e.challenge(before.first().uid, t)
            val after = e.weeklyOffers(t)
            assertEquals(3, after.size, "개인 미션과 같은 조합을 빼서 못 채웠다: ${after.map(::combo)}")
            assertTrue(after.any { it.tier == missing })
        }

        @Test
        fun `그 주에 취소한 주간 미션의 조합은 다시 나올 수 있다`() {
            val e = Env(catalog = brakeOnly())
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val a = e.weeklyOffers(t).first()
            e.challenge(a.uid, t)                         // 목록 {B,C,D}, 슬롯 {A}
            assertTrue(e.engine.cancel(P, a.uid, t + MIN, driving = false) is MissionEngine.SlotResult.Ok)
            val b = e.weeklyOffers(t + MIN).first()
            e.challenge(b.uid, t + 2 * MIN)               // 목록 {나머지 2}, 슬롯 {B} → 뽑을 수 있는 건 A 조합뿐
            val after = e.weeklyOffers(t + 2 * MIN)
            assertEquals(3, after.size, "after=${after.map(::combo)}")
            assertEquals(1, after.count { it.tier == a.tier })
            assertTrue(after.none { it.uid == a.uid }, "취소한 미션 자체가 돌아왔다")
        }

        @Test
        fun `판정이 끝나 비운 주간 미션의 조합은 다시 나올 수 있다`() {
            val e = Env(catalog = brakeOnly())
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val a = e.weeklyOffers(t).first()
            e.challenge(a.uid, t)
            e.driveEngineOnly(MON + 10 * H)               // 급제동 0회 → 성공
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))
            assertTrue(e.engine.clear(P, a.uid, MON + 11 * H, driving = false) is MissionEngine.SlotResult.Ok)
            val b = e.weeklyOffers(MON + 11 * H).first()
            e.challenge(b.uid, MON + 11 * H)
            val after = e.weeklyOffers(MON + 11 * H)
            assertEquals(3, after.size, "after=${after.map(::combo)}")
            assertEquals(1, after.count { it.tier == a.tier })
        }

        @Test
        fun `판정이 끝났지만 비우지 않아 슬롯에 있는 주간 미션의 조합은 뽑지 않는다`() {
            val e = Env(catalog = brakeOnly(weekly = 2))
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val first = e.weeklyOffers(t)
            assertEquals(2, first.size)
            val a = first.first()
            e.challenge(a.uid, t)                         // 슬롯 {A}, 목록 {B, 새것}
            e.driveEngineOnly(MON + 10 * H)
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))
            assertTrue(e.get(a.uid).inSlot)
            val b = e.weeklyOffers(MON + 11 * H).first()
            e.challenge(b.uid, MON + 11 * H)              // 슬롯 {A(성공), B}
            val offers = e.weeklyOffers(MON + 11 * H)
            assertEquals(2, offers.size, "offers=${offers.map(::combo)}")
            val slotTiers = e.weeklyInSlot().map { it.tier }.toSet()
            assertTrue(a.tier in slotTiers && b.tier in slotTiers)
            assertTrue(offers.none { it.tier in slotTiers }, "offers=${offers.map(::combo)}, slot=$slotTiers")
        }
    }

    // ───────────────────────── 2단계: 경계값·사이드 이펙트 ─────────────────────────

    @Nested
    inner class Stage2Boundaries {

        private fun withRewards(vararg rows: TierReward, multiplier: Int = 2): MissionCatalog {
            val base = Mx.catalog(failMultiplier = multiplier)
            val byTier = rows.associateBy { it.tier }
            return base.copy(tierRewards = base.tierRewards.map { byTier[it.tier] ?: it })
        }

        private fun brakeOnly(weekly: Int, personal: Int = 0): MissionCatalog {
            val base = Mx.catalog(personal, weekly)
            return base.copy(conditions = base.conditions.filter { it.condition == ConditionType.HARSH_BRAKE_MAX })
        }

        // ── 패널티 = 받은 미션의 보상 × 깎는 때의 미션 데이터 배수 ──

        @Test
        fun `실패 패널티는 단계마다 보상의 2배다`() {
            for (tier in 1..4) {
                val e = Env()
                val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, tier, MON + H)
                val j = e.driveEngineOnly(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 10))
                assertEquals(MissionStatus.FAILED, e.status(m.uid), "tier=$tier")
                assertEquals(MissionEngine.Penalty(2 * m.rewardCoins, 2 * m.rewardXp), j.failed.single().penalty, "tier=$tier")
            }
        }

        @Test
        fun `제공 뒤 미션 데이터가 바뀌면 받을 때 보상에 판정 때 배수를 곱한다`() {
            val e = Env(coins = 500, xp = 500)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H) // 40/10
            e.catalog = withRewards(TierReward(2, 999, 99), multiplier = 5)
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(MissionEngine.Penalty(200, 50), sum.failed.single().penalty)
            assertEquals(200, sum.coinsLost)
            assertEquals(50, sum.xpLost)
            assertEquals(300, e.coins)
        }

        @Test
        fun `유효시간 끝 실패도 판정 때 배수를 쓴다`() {
            val e = Env(coins = 500, xp = 500)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H)
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            e.catalog = Mx.catalog(failMultiplier = 0)
            val j = e.actions.refresh(P, MON + DAY + MIN, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(MissionEngine.Penalty(0, 0), j.failed.single().penalty)
            assertEquals(500, e.coins)
            assertEquals(500, e.xp)
        }

        @Test
        fun `취소 패널티 배수는 미션 데이터의 값을 따른다`() {
            val e = Env(catalog = Mx.catalog(cancelMultiplier = 3), coins = 500, xp = 500)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 20/5
            val r = e.engine.cancel(P, m.uid, MON + 2 * H, driving = false)
            assertEquals(MissionEngine.Penalty(60, 15), (r as MissionEngine.SlotResult.Ok).penalty)
        }

        @Test
        fun `포인트만 주는 미션은 포인트만, 경험치만 주는 미션은 경험치만 깎는다`() {
            val e = Env(catalog = withRewards(TierReward(1, 20, 0), TierReward(2, 0, 10)), coins = 500, xp = 500)
            val coinsOnly = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 3회
            val xpOnly = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 2, MON + H)     // 2회
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4) + evs(EventType.HARSH_ACCEL, 3))
            assertEquals(2, sum.failed.size)
            assertEquals(40, sum.coinsLost)
            assertEquals(20, sum.xpLost)
            assertEquals(460, e.coins)
            assertEquals(500 + 10 - 20, e.xp)
        }

        @Test
        fun `실패 패널티 배수가 0이면 실패해도 깎지 않는다`() {
            val e = Env(catalog = Mx.catalog(failMultiplier = 0), coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + H)
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(MissionEngine.Penalty(0, 0), sum.failed.single().penalty)
            assertEquals(0, sum.coinsLost)
            assertEquals(0, sum.xpLost)
            assertEquals(200, e.coins)
            assertEquals(160, e.xp)
        }

        @Test
        fun `콘텐츠 검사 - 실패 패널티 배수 음수는 문제, 0은 문제가 아니다`() {
            val pack = BundledContent.pack
            fun withMul(k: Int) = pack.copy(missions = pack.missions.copy(rules = pack.missions.rules.copy(failPenaltyMultiplier = k)))
            val baseline = ContentValidator.problems(pack)
            assertEquals(baseline, ContentValidator.problems(withMul(0)), "배수 0을 문제로 봤다")
            val neg = ContentValidator.problems(withMul(-1))
            assertTrue(neg.size > baseline.size, "배수 -1을 문제로 보지 않았다: $neg")
        }

        @Test
        fun `콘텐츠 검사 - 취소 패널티 배수 음수는 문제, 0은 문제가 아니다`() {
            val pack = BundledContent.pack
            fun withMul(k: Int) = pack.copy(missions = pack.missions.copy(rules = pack.missions.rules.copy(cancelPenaltyMultiplier = k)))
            val baseline = ContentValidator.problems(pack)
            assertEquals(baseline, ContentValidator.problems(withMul(0)), "배수 0을 문제로 봤다")
            val neg = ContentValidator.problems(withMul(-1))
            assertTrue(neg.size > baseline.size, "배수 -1을 문제로 보지 않았다: $neg")
        }

        @Test
        fun `아주 큰 보상의 패널티는 넘치지 않고 깎으면 0에서 멈춘다`() {
            val huge = 1_500_000_000
            val e = Env(catalog = withRewards(TierReward(1, huge, huge)), coins = 1000, xp = 150)
            val a = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            val b = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 1, MON + H)
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4) + evs(EventType.HARSH_ACCEL, 4))
            assertEquals(2, sum.failed.size)
            sum.failed.forEach { assertTrue(it.penalty.coins >= 0 && it.penalty.xp >= 0, "음수 패널티: ${it.penalty}") }
            assertEquals(0, e.coins)
            assertEquals(0, e.xp)
            assertEquals(1000, sum.coinsLost)
            assertTrue(sum.xpLost >= 0)
        }

        @Test
        fun `여러 실패의 패널티 합도 넘치지 않는다`() {
            val huge = 1_500_000_000
            val e = Env(catalog = withRewards(TierReward(1, huge, huge)))
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H)
            e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 1, MON + H)
            val j = e.driveEngineOnly(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4) + evs(EventType.HARSH_ACCEL, 4))
            assertEquals(2, j.failed.size)
            assertEquals(MissionEngine.Penalty(Int.MAX_VALUE, Int.MAX_VALUE), j.penalty)
        }

        // ── 파이프라인: 보상·패널티와 레벨 ──

        @Test
        fun `유효하지 않은 주행 끝에 처리된 유효시간 끝 실패도 패널티를 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2, MON + H)
            e.driveEngineOnly(MON + 2 * H, km = 3f)
            val sum = e.driveViaPipeline(MON + DAY + 9 * H, km = 0.5f, minutes = 2, valid = false)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(0, sum.coinsGained)
            assertEquals(0, sum.xpGained)
            assertEquals(listOf(m.uid), sum.failed.map { it.instance.uid })
            assertEquals(80, sum.coinsLost)
            assertEquals(20, sum.xpLost)
            assertEquals(120, e.coins)
            assertEquals(130, e.xp)
        }

        @Test
        fun `주행 완료 경험치로 레벨 경계를 넘었다가 실패 패널티로 다시 내려오면 레벨업이 아니다`() {
            val e = Env(coins = 500, xp = 95)   // 1단계
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 20/5 → 40/10
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(95, e.xp)
            assertEquals(1, e.stage)
            assertEquals(null, sum.leveledUpTo)
            assertEquals(1, sum.character!!.stage)
        }

        @Test
        fun `보상과 패널티를 반영한 뒤에도 경계 위면 레벨업이다`() {
            val e = Env(coins = 500, xp = 95)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, MON + H) // 성공 20/5
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, MON + H) // 실패 40/10
            val sum = e.driveViaPipeline(MON + 2 * H, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(95 + 10 + 5 - 10, e.xp)
            assertEquals(2, e.stage)
            assertEquals(2, sum.leveledUpTo)
        }

        // ── 주간 미션 유효시간 끝 ──

        @Test
        fun `주간 미션이 일요일까지 목표를 못 채우면 월요일 00시에 실패하고 2배를 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.CLEAN_KM, 2, MON + H) // 10km, 40/10
            e.driveEngineOnly(MON + DAY + 9 * H, km = 3f)
            val sunLate = e.actions.refresh(P, MON + 7 * DAY - 1_000, null)
            assertTrue(sunLate.failed.isEmpty())
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            assertEquals(200, e.coins)
            val j = e.actions.refresh(P, MON + 7 * DAY, null)
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(MissionEngine.Penalty(80, 20), j.penalty)
            assertEquals(120, e.coins)
            assertEquals(130, e.xp)
        }

        // ── 주행 보전 ──

        @Test
        fun `주행 보전 중 00시 refresh 에는 판정도 패널티도 없고 그 주행 끝에 실패하면 깎는다`() {
            val e = Env(coins = 200, xp = 150)
            val m = e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2, MON + 20 * H) // 80/20
            val start = MON + 23 * H + 30 * MIN
            val mid = e.actions.refresh(P, MON + DAY + MIN, start)
            assertTrue(mid.failed.isEmpty())
            assertEquals(MissionStatus.IN_PROGRESS, e.status(m.uid))
            assertEquals(200, e.coins)
            val sum = e.driveViaPipeline(start, minutes = 50, events = evs(EventType.HARSH_BRAKE, 3))
            assertEquals(MissionStatus.FAILED, e.status(m.uid))
            assertEquals(80, sum.coinsLost)
            assertEquals(120, e.coins)
            assertEquals(150 + 10 - 20, e.xp)
        }

        @Test
        fun `일요일 밤 주행 보전 중인 지난주 주간 미션의 조합은 새 주 주간 미션에서 빠진다`() {
            val e = Env(catalog = brakeOnly(weekly = 3), coins = 500, xp = 500)
            val sun = MON + 6 * DAY + 20 * H
            e.engine.refresh(P, sun, null)
            val a = e.challenge(e.weeklyOffers(sun).first().uid, sun)
            val start = MON + 6 * DAY + 23 * H + 30 * MIN
            val nextMon = MON + 7 * DAY + MIN
            val mid = e.actions.refresh(P, nextMon, start)
            assertTrue(mid.failed.isEmpty())
            assertEquals(MissionStatus.IN_PROGRESS, e.status(a.uid))
            val fresh = e.weeklyOffers(nextMon)
            assertEquals(3, fresh.size, "fresh=${fresh.map(::combo)}")
            assertTrue(fresh.none { it.tier == a.tier }, "슬롯의 지난주 주간 미션과 같은 조합: a=${a.tier}, fresh=${fresh.map(::combo)}")
            assertTrue(fresh.all { it.validUntilMs in (weekEnd(MON + 7 * DAY) - MIN)..weekEnd(MON + 7 * DAY) })

            val sum = e.driveViaPipeline(start, minutes = 60, events = evs(EventType.HARSH_BRAKE, 4))
            assertEquals(MissionStatus.FAILED, e.status(a.uid))
            assertEquals(2 * a.rewardCoins, sum.coinsLost)
            assertEquals(2 * a.rewardXp, sum.xpLost)
            val afterDrive = e.weeklyOffers(MON + 7 * DAY + H)
            assertEquals(fresh.map { it.uid }.toSet(), afterDrive.map { it.uid }.toSet(), "주행 끝 뒤 주간 목록이 바뀌었다")
        }

        // ── 주간 채우기: 사이드 이펙트 ──

        @Test
        fun `슬롯이 차서 도전이 거절되면 주간 목록은 바뀌지 않는다`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, t)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, t)
            e.offerAndChallenge(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, t)
            val before = e.weeklyOffers(t).map { it.uid }.toSet()
            val target = e.weeklyOffers(t).first()
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.SLOT_FULL),
                e.engine.challenge(P, target.uid, t, driving = false))
            assertEquals(before, e.weeklyOffers(t).map { it.uid }.toSet())
            e.engine.refresh(P, t + MIN, null)
            assertEquals(before, e.weeklyOffers(t + MIN).map { it.uid }.toSet())
        }

        @Test
        fun `주행 중이라 도전이 거절되면 주간 목록은 바뀌지 않는다`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val before = e.weeklyOffers(t).map { it.uid }.toSet()
            assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING),
                e.engine.challenge(P, e.weeklyOffers(t).first().uid, t, driving = true))
            assertEquals(before, e.weeklyOffers(t).map { it.uid }.toSet())
        }

        @Test
        fun `도전한 주간 미션을 취소해도 목록은 10개 그대로이고 취소한 미션은 돌아오지 않는다`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val a = e.challenge(e.weeklyOffers(t).first().uid, t)
            val afterChallenge = e.weeklyOffers(t).map { it.uid }.toSet()
            e.engine.cancel(P, a.uid, t + MIN, driving = false)
            e.engine.refresh(P, t + 2 * MIN, null)
            val now = e.weeklyOffers(t + 2 * MIN)
            assertEquals(10, now.size)
            assertEquals(afterChallenge, now.map { it.uid }.toSet())
            assertTrue(now.none { it.uid == a.uid })
        }

        @Test
        fun `판정 끝난 주간 미션을 비워도 목록은 10개 그대로`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val a = e.offerAndChallenge(MissionKind.WEEKLY, ConditionType.SEATBELT_BEFORE_START, 1, t)
            val before = e.weeklyOffers(t).map { it.uid }.toSet()
            e.driveEngineOnly(MON + 10 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
            assertEquals(MissionStatus.SUCCEEDED, e.status(a.uid))
            assertEquals(before, e.weeklyOffers(MON + 11 * H).map { it.uid }.toSet())
            e.engine.clear(P, a.uid, MON + 11 * H, driving = false)
            e.engine.refresh(P, MON + 12 * H, null)
            assertEquals(before, e.weeklyOffers(MON + 12 * H).map { it.uid }.toSet())
        }

        @Test
        fun `주간 미션 하나를 손으로 더 제공한 뒤 그것을 도전하면 목록은 10개`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val taken = e.weeklyOffers(t).map(::combo).toSet()
            val free = ConditionType.values().flatMap { c -> (1..4).map { c to it } }
                .first { it !in taken && e.catalog.row(it.first)?.target(it.second) != null && it.first != ConditionType.SEATBELT_BEFORE_START }
            val extra = e.engine.offer(P, MissionKind.WEEKLY, free.first, free.second, t)!!
            e.challenge(extra.uid, t)
            val now = e.weeklyOffers(t)
            assertEquals(10, now.size, "now=${now.map(::combo)}")
            assertTrue(now.none { combo(it) == free })
        }

        @Test
        fun `뽑을 조합이 모자라면 있는 만큼만 주고, 조합이 비면 다음 refresh 에 채운다`() {
            val e = Env(catalog = brakeOnly(weekly = 10))
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            val first = e.weeklyOffers(t)
            assertEquals(4, first.size, "급제동 4조합만 있다: ${first.map(::combo)}")
            assertEquals(4, first.map(::combo).toSet().size)
            val a = e.challenge(first.first().uid, t)
            assertEquals(3, e.weeklyOffers(t).size, "슬롯과 목록이 4조합을 다 쓴다")
            e.engine.refresh(P, t + MIN, null)
            assertEquals(3, e.weeklyOffers(t + MIN).size)
            e.engine.cancel(P, a.uid, t + 2 * MIN, driving = false)
            e.engine.refresh(P, t + 3 * MIN, null)
            val after = e.weeklyOffers(t + 3 * MIN)
            assertEquals(4, after.size, "취소로 비게 된 조합을 다시 채우지 않았다: ${after.map(::combo)}")
            assertEquals(1, after.count { it.tier == a.tier })
        }

        @Test
        fun `새로 뽑는 주간 미션은 지금 미션 데이터에서 뽑는다`() {
            val e = Env(weekly = 10)
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            e.catalog = brakeOnly(weekly = 10)
            val before = e.weeklyOffers(t)
            val picked = before.first()
            e.challenge(picked.uid, t)
            val excludedBrake = before.map(::combo)
                .filter { it.first == ConditionType.HARSH_BRAKE_MAX }.map { it.second }.toSet()
            val added = e.weeklyOffers(t).filter { a -> before.none { it.uid == a.uid } }
            if (excludedBrake.size == 4) {
                assertTrue(added.isEmpty(), "added=${added.map(::combo)}")
            } else {
                assertEquals(1, added.size)
                assertEquals(ConditionType.HARSH_BRAKE_MAX, added[0].condition)
                assertFalse(added[0].tier in excludedBrake)
            }
        }

        @Test
        fun `개인 미션은 주간 목록·슬롯과 조합이 같아도 제공된다`() {
            val e = Env(catalog = brakeOnly(weekly = 3, personal = 2))
            val t = MON + 9 * H
            e.engine.refresh(P, t, null)
            e.challenge(e.weeklyOffers(t).first().uid, t)   // 주간 목록 3 + 슬롯 1 = 급제동 4조합 전부
            e.engine.refresh(P, MON + DAY + MIN, null)
            val personal = e.personalOffers(MON + DAY + MIN)
            assertEquals(2, personal.size, "personal=${personal.map(::combo)}")
            assertTrue(personal.all { it.condition == ConditionType.HARSH_BRAKE_MAX })
        }
    }
}

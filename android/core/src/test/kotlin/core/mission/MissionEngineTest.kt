package core.mission

import core.Fx
import core.Mx
import core.Time
import core.character.Profile
import core.content.BundledContent
import core.session.InMemorySessionStore
import core.session.VehicleStatus
import engine.EventType.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.random.Random

class MissionEngineTest {
    private val H = 3_600_000L
    private val sessions = InMemorySessionStore(); private val store = InMemoryMissionStore()
    private var catalog = Mx.catalog()
    private var engine = engine(RangePolicy.BY_CONDITION)
    private fun engine(range: RangePolicy) = MissionEngine(store, sessions, missions = { catalog }, range = range, random = Random(1))
    private val mon9 = Fx.MON + 9 * H     // 월요일 09:00
    private val p = Profile.DEFAULT

    /** 미션 하나를 제공하고 도전한다 */
    private fun take(kind: MissionKind, c: ConditionType, tier: Int, at: Long = mon9): MissionInstance {
        val m = engine.offer(p, kind, c, tier, at)!!
        return (engine.challenge(p, m.uid, at, driving = false) as MissionEngine.SlotResult.Ok).instance
    }
    private fun get(uid: String) = store.list(p).single { it.uid == uid }
    private fun drive(id: String, start: Long, km: Float = 10f, valid: Boolean = true, ev: List<engine.EventType> = emptyList(), status: VehicleStatus = VehicleStatus()): List<MissionEngine.Completed> {
        val r = Fx.record(id, start, km = km, valid = valid, events = ev.mapIndexed { i, t -> Fx.ev(t, 10_000L + i * 20_000L) })
        sessions.save(r)
        return engine.onDriveEnd(r, status, r.endWallMs).completed
    }

    // ───────── 제공·갱신 ─────────

    @Test fun `개인 미션은 하루에, 주간 미션은 한 주에 정한 개수만 제공한다`() {
        catalog = BundledContent.pack.missions   // 개인 3, 주간 10
        val n = catalog.rules.personalPerDay
        engine.refresh(p, mon9, null)
        assertEquals(n, engine.offers(p, mon9).count { it.kind == MissionKind.PERSONAL })
        assertEquals(10, engine.offers(p, mon9).count { it.kind == MissionKind.WEEKLY })
        engine.refresh(p, mon9 + H, null)
        assertEquals(n + 10, store.list(p).size, "같은 날 다시 제공하지 않는다")
    }

    @Test fun `콘텐츠 문구가 바뀌면 아직 끝나지 않은 미션 글도 지금 문구로 바뀐다`() {
        catalog = BundledContent.pack.missions
        val m = engine.offer(p, MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 4, mon9)!!
        store.upsert(m.copy(title = "깜빡이 100% 이상", description = "옛 설명"))
        engine.refresh(p, mon9 + H, null)
        val now = store.list(p).single { it.uid == m.uid }
        assertEquals("깜빡이 100% 지키기", now.title); assertEquals("차선 바꿀 때 깜빡이를 100% 켜기", now.description)
    }

    @Test fun `도전하지 않은 개인 미션은 00시에 사라지고 새 미션이 나온다`() {
        catalog = Mx.catalog(personal = 2, weekly = 1)
        engine.refresh(p, mon9, null)
        val monPersonal = engine.offers(p, mon9).filter { it.kind == MissionKind.PERSONAL }
        val tue0 = Fx.MON + Fx.DAY
        engine.refresh(p, tue0, driveStartMs = mon9 + 14 * H)   // 주행 중이어도 갱신한다
        monPersonal.forEach { assertEquals(MissionStatus.EXPIRED, get(it.uid).status) }
        val tuePersonal = engine.offers(p, tue0).filter { it.kind == MissionKind.PERSONAL }
        assertEquals(2, tuePersonal.size); assertTrue(tuePersonal.all { it.validUntilMs == tue0 + Fx.DAY })
        assertEquals(1, engine.offers(p, tue0).count { it.kind == MissionKind.WEEKLY }, "주간 미션은 그대로")
    }

    @Test fun `주 중간에 처음 켜도 그 주 일요일까지의 주간 미션을 받는다`() {
        catalog = Mx.catalog(weekly = 3)
        val thu = Fx.MON + 3 * Fx.DAY + 9 * H
        engine.refresh(p, thu, null)
        val w = engine.offers(p, thu)
        assertEquals(3, w.size); assertTrue(w.all { it.validUntilMs == Fx.MON + 7 * Fx.DAY })
    }

    @Test fun `연속 일수 조건은 개인 미션으로 나오지 않는다`() {
        catalog = Mx.catalog(personal = 4).let { c -> c.copy(conditions = c.conditions.filter { it.condition == ConditionType.STREAK_DAYS }) }
        engine.refresh(p, mon9, null)
        assertTrue(engine.offers(p, mon9).isEmpty())
        assertNull(engine.offer(p, MissionKind.PERSONAL, ConditionType.STREAK_DAYS, 1, mon9))
        assertNotNull(engine.offer(p, MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, mon9))
    }

    @Test fun `제공할 때 단계로 목표·보상을 정하고 이름 틀에 목표치를 넣는다`() {
        val m = engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3, mon9)!!
        assertEquals(1f, m.target); assertEquals(60, m.rewardCoins); assertEquals(15, m.rewardXp)
        assertEquals("급제동 1회 이하", m.title)
        assertEquals(MissionStatus.OFFERED, m.status); assertEquals(Fx.MON + Fx.DAY, m.validUntilMs)
        assertNull(engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 5, mon9), "없는 단계")
    }

    // ───────── 도전 슬롯 ─────────

    @Test fun `도전 슬롯은 3칸이고 주행 중에는 도전할 수 없다`() {
        val ms = (1..4).map { engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, it, mon9)!! }
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING), engine.challenge(p, ms[0].uid, mon9, driving = true))
        ms.take(3).forEach { assertTrue(engine.challenge(p, it.uid, mon9, false) is MissionEngine.SlotResult.Ok) }
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.SLOT_FULL), engine.challenge(p, ms[3].uid, mon9, false))
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.WRONG_STATE), engine.challenge(p, ms[0].uid, mon9, false))
        assertEquals(3, engine.slot(p).size)
    }

    @Test fun `유효시간이 지난 미션은 도전할 수 없다`() {
        val m = engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, mon9)!!
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.EXPIRED), engine.challenge(p, m.uid, Fx.MON + Fx.DAY, false))
    }

    @Test fun `취소하면 사라지고 보상만큼의 패널티를 돌려주며 슬롯이 빈다`() {
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2)
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.DRIVING), engine.cancel(p, m.uid, mon9, driving = true))
        val r = engine.cancel(p, m.uid, mon9 + H, false) as MissionEngine.SlotResult.Ok
        assertEquals(MissionEngine.Penalty(40, 10), r.penalty)
        assertEquals(MissionStatus.CANCELLED, get(m.uid).status); assertTrue(engine.slot(p).isEmpty())
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.WRONG_STATE), engine.cancel(p, m.uid, mon9 + H, false))
    }

    @Test fun `판정 끝난 미션은 비울 수 있고, 비우지 않으면 다음 00시에 슬롯에서 빠진다`() {
        val a = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1)
        val b = take(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1)
        assertEquals(MissionEngine.SlotResult.Rejected(MissionEngine.Reason.WRONG_STATE), engine.clear(p, a.uid, mon9, false), "판정 전")
        drive("s1", mon9 + H)
        assertEquals(2, engine.slot(p).size, "성공해도 비우기 전까지 슬롯에 있다")
        assertTrue(engine.clear(p, a.uid, mon9 + 3 * H, false) is MissionEngine.SlotResult.Ok)
        assertEquals(listOf(b.uid), engine.slot(p).map { it.uid })
        engine.refresh(p, Fx.MON + Fx.DAY, null)
        assertTrue(engine.slot(p).isEmpty())
        assertEquals(MissionStatus.SUCCEEDED, get(b.uid).status, "비워도 성공 기록은 남는다")
    }

    // ───────── 판정 ─────────

    @Test fun `도전하지 않은 미션과 도전 전에 시작한 주행은 판정하지 않는다`() {
        val offered = engine.offer(p, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, mon9)!!
        drive("s1", mon9 + H)
        assertEquals(MissionStatus.OFFERED, get(offered.uid).status)
        val early = Fx.record("s0", mon9 - H); sessions.save(early)
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_ACCEL_MAX, 1, at = mon9)
        assertTrue(engine.onDriveEnd(early, VehicleStatus(), mon9 + H).completed.isEmpty())
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status)
    }

    @Test fun `이하 조건은 첫 유효 주행 하나로 판정한다`() {
        val ok = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3)            // 1회 이하
        val bad = take(MissionKind.WEEKLY, ConditionType.HARSH_ACCEL_MAX, 4)             // 0회 이하
        val done = drive("s1", mon9 + H, ev = listOf(HARSH_BRAKE, HARSH_ACCEL))
        assertEquals(listOf(ok.uid), done.map { it.instance.uid }); assertEquals(60, done.single().coins)
        assertEquals(MissionStatus.FAILED, get(bad.uid).status, "주간 미션도 한 주행으로 판정")
        assertEquals(1f, get(bad.uid).counted)
        assertTrue(drive("s2", mon9 + 3 * H).isEmpty(), "끝난 미션은 다시 판정하지 않는다")
    }

    @Test fun `미유효 주행은 세지 않는다`() {
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1)
        drive("s1", mon9 + H, km = 0.5f, valid = false, ev = listOf(HARSH_BRAKE, HARSH_BRAKE, HARSH_BRAKE, HARSH_BRAKE))
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status); assertEquals(0, get(m.uid).drives)
    }

    @Test fun `깜빡이 비율은 차선을 바꾸지 않은 주행을 건너뛰고, 끝까지 근거가 없으면 미판정`() {
        val a = take(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1)          // 70% 이상
        val b = take(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 4)          // 100% 이상
        drive("s1", mon9 + H)
        assertEquals(MissionStatus.IN_PROGRESS, get(a.uid).status)
        drive("s2", mon9 + 3 * H, ev = listOf(LANE_CHANGE_SIGNALED, LANE_CHANGE_SIGNALED, LANE_CHANGE_SIGNALED, LANE_CHANGE_NO_SIGNAL))
        assertEquals(MissionStatus.SUCCEEDED, get(a.uid).status); assertEquals(75f, get(a.uid).counted)
        assertEquals(MissionStatus.FAILED, get(b.uid).status)
        val c = take(MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 2, at = mon9 + 5 * H)
        drive("s3", mon9 + 6 * H)
        engine.refresh(p, Fx.MON + Fx.DAY, null)
        assertEquals(MissionStatus.UNDETERMINED, get(c.uid).status)
        assertFalse(get(c.uid).inSlot)
    }

    @Test fun `점수가 100이어도 감점 행동이 있으면 감점 없는 주행이 아니다`() {
        // 긴 주행은 감점이 거리로 나뉘어 반올림하면 100점이 된다 (스펙 5-1)
        val long = Fx.record("s1", mon9 + H, km = 120f, events = listOf(Fx.ev(HARSH_ACCEL))).let { it.copy(result = it.result.copy(score = 100)) }
        assertFalse(long.clean)
        assertTrue(Fx.record("s2", mon9, events = listOf(Fx.ev(LANE_CHANGE_SIGNALED))).clean, "깜빡이 켠 차선변경은 감점이 아니다")
        val m = take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1)                 // 5km
        sessions.save(long); engine.onDriveEnd(long, VehicleStatus(), long.endWallMs)
        assertEquals(0f, get(m.uid).counted); assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status)
        assertEquals(0, ConditionRules.streakDays(listOf(long)))
    }

    @Test fun `감점 없는 거리는 감점 없는 주행의 거리를 쌓아 목표에 닿은 주행 끝에 성공`() {
        val m = take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 2)                  // 10km
        drive("s1", mon9 + H, km = 6f)
        drive("s2", mon9 + 2 * H, km = 30f, ev = listOf(HARSH_BRAKE))                  // 감점 있음 → 0
        assertEquals(6f, get(m.uid).counted); assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status)
        assertEquals(1, drive("s3", mon9 + 3 * H, km = 4f).size)
        assertEquals(MissionStatus.SUCCEEDED, get(m.uid).status)
    }

    @Test fun `유효시간 끝에 판정한 미션은 바로 슬롯에서 빠진다`() {
        val failed = take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 4)               // 30km
        drive("s1", mon9 + H, km = 5f)
        val tue0 = Fx.MON + Fx.DAY
        engine.refresh(p, tue0, null)
        assertEquals(MissionStatus.FAILED, get(failed.uid).status)
        assertEquals(tue0, get(failed.uid).clearedMs)
        assertTrue(engine.slot(p).isEmpty())
    }

    @Test fun `쌓는 조건은 유효 주행이 있었으면 실패, 없었으면 미판정으로 끝난다`() {
        val withDrive = take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 4)            // 30km
        drive("s1", mon9 + H, km = 5f)
        val noDrive = take(MissionKind.PERSONAL, ConditionType.CLEAN_KM, 1, at = mon9 + 3 * H)
        engine.refresh(p, Fx.MON + Fx.DAY, null)
        assertEquals(MissionStatus.FAILED, get(withDrive.uid).status)
        assertEquals(MissionStatus.UNDETERMINED, get(noDrive.uid).status)
    }

    @Test fun `출발 전 안전벨트는 그 주행에서 맸는지로 판정하고 신호가 없으면 다음 주행으로`() {
        val a = take(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1)
        drive("s1", mon9 + H)
        assertEquals(MissionStatus.IN_PROGRESS, get(a.uid).status)
        drive("s2", mon9 + 2 * H, status = VehicleStatus(seatBeltBuckledAtStart = false))
        assertEquals(MissionStatus.FAILED, get(a.uid).status)
        val b = take(MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, at = mon9 + 3 * H)
        drive("s3", mon9 + 4 * H, status = VehicleStatus(seatBeltBuckledAtStart = true))
        assertEquals(MissionStatus.SUCCEEDED, get(b.uid).status)
    }

    @Test fun `충전 멈춤은 목표 이하에서 멈춘 뒤의 주행 끝에 성공, 충전이 없으면 미판정`() {
        val ok = take(MissionKind.WEEKLY, ConditionType.CHARGE_STOP_AT_80, 3)             // 80% 이하
        val over = take(MissionKind.WEEKLY, ConditionType.CHARGE_STOP_AT_80, 4)           // 75% 이하
        val easy = take(MissionKind.WEEKLY, ConditionType.CHARGE_STOP_AT_80, 1, at = mon9)
        drive("s1", mon9 + H, status = VehicleStatus(chargeStoppedAtPct = 78))
        assertEquals(MissionStatus.SUCCEEDED, get(ok.uid).status)
        assertEquals(MissionStatus.IN_PROGRESS, get(over.uid).status, "한 번 넘게 멈춰도 바로 실패하지 않는다")
        engine.refresh(p, Fx.MON + 7 * Fx.DAY, null)
        assertEquals(MissionStatus.FAILED, get(over.uid).status)
        assertEquals(MissionStatus.SUCCEEDED, get(easy.uid).status)   // 90% 이하
    }

    @Test fun `충전을 한 번도 안 했으면 미판정`() {
        val m = take(MissionKind.WEEKLY, ConditionType.CHARGE_STOP_AT_80, 1)
        drive("s1", mon9 + H)
        engine.refresh(p, Fx.MON + 7 * Fx.DAY, null)
        assertEquals(MissionStatus.UNDETERMINED, get(m.uid).status)
    }

    @Test fun `배터리 지키기는 그 주행의 최저 잔량으로 판정한다`() {
        val a = take(MissionKind.PERSONAL, ConditionType.NO_BELOW_10_PCT, 1)             // 10%
        val b = take(MissionKind.PERSONAL, ConditionType.NO_BELOW_10_PCT, 4)             // 25%
        drive("s1", mon9 + H, status = VehicleStatus(minSocThisDayPct = 20))
        assertEquals(MissionStatus.SUCCEEDED, get(a.uid).status); assertEquals(MissionStatus.FAILED, get(b.uid).status)
    }

    @Test fun `연속 일수는 운전하지 않은 날을 건너뛰고 센다`() {
        val m = take(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 2)                   // 3일
        drive("s1", mon9 + H)
        drive("s2", mon9 + 2 * Fx.DAY)           // 화요일은 운전 안 함
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status); assertEquals(2f, get(m.uid).counted)
        drive("s3", mon9 + 3 * Fx.DAY)
        assertEquals(MissionStatus.SUCCEEDED, get(m.uid).status)
    }

    @Test fun `연속 일수는 남은 날로 못 채우게 된 순간 실패`() {
        val fri = Fx.MON + 4 * Fx.DAY + 9 * H
        val m = take(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 2, at = fri)         // 3일, 남은 날 토·일
        drive("s1", fri + H, ev = listOf(HARSH_BRAKE))
        assertEquals(MissionStatus.FAILED, get(m.uid).status)
        val m2 = take(MissionKind.WEEKLY, ConditionType.STREAK_DAYS, 1, at = fri + 2 * H)  // 2일
        drive("s2", fri + 3 * H, ev = listOf(HARSH_BRAKE))
        assertEquals(MissionStatus.IN_PROGRESS, get(m2.uid).status, "토·일 이틀이 남아 아직 채울 수 있다")
    }

    // ───────── 주행 보전 ─────────

    @Test fun `주행 중 00시가 지나도 도전 중인 미션은 그 주행이 끝날 때 판정한다`() {
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, at = mon9)   // 월요일 미션
        val start = Fx.MON + 23 * H + 30 * 60_000L                                        // 월 23:30 출발
        val tue0 = Fx.MON + Fx.DAY
        engine.refresh(p, tue0, driveStartMs = start)
        assertEquals(MissionStatus.IN_PROGRESS, get(m.uid).status, "주행 보전")
        val r = Fx.record("s1", start, minutes = 50); sessions.save(r)                      // 화 00:20 도착
        assertEquals(1, engine.onDriveEnd(r, VehicleStatus(), r.endWallMs).completed.size)
        assertEquals(MissionStatus.SUCCEEDED, get(m.uid).status)
    }

    @Test fun `보전된 주행이 유효하지 않으면 그 주행이 끝날 때 미판정으로 정리한다`() {
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, at = mon9)
        val start = Fx.MON + 23 * H + 58 * 60_000L
        engine.refresh(p, Fx.MON + Fx.DAY, driveStartMs = start)
        val r = Fx.record("s1", start, km = 0.3f, minutes = 2, valid = false); sessions.save(r)
        engine.onDriveEnd(r, VehicleStatus(), r.endWallMs)
        assertEquals(MissionStatus.UNDETERMINED, get(m.uid).status)
    }

    @Test fun `유효시간이 지난 뒤 시작한 주행은 지난 미션에 세지 않는다`() {
        val m = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, at = mon9)
        val r = Fx.record("s1", Fx.MON + Fx.DAY + H); sessions.save(r)
        engine.onDriveEnd(r, VehicleStatus(), r.endWallMs)
        assertEquals(MissionStatus.UNDETERMINED, get(m.uid).status)
        assertEquals(0, get(m.uid).drives)
    }

    // ───────── 세는 범위 바꾸기 ─────────

    @Test fun `이하 조건을 주간 미션만 유효시간 동안 세도록 바꿀 수 있다`() {
        engine = engine(RangePolicy.AT_MOST_WEEKLY_ACCUMULATES)
        val weekly = take(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 3)             // 1회 이하
        val personal = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 3)
        drive("s1", mon9 + H, ev = listOf(HARSH_BRAKE))
        assertEquals(MissionStatus.IN_PROGRESS, get(weekly.uid).status); assertEquals(1f, get(weekly.uid).counted)
        assertEquals(MissionStatus.SUCCEEDED, get(personal.uid).status, "개인 미션은 그대로 한 주행")
        drive("s2", mon9 + Fx.DAY)
        val done = engine.refresh(p, Fx.MON + 7 * Fx.DAY, null).completed
        assertEquals(listOf(weekly.uid), done.map { it.instance.uid }, "끝까지 넘지 않으면 유효시간 끝에 성공")
        val w2 = take(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 3, at = Fx.MON + 7 * Fx.DAY + H)
        drive("s3", Fx.MON + 7 * Fx.DAY + 2 * H, ev = listOf(HARSH_BRAKE))
        drive("s4", Fx.MON + 7 * Fx.DAY + 4 * H, ev = listOf(HARSH_BRAKE))
        assertEquals(MissionStatus.FAILED, get(w2.uid).status, "넘는 순간 실패")
    }

    @Test fun `세는 범위 정책은 이하 조건 말고는 조건마다 같다`() {
        for (c in ConditionType.entries) for (k in MissionKind.entries) {
            val r = ConditionRules.of(c)
            if (r.compare != Compare.AT_MOST) assertEquals(RangePolicy.BY_CONDITION.of(c, k), RangePolicy.AT_MOST_WEEKLY_ACCUMULATES.of(c, k))
        }
        assertEquals(CountRange.SINGLE_DRIVE, RangePolicy.BY_CONDITION.of(ConditionType.HARSH_BRAKE_MAX, MissionKind.WEEKLY))
    }

    @Test fun `시간 경계는 00시다`() {
        assertEquals(Fx.MON + Fx.DAY, engine.validUntil(MissionKind.PERSONAL, Fx.MON + Fx.DAY - 1))
        assertEquals(Fx.MON + 2 * Fx.DAY, engine.validUntil(MissionKind.PERSONAL, Fx.MON + Fx.DAY))
        assertEquals(Fx.MON + 7 * Fx.DAY, engine.validUntil(MissionKind.WEEKLY, Fx.MON + 7 * Fx.DAY - 1))
        assertEquals(Fx.MON + 14 * Fx.DAY, engine.validUntil(MissionKind.WEEKLY, Fx.MON + 7 * Fx.DAY))
        assertEquals(0L, Math.floorMod(Fx.MON + Time.KST_OFFSET_MS, Fx.DAY))
    }

    // ───────── 완료 미션 ─────────

    @Test fun `완료 미션은 기준 시각부터 성공한 미션만, 최근 성공이 앞이다`() {
        // 판정을 끝내고 슬롯을 비워 다음 미션을 도전할 수 있게 한다
        fun judge(m: MissionInstance, status: MissionStatus, endedMs: Long) =
            store.upsert(m.copy(status = status, endedMs = endedMs, clearedMs = endedMs))
        val a = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1).also { judge(it, MissionStatus.SUCCEEDED, mon9 + H) }
        val b = take(MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 2).also { judge(it, MissionStatus.SUCCEEDED, mon9 + 3 * H) }
        take(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 1).also { judge(it, MissionStatus.FAILED, mon9 + 2 * H) }
        take(MissionKind.WEEKLY, ConditionType.HARSH_BRAKE_MAX, 2).also { judge(it, MissionStatus.SUCCEEDED, mon9 - H) }
        assertEquals(listOf(b.uid, a.uid), engine.completed(p, mon9).map { it.uid })
        assertEquals(listOf(b.uid, a.uid), engine.completed(p, mon9 + H).map { it.uid }, "기준 시각에 성공한 것도 넣는다")
        assertEquals(listOf(b.uid), engine.completed(p, mon9 + H + 1).map { it.uid })
        assertTrue(engine.completed(p, mon9 + 5 * H).isEmpty())
    }
}

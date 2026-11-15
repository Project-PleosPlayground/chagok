package core.session

import core.Time
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VehicleStatusTrackerTest {
    private val t0 = 1_793_000_000_000L

    @Test fun `주행 시작 순간의 벨트 상태를 남긴다`() {
        val t = VehicleStatusTracker()
        assertNull(t.snapshot().seatBeltBuckledAtStart)
        t.onBelt(true)
        t.onDriveStarted()
        t.onBelt(false)   // 주행 중에 풀어도 출발 전 값은 그대로
        assertEquals(true, t.takeAtDriveEnd().seatBeltBuckledAtStart)
    }

    @Test fun `벨트를 안 매고 출발하면 false`() {
        val t = VehicleStatusTracker()
        t.onBelt(false); t.onDriveStarted()
        assertEquals(false, t.takeAtDriveEnd().seatBeltBuckledAtStart)
    }

    @Test fun `정차 중 잔량이 오르다 충전구가 닫히면 최고 잔량에서 멈춘 것으로 본다`() {
        val t = VehicleStatusTracker()
        t.onSoc(50, t0); t.onSoc(70, t0 + 1); t.onSoc(78, t0 + 2)
        assertEquals(true, t.snapshot().charging)
        t.onChargePortOpen(false)
        assertEquals(78, t.snapshot().chargeStoppedAtPct)
        assertEquals(false, t.snapshot().charging)
    }

    @Test fun `충전 중에 주행을 시작해도 충전이 끝난 것으로 본다`() {
        val t = VehicleStatusTracker()
        t.onSoc(60, t0); t.onSoc(85, t0 + 1)
        t.onDriveStarted()
        assertEquals(85, t.takeAtDriveEnd().chargeStoppedAtPct)
    }

    @Test fun `충전 멈춤 잔량은 주행 끝 판정에 한 번만 넘긴다`() {
        val t = VehicleStatusTracker()
        t.onSoc(60, t0); t.onSoc(80, t0 + 1); t.onChargePortOpen(false)
        t.onDriveStarted()
        assertEquals(80, t.takeAtDriveEnd().chargeStoppedAtPct)
        t.onDriveStarted()
        assertNull(t.takeAtDriveEnd().chargeStoppedAtPct)
    }

    @Test fun `주행 중 잔량 변화는 충전으로 보지 않는다`() {
        val t = VehicleStatusTracker()
        t.onSoc(60, t0); t.onDriveStarted(); t.onSoc(61, t0 + 1)   // 회생제동
        assertNull(t.takeAtDriveEnd().chargeStoppedAtPct)
    }

    @Test fun `오늘 최저 잔량을 세고 날이 바뀌면 새로 센다`() {
        val t = VehicleStatusTracker()
        t.onSoc(40, t0); t.onSoc(12, t0 + 1); t.onSoc(30, t0 + 2)
        assertEquals(12, t.snapshot().minSocThisDayPct)
        t.onSoc(35, t0 + Time.DAY_MS)
        assertEquals(35, t.snapshot().minSocThisDayPct)
    }
}

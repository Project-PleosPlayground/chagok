package ai.safetypoc.app.source

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import android.content.Context
import android.util.Log
import core.session.VehicleStatus
import core.session.VehicleStatusTracker

/**
 * Pleos Vehicle SDK 2.3.2 로 운전석 안전벨트, 배터리 잔량(%), 충전구 열림을 받아 [VehicleStatusTracker] 에 넣는다.
 * 출발 전 안전벨트·충전 멈춤·배터리 지키기 미션의 판정 근거다. 충전은 주행 밖에서 일어나므로 앱이 떠 있는 동안 계속 듣는다.
 * API 이름은 Vehicle-2.3.2.aar 의 클래스에서 확인했다 (11/14).
 */
/**
 * [demoBeltBuckled]: 시연 모드. 에뮬레이터 Virtual Console 에는 안전벨트 체결 항목이 없어 늘 "안 맴"으로 읽히므로,
 * 출발 전 안전벨트를 맨 것으로 넘긴다 (11/14)
 */
class VehicleStatusMonitor(private val context: Context, private val demoBeltBuckled: Boolean = false) {
    private val tracker = VehicleStatusTracker()
    /** 배터리 잔량(%)이 들어올 때마다. 주행 중 꼬르륵 반응에 쓴다 (11/15) */
    var onSoc: ((Int) -> Unit)? = null
    private var vehicle: Vehicle? = null
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

    private fun logFail(what: String, e: Exception?) { Log.w(TAG, "$what 실패: ${e?.message}") }

    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) { Log.d(TAG, "안전벨트 $buckled"); buckled?.let { synchronized(tracker) { tracker.onBelt(it) } } }
        override fun onFailed(e: Exception) = logFail("안전벨트", e)
    }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(pct: Int?) {
            Log.d(TAG, "배터리 잔량 $pct%")
            pct?.let { synchronized(tracker) { tracker.onSoc(it, System.currentTimeMillis()) }; onSoc?.invoke(it) }
        }
        override fun onFailed(e: Exception) = logFail("배터리 잔량", e)
    }

    private val portListener = object : EvChargePortOpenListener {
        override fun onEvChargePortOpenUpdated(open: Boolean?) { Log.d(TAG, "충전구 열림 $open"); open?.let { synchronized(tracker) { tracker.onChargePortOpen(it) } } }
        override fun onFailed(e: Exception) = logFail("충전구", e)
    }

    /** 권한을 받은 뒤 부른다. 이미 듣고 있으면 그대로 둔다 */
    fun start() {
        if (vehicle != null) return
        val v = Vehicle(context)
        vehicle = v
        v.initialize()
        runCatching { v.getSeat().registerSeatBeltBuckledState(driverSeat, beltListener) }.onFailure { Log.w(TAG, "안전벨트 등록 실패: ${it.message}") }
        runCatching { v.getEvBattery().registerEvBatteryStateOfCharge(socListener) }.onFailure { Log.w(TAG, "배터리 잔량 등록 실패: ${it.message}") }
        runCatching { v.getEvBattery().registerEvChargePortOpen(portListener) }.onFailure { Log.w(TAG, "충전구 등록 실패: ${it.message}") }
    }

    fun stop() {
        vehicle?.let { v ->
            runCatching { v.getSeat().unregisterSeatBeltBuckledState(driverSeat, beltListener) }
            runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
            runCatching { v.getEvBattery().unregisterEvChargePortOpen(portListener) }
            v.release()
        }
        vehicle = null
    }

    fun onDriveStarted() = synchronized(tracker) { tracker.onDriveStarted() }

    /** 주행 끝 판정용. 충전 멈춤 잔량은 한 번만 넘긴다 */
    fun takeAtDriveEnd(): VehicleStatus = demo(synchronized(tracker) { tracker.takeAtDriveEnd() }).also { Log.i(TAG, "주행 끝 차량 상태: $it") }

    /** 주행 중 진행도용 */
    fun snapshot(): VehicleStatus = demo(synchronized(tracker) { tracker.snapshot() })

    private fun demo(s: VehicleStatus) = if (demoBeltBuckled) s.copy(seatBeltBuckledAtStart = true) else s

    private companion object { const val TAG = "VehicleStatus" }
}

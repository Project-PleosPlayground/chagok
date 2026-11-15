package core.session

import core.Time

/**
 * 차량 신호(안전벨트·배터리 잔량·충전구)를 받아 [VehicleStatus] 를 만든다. 순수 로직. 신호를 받는 일은 :app 의 VehicleStatusMonitor 가 한다.
 *
 * - 출발 전 안전벨트: 주행이 시작되는 순간 운전석 벨트 상태를 찍어 둔다.
 * - 충전 멈춤: 주행 중이 아닐 때 잔량이 오르면 충전 중으로 본다. 충전구가 닫히거나, 잔량이 다시 내려가거나, 주행이 시작되면
 *   충전이 끝난 것으로 보고 그때까지 오른 최고 잔량을 "멈춘 잔량"으로 남긴다. 다음 주행이 끝나 판정에 한 번 쓰면 지운다.
 * - 오늘 최저 잔량: 받은 잔량 중 오늘(KST) 가장 낮은 값. 날이 바뀌면 새로 센다.
 */
class VehicleStatusTracker(private val tzOffsetMs: Long = Time.KST_OFFSET_MS) {
    private var belt: Boolean? = null
    private var beltAtStart: Boolean? = null
    private var soc: Int? = null
    private var charging = false
    private var chargePeak: Int? = null
    private var chargeStoppedAt: Int? = null
    private var minSocDay: Long? = null
    private var minSoc: Int? = null
    private var driving = false

    /** 운전석 안전벨트 */
    fun onBelt(buckled: Boolean) { belt = buckled }

    /** 배터리 잔량 (%) */
    fun onSoc(pct: Int, nowMs: Long) {
        val day = Time.dayIndex(nowMs, tzOffsetMs)
        if (minSocDay != day) { minSocDay = day; minSoc = pct } else minSoc = minOf(minSoc ?: pct, pct)
        val prev = soc
        soc = pct
        if (driving || prev == null) return
        when {
            pct > prev -> { charging = true; chargePeak = maxOf(chargePeak ?: pct, pct) }
            pct < prev && charging -> endCharge()
        }
    }

    /** 충전구 열림. 닫히면 충전이 끝난 것으로 본다 */
    fun onChargePortOpen(open: Boolean) { if (!open && charging) endCharge() }

    fun onDriveStarted() {
        if (charging) endCharge()
        driving = true
        beltAtStart = belt
    }

    /** 주행이 끝났을 때 판정에 넘길 상태. 충전 멈춤 잔량은 한 번만 넘기고 지운다 */
    fun takeAtDriveEnd(): VehicleStatus {
        driving = false
        val s = snapshot()
        chargeStoppedAt = null
        return s
    }

    /** 주행 중 진행도용. 아무것도 지우지 않는다 */
    fun snapshot(): VehicleStatus = VehicleStatus(
        seatBeltBuckledAtStart = beltAtStart,
        evSocPct = soc,
        charging = if (soc == null) null else charging,
        chargeStoppedAtPct = chargeStoppedAt,
        minSocThisDayPct = minSoc,
    )

    private fun endCharge() {
        chargeStoppedAt = chargePeak
        charging = false
        chargePeak = null
    }
}

package ai.safetypoc.app.source

import ai.safetypoc.app.AppConfig
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import engine.GearState
import engine.SignalSource
import engine.VehicleSignal

/**
 * AAOS 표준 Car API 로 속도와 기어를 받는다 (스펙 2절: 속도는 Vehicle SDK 에 없음).
 * 시각은 수신 시점의 System.currentTimeMillis(). onTick 과 같은 시계를 써야 엔진이 맞게 돈다.
 */
class AaosCarPropertySource(private val context: Context) : SignalSource {

    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private var sink: ((VehicleSignal) -> Unit)? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val now = System.currentTimeMillis()
            val signal = when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED ->
                    VehicleSignal.Speed(now, (value.value as Float))
                VehiclePropertyIds.GEAR_SELECTION ->
                    VehicleSignal.Gear(now, toGearState(value.value as Int))
                else -> null
            }
            signal?.let { sink?.invoke(it) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "속성 오류 propertyId=$propertyId areaId=$areaId")
        }
    }

    override fun start(sink: (VehicleSignal) -> Unit) {
        this.sink = sink
        val c = Car.createCar(context) ?: run { Log.e(TAG, "Car 연결 실패"); return }
        car = c
        val m = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        manager = m
        m.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, AppConfig.SPEED_RATE_HZ)
        m.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
    }

    override fun stop() {
        manager?.unregisterCallback(callback)
        car?.disconnect()
        manager = null
        car = null
        sink = null
    }

    private fun toGearState(gear: Int): GearState = when (gear) {
        VehicleGear.GEAR_PARK -> GearState.P
        VehicleGear.GEAR_REVERSE -> GearState.R
        VehicleGear.GEAR_NEUTRAL -> GearState.N
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        // DRIVE 와 수동 1~9단은 모두 전진
        else -> GearState.D
    }

    private companion object { const val TAG = "AaosSource" }
}

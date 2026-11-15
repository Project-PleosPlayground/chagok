package ai.safetypoc.app.source

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.safetypoc.app.AppConfig
import android.content.Context
import android.util.Log
import engine.SignalSource
import engine.TurnSignalState
import engine.VehicleSignal

/**
 * Pleos Vehicle SDK 2.3.2 로 브레이크 페달, 조향각, 방향지시등, 트립 거리를 받는다.
 * API 이름은 Vehicle-2.3.2.aar 의 클래스에서 확인했다 (11/6).
 */
class PleosVehicleSdkSource(private val context: Context) : SignalSource {

    private var vehicle: Vehicle? = null
    private var sink: ((VehicleSignal) -> Unit)? = null

    private fun now() = System.currentTimeMillis()
    private fun emit(signal: VehicleSignal) { sink?.invoke(signal) }
    private fun logFail(what: String, e: Exception?) { Log.w(TAG, "$what 실패: ${e?.message}") }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) { pressed?.let { emit(VehicleSignal.BrakePedal(now(), it)) } }
        override fun onFailed(e: Exception) = logFail("브레이크", e)
    }

    /** 단위·부호는 스펙 9-2 미확인. 엔진 LaneChangeConfig 가 받는 값 그대로 넘긴다. */
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(degrees: Float?) { degrees?.let { emit(VehicleSignal.SteeringAngle(now(), it)) } }
        override fun onFailed(e: Exception) = logFail("조향각", e)
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
            Log.d(TAG, "방향지시등 $value")
            value?.let { emit(VehicleSignal.TurnSignal(now(), toTurnSignalState(it))) }
        }
        override fun onFailed(e: Exception) = logFail("방향지시등", e)
    }

    private val tripListener = object : DistanceDrivenListener {
        override fun onDistanceDrivenUpdated(distance: Float?) {
            distance?.let { emit(VehicleSignal.TripDistance(now(), it * AppConfig.TRIP_DISTANCE_TO_METERS)) }
        }
        override fun onFailed(e: Exception) = logFail("트립 거리", e)
    }

    override fun start(sink: (VehicleSignal) -> Unit) {
        this.sink = sink
        val v = Vehicle(context)
        vehicle = v
        v.initialize()
        v.getBrake().registerBrakePedalStatus(brakeListener)
        v.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        v.getTurnSignal().registerTurnSignalState(turnSignalListener)
        v.getOdometer().registerTripCurrentDrivingDistance(tripListener)
    }

    override fun stop() {
        vehicle?.let { v ->
            runCatching { v.getBrake().unregisterBrakePedalStatus(brakeListener) }
            runCatching { v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
            runCatching { v.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
            runCatching { v.getOdometer().unregisterTripCurrentDrivingDistance(tripListener) }
            v.release()
        }
        vehicle = null
        sink = null
    }

    private fun toTurnSignalState(value: TurnSignalValue): TurnSignalState = when (value) {
        TurnSignalValue.LEFT -> TurnSignalState.LEFT
        TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
        TurnSignalValue.OFF -> TurnSignalState.OFF
    }

    private companion object { const val TAG = "PleosSource" }
}

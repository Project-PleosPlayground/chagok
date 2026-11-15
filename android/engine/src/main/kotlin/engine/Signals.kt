package engine

/**
 * 입력 신호 (스펙 2절·6절). 출처(Pleos SDK, AAOS CarPropertyManager, JSON 시나리오)와 무관하게 같은 모양으로 들어온다.
 * 모든 신호는 timestampMs 를 가진다. 엔진은 시스템 시계를 읽지 않는다.
 */
sealed interface VehicleSignal {
    val timestampMs: Long

    /** m/s. AAOS PERF_VEHICLE_SPEED. 후진 시 음수일 수 있음(미확인). */
    data class Speed(override val timestampMs: Long, val mps: Float) : VehicleSignal

    /** Pleos Brake.registerBrakePedalStatus → Boolean 뿐 (11/2 AAR 확인). 퍼센트는 없다. */
    data class BrakePedal(override val timestampMs: Long, val pressed: Boolean) : VehicleSignal

    /**
     * 도(degree). Pleos SteeringWheel.registerSteeringWheelAngle. 문서상 앞바퀴 각, 왼쪽 음수.
     * 핸들 각인지 앞바퀴 각인지는 에뮬레이터에서 확인 필요 (카드 90° → SDK 값 90 인지 6 인지). θ1 은 config.
     */
    data class SteeringAngle(override val timestampMs: Long, val degrees: Float) : VehicleSignal

    data class TurnSignal(override val timestampMs: Long, val state: TurnSignalState) : VehicleSignal

    data class Gear(override val timestampMs: Long, val gear: GearState) : VehicleSignal

    /** 누적 트립 거리, m. Pleos Odometer.registerTripCurrentDrivingDistance. 엔진은 증분만 쓴다. */
    data class TripDistance(override val timestampMs: Long, val meters: Float) : VehicleSignal

    /** km/h. NaviHelper. null = 안내 중 아님. */
    data class SpeedLimit(override val timestampMs: Long, val kmh: Int?) : VehicleSignal
}

/** SDK TurnSignalValue 는 OFF/LEFT/RIGHT 뿐 (11/2 확인). BOTH 는 JSON 호환용으로만 남기며 매칭에서는 OFF 로 취급. */
enum class TurnSignalState { OFF, LEFT, RIGHT, BOTH }

enum class GearState { P, R, N, D, UNKNOWN }

/** 신호 공급자. 구현체(Pleos SDK, AAOS, JSON)를 바꿔 끼운다. */
interface SignalSource {
    fun start(sink: (VehicleSignal) -> Unit)
    fun stop()
}

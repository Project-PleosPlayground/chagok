package engine

import com.google.gson.Gson
import java.io.Reader

/**
 * 7-1 JSON 시나리오 재생기. 신호와 틱을 t 순서로 흘린다. 같은 t 의 신호는 파일 순서대로.
 *
 * {
 *   "name": "S3_harsh_brake",
 *   "signals": [ {"t":0,"speed":16.67}, {"t":0,"gear":"D"}, {"t":1000,"brake":true}, ... ],
 *   "tickEveryMs": 1000,
 *   "endAt": 200000,                 // 마지막 신호 뒤 이 시각까지 틱만 흘려 정차 종료를 일으킨다
 *   "distanceKmOverride": 10,        // t=0 에 TripDistance(0), 마지막 신호 시각에 TripDistance(override km) 주입
 *   "config": { "sessionEndIdleMs": 10000, "feedback": { "sameTypeMinMs": 60000 } },   // 선택. EngineConfig 일부 덮어쓰기
 *   "expect": { ... }                // 테스트가 읽는다. 재생기는 무시
 * }
 * brake 는 true/false 또는 숫자(>0 이면 밟음). turnSignal 은 OFF/LEFT/RIGHT/BOTH. gear 는 P/R/N/D/UNKNOWN.
 */
class FakeScenarioSource(private val scenario: Scenario) : SignalSource {
    /** 틱은 sink 가 아니라 여기로 간다. 엔진의 onTick 을 넘긴다. */
    var onTick: (Long) -> Unit = {}

    override fun start(sink: (VehicleSignal) -> Unit) {
        val signals = scenario.toSignals()
        val lastSignalT = signals.lastOrNull()?.timestampMs ?: 0L
        val tick = scenario.tickEveryMs
        var nextTick = 0L
        for (s in signals) {
            while (nextTick < s.timestampMs) { onTick(nextTick); nextTick += tick }
            sink(s)
        }
        val end = maxOf(scenario.endAt, lastSignalT)
        while (nextTick <= end) { onTick(nextTick); nextTick += tick }
    }

    override fun stop() = Unit

    companion object {
        private val gson = Gson()
        fun parse(reader: Reader): Scenario = gson.fromJson(reader, Scenario::class.java)
        fun parse(json: String): Scenario = gson.fromJson(json, Scenario::class.java)
    }
}

class Scenario {
    var name: String = ""
    var signals: List<Map<String, Any?>> = emptyList()
    var tickEveryMs: Long = 1000
    var endAt: Long = 0
    var distanceKmOverride: Double? = null
    var config: Map<String, Any?>? = null
    var expect: Map<String, Any?>? = null

    fun toSignals(): List<VehicleSignal> {
        val out = ArrayList<VehicleSignal>()
        val override = distanceKmOverride
        if (override != null) out += VehicleSignal.TripDistance(0, 0f)
        for (row in signals) {
            val t = (row["t"] as Number).toLong()
            row["speed"]?.let { out += VehicleSignal.Speed(t, (it as Number).toFloat()) }
            row["brake"]?.let {
                val pressed = when (it) { is Boolean -> it; is Number -> it.toFloat() > 0f; else -> false }
                out += VehicleSignal.BrakePedal(t, pressed)
            }
            row["steering"]?.let { out += VehicleSignal.SteeringAngle(t, (it as Number).toFloat()) }
            row["turnSignal"]?.let { out += VehicleSignal.TurnSignal(t, TurnSignalState.valueOf(it as String)) }
            row["gear"]?.let { out += VehicleSignal.Gear(t, GearState.valueOf(it as String)) }
            row["tripDistance"]?.let { out += VehicleSignal.TripDistance(t, (it as Number).toFloat()) }
            if (row.containsKey("speedLimit")) out += VehicleSignal.SpeedLimit(t, (row["speedLimit"] as? Number)?.toInt())
        }
        if (override != null) {
            val lastT = out.maxOfOrNull { it.timestampMs } ?: 0L
            out += VehicleSignal.TripDistance(lastT, (override * 1000).toFloat())
        }
        // 안정 정렬: 같은 t 는 입력 순서 유지
        return out.sortedBy { it.timestampMs }
    }
}

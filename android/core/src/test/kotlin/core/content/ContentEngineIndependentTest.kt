package core.content

import engine.DefaultSafetyScoreEngine
import engine.EngineConfig
import engine.EngineTuning
import engine.EventType
import engine.GearState
import engine.Presets
import engine.SafetyScoreEngine
import engine.SessionResult
import engine.SeverityMode
import engine.TurnSignalState
import engine.VehicleSignal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * 원격 콘텐츠 묶음의 `engine` 항목 (스펙 6-1, #31) 을 운영 실수·악용 관점에서 본 독립 테스트.
 * 스펙이 말하지 않는 부분은 "가정:" 주석으로 남겼다.
 */
class ContentEngineIndependentTest {

    companion object {
        const val APP = 1_000
        val BL = mapOf("accel1Kmhs" to 10.0, "accel2Kmhs" to 15.0, "brake1Kmhs" to 13.0, "brake2Kmhs" to 19.5, "brakeStableAccel" to -1.0)
        val KO = mapOf("accel1Kmhs" to 11.0, "accel2Kmhs" to 16.5, "brake1Kmhs" to 7.5, "brake2Kmhs" to 11.25, "brakeStableAccel" to -1.0)
        val BASE = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)
        private val J = Json
    }

    private val bundled get() = BundledContent.pack
    private fun pack(v: Int, engine: Map<String, Double> = emptyMap()) = bundled.copy(contentVersion = v, engine = engine)

    /** engineRaw 를 JSON 텍스트 그대로 끼워 넣은 묶음 (중복 키·문자열 값 등 직렬화기로 만들 수 없는 모양) */
    private fun rawPack(v: Int, engineRaw: String?): String {
        val obj = J.parseToJsonElement(ContentCodec.encode(pack(v))).jsonObject
        val without = JsonObject(obj.filterKeys { it != "engine" }).toString()
        return if (engineRaw == null) without else without.dropLast(1) + ",\"engine\":" + engineRaw + "}"
    }

    private fun verify(text: String, version: Int, active: Int = bundled.contentVersion): ContentVerdict {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return ContentUpdate.verify(bytes, ContentManifest(version, 1, "content/v$version.json", ContentUpdate.sha256Hex(bytes)), active, APP)
    }

    private fun verify(p: ContentPack, active: Int = bundled.contentVersion) = verify(ContentCodec.encode(p), p.contentVersion, active)

    private fun assertAccept(v: ContentVerdict): ContentPack {
        assertTrue(v is ContentVerdict.Accept, "받아야 함: $v")
        return (v as ContentVerdict.Accept).pack
    }

    private fun assertReject(v: ContentVerdict, reason: ContentRejection? = null) {
        assertTrue(v is ContentVerdict.Reject, "거절돼야 함: $v")
        if (reason != null) assertEquals(reason, (v as ContentVerdict.Reject).reason)
    }

    // ───────────── 내장 묶음, 버전 기록 ─────────────

    @Test
    fun `내장 묶음은 엔진 값이 없고 paramsVersion 0`() {
        assertTrue(bundled.engine.isEmpty())
        val c = bundled.engineConfig(BASE.copy(paramsVersion = 4))
        assertEquals(0, c.paramsVersion)
        assertEquals(BASE.thresholds, c.thresholds)
    }

    @Test
    fun `정상 묶음 - paramsVersion 은 그 묶음의 contentVersion`() {
        val p = assertAccept(verify(pack(7, KO + ("penaltyWeaving" to 9.0))))
        val c = p.engineConfig(BASE)
        assertEquals(7, c.paramsVersion)
        assertEquals(Presets.KOTSA.brake1, c.thresholds.brake1)
        assertEquals(9f, c.penalty.weaving)
    }

    @Test
    fun `엔진 값 없는 새 묶음이면 paramsVersion 0`() {
        val p = assertAccept(verify(pack(8)))
        assertEquals(0, p.engineConfig(BASE.copy(paramsVersion = 7)).paramsVersion)
    }

    @Test
    fun `인코딩·디코딩을 거쳐도 엔진 값이 그대로`() {
        val p = pack(5, KO + mapOf("signalMinOnSec" to 0.7, "steeringRatio" to 14.25, "brakeStableSec" to 0.3))
        val back = ContentCodec.decode(ContentCodec.encode(p))
        assertEquals(p.engine, back.engine)
        assertEquals(p.engineConfig(BASE), back.engineConfig(BASE))
    }

    // ───────────── 운영 실수 ─────────────

    @Test
    fun `km per h per s 자리에 m per s2 값을 넣으면 거절 (하한 5 미만)`() {
        val mps2 = mapOf("accel1Kmhs" to 2.78, "accel2Kmhs" to 4.17, "brake1Kmhs" to 3.61, "brake2Kmhs" to 5.42, "brakeStableAccel" to -1.0)
        assertTrue(ContentValidator.problems(pack(2, mps2)).isNotEmpty())
        assertReject(verify(pack(2, mps2)), ContentRejection.INVALID_CONTENT)
        // 0.25 간격에 맞는 m/s² 값 (3.5 등) 이어도 하한 5 아래라 거절
        assertReject(verify(pack(2, BL + ("brake1Kmhs" to 3.5))), ContentRejection.INVALID_CONTENT)
    }

    @Test
    fun `정수 리터럴 13 은 13_0 과 같게 받는다`() {
        val raw = """{"accel1Kmhs":10,"accel2Kmhs":15,"brake1Kmhs":13,"brake2Kmhs":19.5,"brakeStableAccel":-1}"""
        val p = assertAccept(verify(rawPack(2, raw), 2))
        assertEquals(Presets.BLUELINK.brake1, p.engineConfig(BASE).thresholds.brake1)
    }

    @Test
    fun `문자열 값 13 은 거절`() {
        // 가정: 스펙 6-1 "이름과 실제 값(예: "brake1Kmhs": 13.0)". 숫자가 아닌 JSON 문자열은 형식 오류로 본다.
        val raw = """{"accel1Kmhs":10.0,"accel2Kmhs":15.0,"brake1Kmhs":"13","brake2Kmhs":19.5,"brakeStableAccel":-1.0}"""
        assertReject(verify(rawPack(2, raw), 2))
    }

    @Test
    fun `불리언 true 는 0·1 이 아니라 거절`() {
        // 가정: 켬·끔은 0·1 로 쓴다(6-1). JSON true 는 형식 오류.
        assertReject(verify(rawPack(2, """{"steeringInverted":true}"""), 2))
    }

    @Test
    fun `아주 큰 수는 거절`() {
        assertReject(verify(rawPack(2, """{"penaltyWeaving":1e308}"""), 2))
        assertReject(verify(rawPack(2, """{"penaltyWeaving":-1e308}"""), 2))
        assertReject(verify(rawPack(2, """{"sessionEndIdleSec":1e400}"""), 2))   // 파서가 Infinity 로 읽을 수 있다
        assertReject(verify(rawPack(2, """{"feedbackMaxPerType":99999999999999999999}"""), 2))
    }

    @Test
    fun `음의 0 은 0 으로 받는다`() {
        val p = assertAccept(verify(rawPack(2, """{"penaltyLaneNoSignal":-0.0,"steeringInverted":-0.0}"""), 2))
        val c = p.engineConfig(BASE.copy(laneChange = BASE.laneChange.copy(steeringInverted = true)))
        assertFalse(c.laneChange.steeringInverted)
        assertTrue(c.penalty.laneNoSignal == 0f)
    }

    @Test
    fun `중복 키 - 뒤의 값이 앞의 잘못된 값을 가리면 안 된다`() {
        // 가정: 스펙 "값 하나라도 범위를 어기면 묶음 전체를 버린다". 중복 키로 잘못된 값이 조용히 사라지면 이 규칙이 무력해진다.
        assertReject(verify(rawPack(2, """{"penaltyWeaving":99.0,"penaltyWeaving":8.0}"""), 2))
    }

    @Test
    fun `중복 키 - 둘 다 유효해도 어느 값을 쓸지 모호하므로 거절`() {
        // 가정: 운영자가 값을 직접 고치는 형식(6-1 전송 형식)이라 중복 키는 실수로 본다.
        assertReject(verify(rawPack(2, """{"penaltyWeaving":8.0,"penaltyWeaving":31.5}"""), 2))
    }

    @Test
    fun `engine 이 null 이면 거절하거나 빈 값으로 본다`() {
        // 가정: 스펙이 말하지 않는다. 받는다면 엔진 값 없음(paramsVersion 0)이어야 한다.
        when (val v = verify(rawPack(2, "null"), 2)) {
            is ContentVerdict.Reject -> {}
            is ContentVerdict.Accept -> {
                assertTrue(v.pack.engine.isEmpty())
                assertEquals(0, v.pack.engineConfig(BASE).paramsVersion)
            }
        }
    }

    @Test
    fun `engine 이 배열·숫자·문자열이면 거절`() {
        assertReject(verify(rawPack(2, """[{"penaltyWeaving":8.0}]"""), 2), ContentRejection.PARSE_ERROR)
        assertReject(verify(rawPack(2, "8"), 2), ContentRejection.PARSE_ERROR)
        assertReject(verify(rawPack(2, "\"x\""), 2), ContentRejection.PARSE_ERROR)
    }

    @Test
    fun `엔진 값 중 null 은 거절`() {
        assertReject(verify(rawPack(2, """{"penaltyWeaving":null}"""), 2))
    }

    @Test
    fun `모르는 이름이 있으면 거절`() {
        assertReject(verify(pack(2, KO + ("brake1Kmh" to 7.5))))
        assertReject(verify(pack(2, mapOf("persistMs" to 300.0))))
        assertReject(verify(pack(2, mapOf("_note" to 0.0))))
    }

    @Test
    fun `묶음 일부만 보내면 거절`() {
        assertReject(verify(pack(2, KO - "brakeStableAccel")), ContentRejection.INVALID_CONTENT)
        assertReject(verify(pack(2, mapOf("feedbackSameTypeMinSec" to 60.0))), ContentRejection.INVALID_CONTENT)
    }

    @Test
    fun `하나만 틀려도 묶음 전체 거절 - 활성 묶음 유지`() {
        val state = ContentState(pack(3, BL))
        val v = verify(pack(4, KO + ("capLane" to 101.0)), state.newestVersion)
        assertReject(v, ContentRejection.INVALID_CONTENT)
        // :app 흐름: Accept 일 때만 stage. 거절이면 그대로
        assertEquals(3, state.active.contentVersion)
        assertEquals(3, state.active.engineConfig(BASE).paramsVersion)
    }

    @Test
    fun `검증 안 된 묶음에 engineConfig 를 부르면 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> { pack(2, mapOf("capLane" to 101.0)).engineConfig(BASE) }
        assertThrows<IllegalArgumentException> { pack(2, mapOf("penaltyWeaving" to Double.NaN)).engineConfig(BASE) }
        assertThrows<IllegalArgumentException> { pack(2, mapOf("nope" to 1.0)).engineConfig(BASE) }
    }

    @Test
    fun `NaN·Infinity 가 들어간 묶음은 검증에서 거절`() {
        assertTrue(ContentValidator.problems(pack(2, mapOf("penaltyWeaving" to Double.NaN))).isNotEmpty())
        assertTrue(ContentValidator.problems(pack(2, mapOf("penaltyWeaving" to Double.POSITIVE_INFINITY))).isNotEmpty())
    }

    // ───────────── 버전 ─────────────

    @Test
    fun `엔진 값만 바꾸고 contentVersion 을 안 올리면 NOT_NEWER`() {
        assertReject(verify(pack(1, KO), active = 1), ContentRejection.NOT_NEWER)
        val state = ContentState(pack(5, BL))
        assertFalse(state.stage(pack(5, KO)))
        assertEquals(BL, state.active.engine)
    }

    @Test
    fun `내려간 버전은 받지 않는다`() {
        val state = ContentState(bundled)
        assertTrue(state.stage(pack(3, KO)))
        assertFalse(state.stage(pack(2, BL)), "적용 대기 중인 것보다 낮은 버전")
        state.applyStaged()
        assertFalse(state.stage(pack(2, BL)), "활성보다 낮은 버전")
        assertReject(verify(pack(2, BL), active = state.newestVersion), ContentRejection.NOT_NEWER)
        assertEquals(3, state.active.engineConfig(BASE).paramsVersion)
        assertEquals(Presets.KOTSA.brake1, state.active.engineConfig(BASE).thresholds.brake1)
    }

    @Test
    fun `적용 대기 묶음의 엔진 값은 applyStaged 전에는 안 쓴다`() {
        val state = ContentState(bundled)
        assertTrue(state.stage(pack(2, KO)))
        assertEquals(0, state.active.engineConfig(BASE).paramsVersion)
        assertEquals(Presets.BLUELINK.brake1, state.active.engineConfig(BASE).thresholds.brake1)
        val applied = state.applyStaged()
        assertEquals(2, applied!!.contentVersion)
        assertEquals(2, state.active.engineConfig(BASE).paramsVersion)
        assertEquals(Presets.KOTSA.brake1, state.active.engineConfig(BASE).thresholds.brake1)
        assertNull(state.applyStaged())
    }

    // ───────────── 앱 시작 ─────────────

    @Test
    fun `저장된 묶음의 엔진 값이 틀리면 시작할 때 내장 묶음`() {
        val badRange = ContentCodec.encode(pack(4, KO + ("capLane" to 101.0)))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, badRange, APP))
        val partial = ContentCodec.encode(pack(4, KO - "brake2Kmhs"))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, partial, APP))
        val unknown = ContentCodec.encode(pack(4, mapOf("zzz" to 1.0)))
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, unknown, APP))
        val dup = rawPack(4, """{"penaltyWeaving":99.0,"penaltyWeaving":8.0}""")
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, dup, APP), "중복 키로 숨긴 잘못된 값 (가정: 거절)")
        val str = rawPack(4, """{"penaltyWeaving":"8"}""")
        assertSame(bundled, ContentUpdate.pickAtStartup(bundled, str, APP), "문자열 값 (가정: 거절)")
    }

    @Test
    fun `저장된 묶음이 정상이면 그 버전으로 엔진 설정`() {
        val picked = ContentUpdate.pickAtStartup(bundled, ContentCodec.encode(pack(6, KO)), APP)
        assertEquals(6, picked.contentVersion)
        assertEquals(6, picked.engineConfig(BASE).paramsVersion)
    }

    // ───────────── 엔진까지 (end-to-end) ─────────────

    private class Drv(val e: SafetyScoreEngine) {
        var t = 0L; var v = 0f; var dist = 0f; var pedal = false
        fun push() {
            e.onSignal(VehicleSignal.Speed(t, v)); e.onSignal(VehicleSignal.BrakePedal(t, pedal))
            e.onSignal(VehicleSignal.SteeringAngle(t, 0f)); e.onSignal(VehicleSignal.TurnSignal(t, TurnSignalState.OFF))
            e.onSignal(VehicleSignal.TripDistance(t, dist)); e.onTick(t)
        }
        fun step(a: Float) { t += 100; v = (v + a * 0.1f).coerceAtLeast(0f); dist += v * 0.1f; push() }
        fun hold(ms: Long, a: Float = 0f) = repeat((ms / 100).toInt()) { step(a) }
        fun rampTo(target: Float) {
            while (v < target - 1e-3f) step(minOf(2f, (target - v) / 0.1f))
            while (v > target + 1e-3f) step(-minOf(2f, (v - target) / 0.1f))
        }
        /** 출발 → 20 m/s → 2.5 m/s² 제동(페달) → 정지 → 강제 종료 */
        fun sessionWithModerateBrake(): SessionResult? {
            e.onSignal(VehicleSignal.Gear(t, GearState.D)); push()
            rampTo(20f); hold(5_000)
            pedal = true; hold(2_000, -2.5f); pedal = false; hold(5_000)
            rampTo(0f); hold(1_000)
            return e.endSessionNow(t)
        }
    }

    @Test
    fun `묶음 → engineConfig → updateConfig → 세션에 묶음 버전과 수치가 남는다`() {
        val engine = DefaultSafetyScoreEngine(bundled.engineConfig(BASE))
        val d = Drv(engine)
        val r0 = d.sessionWithModerateBrake()!!
        assertEquals(0, r0.paramsVersion)
        assertEquals(0, r0.events.count { it.type == EventType.HARSH_BRAKE })

        val remotePack = assertAccept(verify(pack(9, KO)))
        engine.updateConfig(remotePack.engineConfig(BASE))
        val r1 = d.sessionWithModerateBrake()!!
        assertEquals(9, r1.paramsVersion)
        assertEquals(1, r1.events.count { it.type == EventType.HARSH_BRAKE })

        // 내장 묶음으로 되돌리면 다시 0
        engine.updateConfig(bundled.engineConfig(BASE))
        val r2 = d.sessionWithModerateBrake()!!
        assertEquals(0, r2.paramsVersion)
        assertEquals(0, r2.events.count { it.type == EventType.HARSH_BRAKE })
        assertEquals(1, r1.events.count { it.type == EventType.HARSH_BRAKE }, "지난 세션 결과는 그대로")
    }

    // ═════════════ 2단계: 경계값·사이드 이펙트·회귀 (주의사항 14·15, 6-1) ═════════════

    @Nested
    inner class Stage2 {

        @Test
        fun `엔진 값을 뺀 새 묶음으로 바뀌면 내장 기본값으로 완전히 돌아간다`() {
            val state = ContentState(pack(5, KO + ("capLane" to 20.0)))
            assertTrue(state.stage(pack(6)))
            state.applyStaged()
            assertEquals(BASE, state.active.engineConfig(BASE), "스펙 6-1: 원격 수치 없이 내장 기본값만 쓰면 0")
        }

        @Test
        fun `base 의 paramsVersion 은 무시 - 값이 있으면 contentVersion, 없으면 0`() {
            assertEquals(4, pack(4, mapOf("capLane" to 20.0)).engineConfig(BASE.copy(paramsVersion = 7)).paramsVersion)
            assertEquals(0, pack(4).engineConfig(BASE.copy(paramsVersion = 7)).paramsVersion)
        }

        @Test
        fun `내장 기본값과 같은 값을 보내도 원격 값이 있는 것 - paramsVersion 은 contentVersion`() {
            val defaults = mapOf("penaltyAccel" to 4.0, "penaltyAccelSevere" to 6.0, "sessionEndIdleSec" to 180.0)
            val c = assertAccept(verify(pack(3, defaults))).engineConfig(BASE)
            assertEquals(3, c.paramsVersion)
            assertEquals(BASE.copy(paramsVersion = 3), c)
        }

        @Test
        fun `앱 설정(시연 10초 등)은 묶음에 없는 수치라 그대로`() {
            val demo = BASE.copy(sessionEndIdleMs = 10_000, thresholds = Presets.KOTSA)
            val c = pack(4, mapOf("capLane" to 20.0)).engineConfig(demo)
            assertEquals(10_000L, c.sessionEndIdleMs)
            assertEquals(Presets.KOTSA, c.thresholds)
        }

        @Test
        fun `허용 오차 안의 값은 간격 값으로 바뀌어 프리셋과 비트까지 같다`() {
            val raw = """{"accel1Kmhs":10.0,"accel2Kmhs":15.0,"brake1Kmhs":13.0000001,"brake2Kmhs":19.5,"brakeStableAccel":-1.0}"""
            val c = assertAccept(verify(rawPack(2, raw), 2)).engineConfig(BASE)
            assertEquals(Presets.BLUELINK.brake1.toRawBits(), c.thresholds.brake1.toRawBits())
        }

        @Test
        fun `F_same 새 범위 10~640 - 묶음에서도 같은 경계`() {
            assertAccept(verify(pack(2, mapOf("feedbackSameTypeMinSec" to 10.0, "feedbackAnyTypeMinSec" to 5.0))))
            assertAccept(verify(pack(2, mapOf("feedbackSameTypeMinSec" to 640.0, "feedbackAnyTypeMinSec" to 320.0))))
            assertReject(verify(pack(2, mapOf("feedbackSameTypeMinSec" to 0.0, "feedbackAnyTypeMinSec" to 5.0))), ContentRejection.INVALID_CONTENT)
            assertReject(verify(pack(2, mapOf("feedbackSameTypeMinSec" to 650.0, "feedbackAnyTypeMinSec" to 5.0))), ContentRejection.INVALID_CONTENT)
        }

        @Test
        fun `검증 결과에 미션 문제와 엔진 문제가 함께 남는다`() {
            val t = bundled.missions.conditions
            val broken = pack(2, mapOf("capLane" to 101.0)).copy(
                missions = bundled.missions.copy(conditions = listOf(t.first().copy(nameKey = "")) + t.drop(1)),
            )
            val p = ContentValidator.problems(broken)
            assertTrue(p.any { "capLane" in it }, "엔진 문제: $p")
            assertTrue(p.any { "콘텐츠 텍스트에 없는 키" in it }, "미션 문제: $p")
            val v = verify(broken)
            assertReject(v, ContentRejection.INVALID_CONTENT)
            assertEquals(p, (v as ContentVerdict.Reject).detail)
        }

        @Test
        fun `engine 항목 이름을 틀리면 (최상위 모르는 필드) 엔진 값 없이 받아진다 - 현재 동작 고정`() {
            // 사실 기록: ContentCodec 은 최상위 모르는 필드를 무시한다(구버전 앱 호환). 운영자가 "engine" 을 "engin" 으로 쓰면
            // 수치가 조용히 빠지고 paramsVersion 은 0 이 된다. 스펙 6-1 의 "모르는 이름 거절"은 engine 안의 이름만 말한다.
            val text = rawPack(2, null).dropLast(1) + ""","engin":{"capLane":20.0}}"""
            val p = assertAccept(verify(text, 2))
            assertTrue(p.engine.isEmpty())
            assertEquals(0, p.engineConfig(BASE).paramsVersion)
        }

        @Test
        fun `스토어 업데이트로 내장 묶음이 더 새것이면 저장된 엔진 값은 버린다`() {
            val newerBundled = pack(6)
            val picked = ContentUpdate.pickAtStartup(newerBundled, ContentCodec.encode(pack(5, KO)), APP)
            assertSame(newerBundled, picked)
            assertEquals(0, picked.engineConfig(BASE).paramsVersion)
        }

        @Test
        fun `원격 임계값을 쓰면 이름은 REMOTE, 안 쓰면 고른 프리셋 이름`() {
            assertEquals(EngineTuning.REMOTE_THRESHOLDS_NAME, pack(2, BL).engineConfig(BASE).thresholds.name)
            assertEquals(Presets.KOTSA.name, pack(2, mapOf("capLane" to 20.0)).engineConfig(BASE.copy(thresholds = Presets.KOTSA)).thresholds.name)
        }
    }
}

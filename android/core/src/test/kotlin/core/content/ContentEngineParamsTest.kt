package core.content

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import engine.EngineConfig
import engine.Presets
import engine.SeverityMode

/** 콘텐츠 묶음의 엔진 수치 (스펙 6-1, #33) */
class ContentEngineParamsTest {
    private val bundled = BundledContent.pack
    private val appVersion = 5
    private val base = EngineConfig(thresholds = Presets.BLUELINK, severityMode = SeverityMode.RECORD_ONLY)

    private fun next(engine: Map<String, Double>) = bundled.copy(contentVersion = bundled.contentVersion + 1, engine = engine)

    /** 서버에 올라가는 것과 같은 바이트로 verify 까지 */
    private fun verifyText(text: String): ContentVerdict {
        val b = text.toByteArray(Charsets.UTF_8)
        val m = ContentManifest(bundled.contentVersion + 1, 1, "packs/x.json", ContentUpdate.sha256Hex(b))
        return ContentUpdate.verify(b, m, bundled.contentVersion, appVersion)
    }

    /** 내장 묶음 JSON 에 engine 항목을 끼운 텍스트 */
    private fun packJson(engineJson: String): String {
        val json = ContentCodec.encode(bundled.copy(contentVersion = bundled.contentVersion + 1))
        return json.replace("\"engine\":{}", "\"engine\":$engineJson")
    }

    @Test fun `내장 묶음에는 엔진 수치가 없고 앱 기본값을 버전 0 으로 쓴다`() {
        assertTrue(bundled.engine.isEmpty())
        assertEquals(base.copy(paramsVersion = EngineConfig.BUILT_IN_PARAMS_VERSION), bundled.engineConfig(base))
    }

    @Test fun `엔진 수치가 든 묶음을 받아 설정을 만든다`() {
        val v = verifyText(packJson("""{"capBrake":40,"praiseFirstSignaled":0,"sessionEndIdleSec":240}"""))
        val pack = (v as ContentVerdict.Accept).pack
        val cfg = pack.engineConfig(base)
        assertEquals(40f, cfg.penalty.brakeCap)
        assertEquals(false, cfg.feedback.praiseFirstSignaled)
        assertEquals(240_000L, cfg.sessionEndIdleMs)
        assertEquals(pack.contentVersion, cfg.paramsVersion)
    }

    @Test fun `engine 항목이 없는 예전 형식 묶음도 읽는다`() {
        val json = ContentCodec.encode(bundled.copy(contentVersion = bundled.contentVersion + 1)).replace(",\"engine\":{}", "")
        assertTrue(!json.contains("engine"))
        val pack = (verifyText(json) as ContentVerdict.Accept).pack
        assertTrue(pack.engine.isEmpty())
    }

    @Test fun `범위 밖 엔진 수치가 있으면 묶음 전체를 버린다`() {
        val v = verifyText(packJson("""{"capBrake":101}"""))
        v as ContentVerdict.Reject
        assertEquals(ContentRejection.INVALID_CONTENT, v.reason)
        assertTrue(v.detail.any { "capBrake" in it }, v.detail.toString())
    }

    @Test fun `모르는 엔진 수치 이름이면 버린다`() {
        val v = verifyText(packJson("""{"theta0Deg":5}"""))
        assertEquals(ContentRejection.INVALID_CONTENT, (v as ContentVerdict.Reject).reason)
    }

    @Test fun `묶음 일부만 오거나 관계 규칙을 어기면 버린다`() {
        assertEquals(ContentRejection.INVALID_CONTENT, (verifyText(packJson("""{"accel1Kmhs":10}""")) as ContentVerdict.Reject).reason)
        assertTrue(ContentValidator.problems(next(mapOf("feedbackSameTypeMinSec" to 10.0, "feedbackAnyTypeMinSec" to 20.0))).isNotEmpty())
    }

    @Test fun `숫자가 아닌 값은 형식 오류로 버린다`() {
        for (bad in listOf("""{"praiseFirstSignaled":true}""", """{"capBrake":"사십"}""", """{"capBrake":null}""", """[]""", "null")) {
            assertTrue(verifyText(packJson(bad)) is ContentVerdict.Reject, bad)
        }
    }

    @Test fun `따옴표로 감싼 숫자가 있으면 묶음 전체를 버린다`() {
        assertEquals(ContentRejection.PARSE_ERROR, (verifyText(packJson("""{"capBrake":"40"}""")) as ContentVerdict.Reject).reason)
        // 엔진 수치 밖의 숫자도 같다
        val quotedVersion = packJson("{}").replaceFirst("\"contentVersion\":${bundled.contentVersion + 1}", "\"contentVersion\":\"${bundled.contentVersion + 1}\"")
        assertTrue(quotedVersion.contains("\"contentVersion\":\""))
        assertEquals(ContentRejection.PARSE_ERROR, (verifyText(quotedVersion) as ContentVerdict.Reject).reason)
        // 문자열 자리의 숫자 모양 글자는 그대로 받는다 (제목 "10km" 등)
        assertTrue(verifyText(packJson("{}")) is ContentVerdict.Accept)
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, packJson("""{"capBrake":"40"}"""), appVersion))
    }

    @Test fun `한 객체에 같은 이름이 두 번 있으면 묶음 전체를 버린다`() {
        for (dup in listOf("""{"capBrake":101,"capBrake":40}""", """{"capBrake":40,"capBrake":41}""", """{"capBrake":40,"capBrake":41}""")) {
            assertEquals(ContentRejection.PARSE_ERROR, (verifyText(packJson(dup)) as ContentVerdict.Reject).reason, dup)
        }
        // 다른 객체에 같은 이름이 있는 것은 정상이다 (미션마다 id 가 있는 것처럼)
        assertTrue(verifyText(packJson("""{"capBrake":40}""")) is ContentVerdict.Accept)
        // 값 문자열 안의 따옴표·쉼표·중괄호는 이름으로 보지 않는다
        val tricky = packJson("{}").replaceFirst("\"title\":\"", "\"title\":\"\\\"a\\\", {\\\"id\\\": ")
        assertTrue(verifyText(tricky) is ContentVerdict.Accept)
        // 최상위 이름이 겹쳐도 같다
        val topDup = packJson("{}").replaceFirst("{", "{\"minAppVersion\":1,")
        assertEquals(ContentRejection.PARSE_ERROR, (verifyText(topDup) as ContentVerdict.Reject).reason)
    }

    @Test fun `저장해 둔 묶음의 엔진 수치가 잘못됐으면 시작할 때 내장 묶음을 쓴다`() {
        val text = packJson("""{"capBrake":101}""")
        assertEquals(bundled, ContentUpdate.pickAtStartup(bundled, text, appVersion))
    }

    @Test fun `저장해 둔 묶음의 엔진 수치가 맞으면 시작할 때 그것을 쓴다`() {
        val picked = ContentUpdate.pickAtStartup(bundled, packJson("""{"capBrake":40}"""), appVersion)
        assertEquals(mapOf("capBrake" to 40.0), picked.engine)
    }
}

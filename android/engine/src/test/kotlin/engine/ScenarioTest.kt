package engine

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * src/test/resources/scenarios/ 의 .json 전부를 읽어 expect 와 비교한다. 시나리오 추가 = JSON 파일 추가.
 * 임계값 프리셋(KOTSA / BLUELINK) × 심각도 모드(WEIGHTED / RECORD_ONLY) 네 조합으로 돌린다.
 *
 * expect 키:
 *   events: [{type, severity?, fromStop?}]   순서대로 전부 일치
 *   score: 77                                 모든 조합 공통
 *   scoreByMode: {"WEIGHTED": 77, "RECORD_ONLY": 81}
 *   valid: true/false, validated: true/false (Validated 알림이 나갔는지)
 *   validatedEventCount: n                   Validated.eventsSoFar 크기
 *   cues: [{type, atMs?}]                     feedback 순서대로 전부 일치
 *   liveEventCount: n                         events Flow 로 실시간 방출된 수
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioTest {

    private val presets = listOf(Presets.KOTSA, Presets.BLUELINK)
    private val modes = listOf(SeverityMode.NONE, SeverityMode.WEIGHTED, SeverityMode.RECORD_ONLY)
    private val modeKey = mapOf(SeverityMode.NONE to "a", SeverityMode.WEIGHTED to "b", SeverityMode.RECORD_ONLY to "c")

    @TestFactory
    fun scenarios(): List<DynamicTest> {
        val dir = File(javaClass.getResource("/scenarios")!!.toURI())
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        return files.flatMap { f ->
            presets.flatMap { p -> modes.map { m -> DynamicTest.dynamicTest("${f.nameWithoutExtension} [${p.name}/${m}]") { run(f, p, m) } } }
        }
    }

    private fun run(file: File, preset: Thresholds, mode: SeverityMode) = runTest {
        val sc = FakeScenarioSource.parse(file.readText())
        val cfg = applyOverrides(EngineConfig(thresholds = preset, severityMode = mode), sc.config)
        val engine = DefaultSafetyScoreEngine(cfg)

        val live = ArrayList<DrivingEvent>(); val life = ArrayList<SessionLifecycle>()
        val cues = ArrayList<FeedbackCue>(); val results = ArrayList<SessionResult>()
        val jobs = listOf(
            launch(UnconfinedTestDispatcher(testScheduler)) { engine.events.collect { live += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { engine.lifecycle.collect { life += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { engine.feedback.collect { cues += it } },
            launch(UnconfinedTestDispatcher(testScheduler)) { engine.sessions.collect { results += it } },
        )
        val src = FakeScenarioSource(sc).apply { onTick = engine::onTick }
        src.start(engine::onSignal)
        // 정차 종료가 안 났으면 강제 종료 (시나리오가 endAt 를 짧게 잡은 경우)
        val result = results.firstOrNull() ?: engine.endSessionNow(maxOf(sc.endAt, 0L))
        jobs.forEach { it.cancel() }

        val ex = sc.expect ?: return@runTest
        val tag = "${file.name} [${preset.name}/$mode]"
        val got = result?.events ?: emptyList()

        (ex["events"] as? List<*>)?.let { exp ->
            val summary = got.joinToString { "${it.type}/${it.severity}${if (it.fromStop) "/fromStop" else ""}@${it.startMs}" }
            assertEquals(exp.size, got.size, "$tag event count. got: $summary")
            exp.forEachIndexed { i, e ->
                e as Map<*, *>
                assertEquals(e["type"], got[i].type.name, "$tag event[$i] type. got: $summary")
                if (mode != SeverityMode.NONE) (e["severity"] as? Number)?.let { sev ->
                    assertEquals(sev.toInt(), got[i].severity, "$tag event[$i] severity. got: $summary")
                }
                (e["fromStop"] as? Boolean)?.let { assertEquals(it, got[i].fromStop, "$tag event[$i] fromStop") }
            }
        }
        (ex["valid"] as? Boolean)?.let { assertEquals(it, result?.valid, "$tag valid") }
        (ex["validated"] as? Boolean)?.let { assertEquals(it, life.any { l -> l is SessionLifecycle.Validated }, "$tag Validated emitted") }
        (ex["validatedEventCount"] as? Number)?.let { n ->
            val v = life.filterIsInstance<SessionLifecycle.Validated>().firstOrNull()
            assertEquals(n.toInt(), v?.eventsSoFar?.size, "$tag Validated.eventsSoFar")
        }
        (ex["score"] as? Number)?.let { assertEquals(it.toInt(), result?.score, "$tag score (dist=${result?.distanceKm} km, events=${got.map { e -> e.type }})") }
        (ex["scoreByMode"] as? Map<*, *>)?.let { m ->
            val want = m[mode.name] ?: m[modeKey[mode]]
            if (want is Number && (ex["valid"] as? Boolean) != false) assertEquals(want.toInt(), result?.score, "$tag score[$mode] (dist=${result?.distanceKm} km, events=${got.map { e -> "${e.type}/${e.severity}" }})")
        }
        // ── scenarios/README 형식 ──
        val userCues = cues.filter { it.type != CueType.SESSION_START }
        (ex["feedbackCues"] as? Map<*, *>)?.forEach { (k, v) ->
            assertEquals((v as Number).toInt(), userCues.count { it.type.name == k }, "$tag feedbackCues[$k]. got: ${userCues.map { c -> "${c.type}@${c.atMs}" }}")
        }
        (ex["firstCueAtMs"] as? Map<*, *>)?.let { r ->
            val at = userCues.firstOrNull()?.atMs
            assertTrue(at != null && at >= (r["min"] as Number).toLong() && at <= (r["max"] as Number).toLong(), "$tag firstCueAtMs=$at not in $r")
        }
        (ex["validatedAtMs"] as? Map<*, *>)?.let { r ->
            val at = life.filterIsInstance<SessionLifecycle.Validated>().firstOrNull()?.atMs
            assertTrue(at != null && at >= (r["min"] as Number).toLong() && at <= (r["max"] as Number).toLong(), "$tag validatedAtMs=$at not in $r")
        }
        (ex["eventsBeforeValidated"] as? Number)?.let { n ->
            val v = life.filterIsInstance<SessionLifecycle.Validated>().firstOrNull()
            assertEquals(n.toInt(), v?.eventsSoFar?.size, "$tag eventsBeforeValidated")
        }
        (ex["liveEventCount"] as? Number)?.let { assertEquals(it.toInt(), live.size, "$tag live events: ${live.map { e -> e.type }}") }
        (ex["cues"] as? List<*>)?.let { exp ->
            val summary = cues.joinToString { "${it.type}@${it.atMs}" }
            assertEquals(exp.size, cues.size, "$tag cue count. got: $summary")
            exp.forEachIndexed { i, c ->
                c as Map<*, *>
                assertEquals(c["type"], cues[i].type.name, "$tag cue[$i] type. got: $summary")
                (c["atMs"] as? Number)?.let { assertEquals(it.toLong(), cues[i].atMs, "$tag cue[$i] atMs. got: $summary") }
            }
        }
        assertTrue(result != null || ex.isEmpty(), "$tag no session result")
    }

    private fun applyOverrides(base: EngineConfig, o: Map<String, Any?>?): EngineConfig {
        if (o == null) return base
        fun l(k: String, d: Long) = (o[k] as? Number)?.toLong() ?: d
        fun f(k: String, d: Float) = (o[k] as? Number)?.toFloat() ?: d
        val fb = (o["feedback"] as? Map<*, *>)
        val feedback = base.feedback.copy(
            sameTypeMinMs = (fb?.get("sameTypeMinMs") as? Number)?.toLong() ?: (o["F_same_s"] as? Number)?.let { it.toLong() * 1000 } ?: base.feedback.sameTypeMinMs,
            anyTypeMinMs = (fb?.get("anyTypeMinMs") as? Number)?.toLong() ?: (o["F_all_s"] as? Number)?.let { it.toLong() * 1000 } ?: base.feedback.anyTypeMinMs,
            maxPerTypePerSession = (fb?.get("maxPerTypePerSession") as? Number)?.toInt() ?: (o["N_max"] as? Number)?.toInt() ?: base.feedback.maxPerTypePerSession,
        )
        return base.copy(
            sessionEndIdleMs = l("sessionEndIdleMs", base.sessionEndIdleMs),
            minValidMs = l("minValidMs", base.minValidMs),
            minValidKm = f("minValidKm", base.minValidKm),
            feedback = feedback,
        )
    }
}

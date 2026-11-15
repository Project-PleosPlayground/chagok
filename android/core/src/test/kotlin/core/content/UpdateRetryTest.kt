package core.content

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UpdateRetryTest {
    private val net = UpdateTiming.NETWORK_BACKOFF
    private val integ = UpdateTiming.INTEGRITY_BACKOFF
    private val noJitter = 0.5
    private val t0 = 1_000_000_000_000L

    @Nested
    inner class 지수_백오프 {
        @Test fun `실패마다 두 배로 늘고 상한에서 멈춘다`() {
            val p = BackoffPolicy(1_000, 2.0, 10_000, 0.0)
            assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 10_000L, 10_000L), (1..6).map { p.delayMs(it, noJitter) })
        }

        @Test fun `지터는 ± 비율 안에 있다`() {
            val p = BackoffPolicy(10_000, 2.0, 10_000, 0.2)
            assertEquals(8_000L, p.delayMs(1, 0.0))
            assertEquals(10_000L, p.delayMs(1, noJitter))
            assertEquals(12_000L, p.delayMs(1, 1.0))
            assertEquals(12_000L, p.longestMs)
        }

        @Test fun `이상한 입력에도 넘치거나 음수가 되지 않는다`() {
            assertEquals(net.maxMs, net.delayMs(Int.MAX_VALUE, noJitter))
            assertEquals(net.delayMs(1, noJitter), net.delayMs(0, noJitter))      // 0·음수 실패 수는 1 로 본다
            assertEquals(net.delayMs(1, noJitter), net.delayMs(-5, noJitter))
            assertEquals(net.delayMs(1, 1.0), net.delayMs(1, 7.0))                // random 범위 밖은 잘라 쓴다
            assertEquals(net.delayMs(1, 0.0), net.delayMs(1, -3.0))
            assertEquals(net.delayMs(1, noJitter), net.delayMs(1, Double.NaN))
        }

        @Test fun `잘못된 규칙은 만들 수 없다`() {
            assertThrows<IllegalArgumentException> { BackoffPolicy(0, 2.0, 10, 0.1) }
            assertThrows<IllegalArgumentException> { BackoffPolicy(10, 0.5, 10, 0.1) }
            assertThrows<IllegalArgumentException> { BackoffPolicy(10, 2.0, 5, 0.1) }
            assertThrows<IllegalArgumentException> { BackoffPolicy(10, 2.0, 10, 1.5) }
        }

        @Test fun `통신 실패가 검증 실패보다 짧게 시작하고 짧게 끝난다`() {
            assertTrue(net.initialMs < integ.initialMs)
            assertTrue(net.maxMs < integ.maxMs)
        }
    }

    @Nested
    inner class 통신_실패 {
        @Test fun `간격 동안 확인하지 않고 지나면 확인한다`() {
            val s = UpdateRetry.onFailure(RetryState(), UpdateFailure.NETWORK, null, t0, noJitter)
            assertEquals(1, s.networkFailures)
            assertFalse(UpdateRetry.canCheck(s, t0 + net.initialMs - 1))
            assertTrue(UpdateRetry.canCheck(s, t0 + net.initialMs))
        }

        @Test fun `연속 실패면 간격이 늘고 확인에 성공하면 처음으로 돌아간다`() {
            var s = RetryState()
            repeat(3) { s = UpdateRetry.onFailure(s, UpdateFailure.NETWORK, null, t0, noJitter) }
            assertEquals(3, s.networkFailures)
            assertEquals(t0 + net.delayMs(3, noJitter), s.networkRetryAtMs)
            s = UpdateRetry.onCheckSucceeded(s)
            assertEquals(0, s.networkFailures)
            assertTrue(UpdateRetry.canCheck(s, t0))
        }

        @Test fun `통신 실패는 검증 실패 기록을 건드리지 않는다`() {
            val bad = UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 7, t0, noJitter)
            val s = UpdateRetry.onCheckSucceeded(UpdateRetry.onFailure(bad, UpdateFailure.NETWORK, 7, t0, noJitter))
            assertEquals(bad.integrityRetryAtMs, s.integrityRetryAtMs)
            assertFalse(UpdateRetry.canDownload(s, 7, t0 + 1))
        }
    }

    @Nested
    inner class 검증_실패 {
        @Test fun `같은 릴리스는 간격 동안 다시 받지 않는다`() {
            val s = UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 7, t0, noJitter)
            assertFalse(UpdateRetry.canDownload(s, 7, t0 + integ.initialMs - 1))
            assertTrue(UpdateRetry.canDownload(s, 7, t0 + integ.initialMs))
        }

        @Test fun `새 릴리스가 보이면 기다리지 않고 실패 수도 1 부터 센다`() {
            var s = RetryState()
            repeat(4) { s = UpdateRetry.onFailure(s, UpdateFailure.INTEGRITY, 7, t0, noJitter) }
            assertEquals(4, s.integrityFailures)
            assertTrue(UpdateRetry.canDownload(s, 8, t0))
            s = UpdateRetry.onFailure(s, UpdateFailure.INTEGRITY, 8, t0, noJitter)
            assertEquals(1, s.integrityFailures)
            assertEquals(8, s.integrityRelease)
        }

        @Test fun `다 받아 적용하면 모든 기록이 지워진다`() {
            var s = UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 7, t0, noJitter)
            s = UpdateRetry.onFailure(s, UpdateFailure.NETWORK, 7, t0, noJitter)
            assertEquals(RetryState(), UpdateRetry.onApplied())
        }

        @Test fun `실패 수가 끝까지 가도 넘치지 않는다`() {
            val s = RetryState(integrityFailures = Int.MAX_VALUE, integrityRelease = 7)
            val next = UpdateRetry.onFailure(s, UpdateFailure.INTEGRITY, 7, t0, noJitter)
            assertEquals(Int.MAX_VALUE, next.integrityFailures)
            assertEquals(t0 + integ.maxMs, next.integrityRetryAtMs)
        }

        @Test fun `시각이 끝에 가까워도 예약 시각이 넘치지 않는다`() {
            val s = UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 7, Long.MAX_VALUE - 5, noJitter)
            assertEquals(Long.MAX_VALUE, s.integrityRetryAtMs)
        }
    }

    @Nested
    inner class 깨진_manifest {
        private fun badManifest(s: RetryState = RetryState(), at: Long = t0) =
            UpdateRetry.onFailure(s, UpdateRetry.kindOf(ContentRejection.BAD_MANIFEST)!!, null, at, noJitter)

        @Test fun `통신 실패 뒤 깨진 manifest 를 받으면 통신 실패 기록은 지워진다`() {
            var s = RetryState()
            repeat(3) { s = UpdateRetry.onFailure(s, UpdateFailure.NETWORK, null, t0, noJitter) }
            s = badManifest(s)
            assertEquals(0, s.networkFailures)
            assertEquals(0, s.networkRetryAtMs)
            assertEquals(1, s.manifestFailures)
        }

        @Test fun `검증 간격 동안 manifest 를 다시 확인하지 않는다`() {
            val s = badManifest()
            assertFalse(UpdateRetry.canCheck(s, t0 + 1_000))
            assertFalse(UpdateRetry.canCheck(s, t0 + integ.initialMs - 1))
            assertTrue(UpdateRetry.canCheck(s, t0 + integ.initialMs))
        }

        @Test fun `연속으로 깨져 있으면 간격이 늘어난다`() {
            var s = RetryState()
            repeat(3) { s = badManifest(s) }
            assertEquals(3, s.manifestFailures)
            assertEquals(t0 + integ.delayMs(3, noJitter), s.manifestRetryAtMs)
        }

        @Test fun `정상 manifest 를 받으면 기록이 지워진다`() {
            val s = UpdateRetry.onCheckSucceeded(badManifest())
            assertEquals(RetryState(), s)
            assertTrue(UpdateRetry.canCheck(s, t0 + 1))
        }

        @Test fun `특정 릴리스의 검증 실패는 manifest 확인을 막지 않는다`() {
            val s = UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 7, t0, noJitter)
            assertTrue(UpdateRetry.canCheck(s, t0 + 1))
            assertEquals(s, UpdateRetry.onCheckSucceeded(s))
        }

        @Test fun `시계가 뒤로 가서 예약이 너무 멀어지면 지금 해도 된다`() {
            val s = badManifest()
            assertTrue(UpdateRetry.canCheck(s, t0 - 365L * 24 * 60 * 60 * 1000))
        }

        @Test fun `깨진 manifest 가 끼어도 릴리스 재시도 간격은 유지된다`() {
            var s = RetryState()
            repeat(5) { s = UpdateRetry.onFailure(s, UpdateFailure.INTEGRITY, 7, t0, noJitter) }
            val releaseRetryAt = s.integrityRetryAtMs
            val hourLater = t0 + 60L * 60 * 1000
            assertTrue(hourLater < releaseRetryAt)
            s = badManifest(s, hourLater)
            assertEquals(7, s.integrityRelease)
            assertEquals(5, s.integrityFailures)
            s = UpdateRetry.onCheckSucceeded(s)
            assertEquals(releaseRetryAt, s.integrityRetryAtMs)
            assertFalse(UpdateRetry.canDownload(s, 7, hourLater + 1))
            assertTrue(UpdateRetry.canDownload(s, 8, hourLater + 1))
        }

        @Test fun `받다 끊겨 깨진 manifest 는 통신 실패로 본다`() {
            assertEquals(UpdateFailure.NETWORK, UpdateRetry.kindOfBadManifest(100, 200))
            assertEquals(UpdateFailure.INTEGRITY, UpdateRetry.kindOfBadManifest(200, 200))
            assertEquals(UpdateFailure.INTEGRITY, UpdateRetry.kindOfBadManifest(300, 200))
            assertEquals(UpdateFailure.INTEGRITY, UpdateRetry.kindOfBadManifest(100, null))
            // 통신 실패로 기록하면 manifest 기록은 늘지 않고 짧은 간격만 기다린다
            val s = UpdateRetry.onFailure(RetryState(), UpdateRetry.kindOfBadManifest(100, 200), null, t0, noJitter)
            assertEquals(0, s.manifestFailures)
            assertTrue(UpdateRetry.canCheck(s, t0 + net.initialMs))
        }

        @Test fun `새 칸이 없는 예전 저장 JSON 도 읽힌다`() {
            val old = """{"networkFailures":1,"networkRetryAtMs":5,"integrityFailures":2,"integrityRelease":7,"integrityRetryAtMs":9}"""
            val s = Json.decodeFromString(RetryState.serializer(), old)
            assertEquals(0, s.manifestFailures)
            assertEquals(0, s.manifestRetryAtMs)
            assertEquals(7, s.integrityRelease)
        }
    }

    @Nested
    inner class 시계와_저장 {
        @Test fun `시계가 뒤로 가서 예약이 너무 멀어지면 지금 해도 된다`() {
            val s = UpdateRetry.onFailure(RetryState(), UpdateFailure.NETWORK, null, t0, noJitter)
            val wayBack = t0 - 365L * 24 * 60 * 60 * 1000
            assertTrue(UpdateRetry.canCheck(s, wayBack))
            // 정상 범위의 남은 시간이면 기다린다
            assertFalse(UpdateRetry.canCheck(s, t0))
        }

        @Test fun `상태는 JSON 으로 저장하고 다시 읽을 수 있다`() {
            val s = UpdateRetry.onFailure(
                UpdateRetry.onFailure(RetryState(), UpdateFailure.INTEGRITY, 9, t0, 0.1), UpdateFailure.NETWORK, 9, t0, 0.9,
            )
            val text = Json.encodeToString(RetryState.serializer(), s)
            assertEquals(s, Json.decodeFromString(RetryState.serializer(), text))
        }
    }

    @Nested
    inner class 분류와_타임아웃 {
        @Test fun `검증 거절 사유를 실패 종류로 나눈다`() {
            for (r in ContentRejection.entries) {
                val kind = UpdateRetry.kindOf(r)
                when (r) {
                    ContentRejection.NOT_NEWER, ContentRejection.APP_TOO_OLD -> assertNull(kind, r.name)
                    else -> assertEquals(UpdateFailure.INTEGRITY, kind, r.name)
                }
            }
        }

        @Test fun `종류별 규칙을 고른다`() {
            assertEquals(net, UpdateTiming.policy(UpdateFailure.NETWORK))
            assertEquals(integ, UpdateTiming.policy(UpdateFailure.INTEGRITY))
        }

        @Test fun `파일 제한 시간은 크기에 따라 늘고 넘치지 않는다`() {
            assertEquals(UpdateTiming.FILE_DEADLINE_BASE_MS, UpdateTiming.fileDeadlineMs(0))
            assertEquals(UpdateTiming.FILE_DEADLINE_BASE_MS, UpdateTiming.fileDeadlineMs(-1))
            val oneSecondWorth = UpdateTiming.MIN_THROUGHPUT_BYTES_PER_S
            assertEquals(UpdateTiming.FILE_DEADLINE_BASE_MS + 1_000, UpdateTiming.fileDeadlineMs(oneSecondWorth))
            assertEquals(UpdateTiming.FILE_DEADLINE_BASE_MS + 1_500, UpdateTiming.fileDeadlineMs(oneSecondWorth * 3 / 2))
            assertTrue(UpdateTiming.fileDeadlineMs(Long.MAX_VALUE) > UpdateTiming.fileDeadlineMs(1_000_000))
        }

        @Test fun `타임아웃 값의 크기 관계`() {
            assertTrue(UpdateTiming.CONNECT_TIMEOUT_MS < UpdateTiming.MANIFEST_DEADLINE_MS)
            assertTrue(UpdateTiming.READ_TIMEOUT_MS <= UpdateTiming.FILE_DEADLINE_BASE_MS)
        }
    }
}

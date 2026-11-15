package core.content

import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.pow

/**
 * 원격 콘텐츠 업데이트(#31)의 재시도 간격(지수 백오프)과 타임아웃.
 * 여기서는 "언제 다시 해도 되는가"와 "얼마나 기다리면 끊는가"만 계산한다. 실제 예약·통신·저장은 :app 이 한다.
 *
 * :app 이 지킬 것
 * - [RetryState] 는 앱을 다시 켜도 남도록 저장한다(JSON 으로 저장할 수 있다). 지우면 간격이 처음부터 다시 시작된다.
 * - 다운로드가 중간에 끊겨 크기가 모자라면 [UpdateFailure.NETWORK] 로 본다. 크기는 다 왔는데 해시가 다를 때만 [UpdateFailure.INTEGRITY].
 *   차는 시동을 끄면 전원이 끊겨 받다 만 파일이 생기기 쉽다. 이걸 무결성 실패로 보면 정상 차가 오래 기다리게 된다.
 * - manifest 를 받으면 읽어 본 뒤 하나만 부른다. 읽었으면 [UpdateRetry.onCheckSucceeded], 깨져 있으면(BAD_MANIFEST) [UpdateRetry.onFailure] 에 release=null.
 *   깨져 있을 때 종류는 [UpdateRetry.kindOfBadManifest] 로 정한다. 받다 끊겨 깨진 것은 NETWORK 다.
 * - 실패하는 동안에는 내장 콘텐츠나 직전에 검증된 콘텐츠를 그대로 쓴다.
 * - 연결 [UpdateTiming.CONNECT_TIMEOUT_MS], 바이트 사이 대기 [UpdateTiming.READ_TIMEOUT_MS], 요청 전체 [UpdateTiming.MANIFEST_DEADLINE_MS]·[UpdateTiming.fileDeadlineMs] 를 넘으면 끊고 NETWORK 실패로 넘긴다.
 */

/** 업데이트 실패의 종류. 종류마다 다시 시도할 간격이 다르다 */
enum class UpdateFailure {
    /** 통신 실패, 타임아웃, 받다 끊김. 잠시 뒤 다시 하면 될 가능성이 크다 */
    NETWORK,
    /** 다 받았지만 검증 실패(해시·형식·값·버전 불일치). 같은 릴리스를 다시 받아도 고쳐지지 않을 가능성이 크다 */
    INTEGRITY,
}

/**
 * 지수 백오프 규칙. n 번째 연속 실패 뒤 기다리는 시간 = min(initialMs × multiplier^(n−1), maxMs) ± jitterRatio.
 * 지터(무작위 폭)는 서버 장애가 풀린 순간 모든 차가 한꺼번에 몰리지 않게 흩뜨린다.
 */
data class BackoffPolicy(
    val initialMs: Long,
    val multiplier: Double,
    val maxMs: Long,
    /** 0 이상 1 이하. 0.2 면 ±20% */
    val jitterRatio: Double,
) {
    init {
        require(initialMs > 0) { "initialMs > 0" }
        require(multiplier >= 1.0) { "multiplier >= 1" }
        require(maxMs >= initialMs) { "maxMs >= initialMs" }
        require(jitterRatio in 0.0..1.0) { "jitterRatio 0~1" }
    }

    /** [failures] 번째 연속 실패 뒤 기다릴 시간(ms). [random] 은 0 이상 1 이하(범위 밖이면 잘라 쓴다) */
    fun delayMs(failures: Int, random: Double): Long {
        val n = failures.coerceAtLeast(1)
        // n 이 매우 크면 pow 가 무한대가 되지만 min 으로 maxMs 에서 멈춘다
        val base = min(initialMs * multiplier.pow(n - 1), maxMs.toDouble())
        val r = if (random.isNaN()) HALF else random.coerceIn(0.0, 1.0)
        val jitter = base * jitterRatio * (2 * r - 1)
        return (base + jitter).toLong().coerceAtLeast(0)
    }

    /** 지터까지 더한 가장 긴 대기 */
    val longestMs: Long get() = (maxMs * (1 + jitterRatio)).toLong()

    private companion object {
        /** random 이 숫자가 아니면 지터 없이(가운데 값) */
        const val HALF = 0.5
    }
}

/** 재시도 상태. :app 이 저장해 두었다가 다음 확인 때 넘긴다 */
@Serializable
data class RetryState(
    /** manifest 확인·다운로드의 연속 통신 실패 수 */
    val networkFailures: Int = 0,
    val networkRetryAtMs: Long = 0,
    /** 같은 릴리스의 연속 검증 실패 수 */
    val integrityFailures: Int = 0,
    /** 검증에 실패한 릴리스(지금은 contentVersion). 새 릴리스가 보이면 기다리지 않고 받는다 */
    val integrityRelease: Int? = null,
    val integrityRetryAtMs: Long = 0,
    /** manifest 가 깨져 온(BAD_MANIFEST) 연속 횟수. 릴리스 기록과 칸을 나눠 서로 덮어쓰지 않는다 */
    val manifestFailures: Int = 0,
    val manifestRetryAtMs: Long = 0,
)

/** 업데이트 재시도 판단. 순수 함수. 시각은 :app 이 넘긴다(wall-clock, epoch ms) */
object UpdateRetry {
    /**
     * manifest 를 확인해도 되는지. 통신 실패 뒤에는 통신 간격을, manifest 가 깨져 온 뒤([ContentRejection.BAD_MANIFEST])에는 manifest 간격을 지킨다.
     * 깨진 manifest 는 다시 받아도 그대로일 가능성이 커서, 간격 없이 반복해 받지 않게 한다. 릴리스 기록은 보지 않는다(그건 [canDownload]).
     */
    fun canCheck(s: RetryState, nowMs: Long): Boolean =
        ready(s.networkRetryAtMs, nowMs, UpdateTiming.NETWORK_BACKOFF) &&
            ready(s.manifestRetryAtMs, nowMs, UpdateTiming.INTEGRITY_BACKOFF)

    /**
     * 이 릴리스의 파일을 받아도 되는지. 같은 릴리스가 검증에 실패했으면 간격을 지킨다.
     * manifest 에 새 릴리스가 보이면(고쳐 올린 경우) 기다리지 않는다.
     */
    fun canDownload(s: RetryState, release: Int, nowMs: Long): Boolean =
        s.integrityRelease != release || ready(s.integrityRetryAtMs, nowMs, UpdateTiming.INTEGRITY_BACKOFF)

    /**
     * 실패를 기록한 새 상태. [random] 은 0 이상 1 이하 (예: kotlin.random.Random.nextDouble()).
     * INTEGRITY 에서 [release] 가 null 이면 manifest 가 깨져 릴리스를 모르는 경우다. manifest 기록에 세고, 릴리스 기록은 건드리지 않는다.
     * NETWORK 에서는 [release] 를 쓰지 않는다.
     * 검증 실패는 끝까지 받았다는 뜻이라 통신 실패 기록을 함께 지운다. 그래서 깨진 manifest 에는 [onCheckSucceeded] 를 부르지 않고 이것만 부른다.
     */
    fun onFailure(s: RetryState, kind: UpdateFailure, release: Int?, nowMs: Long, random: Double): RetryState = when (kind) {
        UpdateFailure.NETWORK -> {
            val n = inc(s.networkFailures)
            s.copy(networkFailures = n, networkRetryAtMs = later(nowMs, UpdateTiming.NETWORK_BACKOFF.delayMs(n, random)))
        }
        UpdateFailure.INTEGRITY -> if (release == null) {
            val n = inc(s.manifestFailures)
            clearNetwork(s).copy(manifestFailures = n, manifestRetryAtMs = later(nowMs, UpdateTiming.INTEGRITY_BACKOFF.delayMs(n, random)))
        } else {
            val n = if (release == s.integrityRelease) inc(s.integrityFailures) else 1
            clearNetwork(s).copy(
                integrityFailures = n, integrityRelease = release,
                integrityRetryAtMs = later(nowMs, UpdateTiming.INTEGRITY_BACKOFF.delayMs(n, random)),
            )
        }
    }

    /**
     * manifest 를 받아 읽는 데 성공(BAD_MANIFEST 아님). 통신 실패 기록과 manifest 기록을 지운다.
     * 받기만 하고 읽기 전에 부르지 않는다. 깨진 manifest 기록이 매번 지워져 간격이 늘지 않는다.
     * 릴리스의 검증 실패 기록은 남긴다. 그 릴리스는 [canDownload] 가 간격을 지킨다.
     */
    fun onCheckSucceeded(s: RetryState): RetryState = clearNetwork(s).copy(manifestFailures = 0, manifestRetryAtMs = 0)

    /** 릴리스를 다 받아 검증까지 통과. 모든 실패 기록을 지운다 */
    fun onApplied(): RetryState = RetryState()

    /** 검증 거절 사유 → 실패 종류. 실패가 아닌 거절(새것 아님, 앱이 오래됨)은 null */
    fun kindOf(r: ContentRejection): UpdateFailure? = when (r) {
        ContentRejection.BAD_MANIFEST, ContentRejection.HASH_MISMATCH, ContentRejection.PARSE_ERROR,
        ContentRejection.VERSION_MISMATCH, ContentRejection.INVALID_CONTENT -> UpdateFailure.INTEGRITY
        ContentRejection.NOT_NEWER, ContentRejection.APP_TOO_OLD -> null
    }

    /**
     * manifest 가 깨져 왔을 때의 실패 종류. 서버가 알린 크기([expectedBytes], HTTP Content-Length)보다 덜 받았으면
     * 받다 끊긴 것이라 NETWORK. 크기를 모르거나(null) 다 받았는데 깨져 있으면 INTEGRITY.
     * 시동이 꺼져 받다 끊긴 정상 차가 검증 간격(최소 10분)을 기다리지 않게 한다.
     */
    fun kindOfBadManifest(receivedBytes: Long, expectedBytes: Long?): UpdateFailure =
        if (expectedBytes != null && receivedBytes < expectedBytes) UpdateFailure.NETWORK else UpdateFailure.INTEGRITY

    /**
     * 예약 시각이 지났는지. 시계가 뒤로 가면(배터리 분리, 시간대·시각 변경) 예약 시각이 아주 먼 미래가 될 수 있다.
     * 남은 시간이 가장 긴 대기보다 길면 시계가 바뀐 것으로 보고 지금 해도 된다고 본다.
     */
    private fun ready(retryAtMs: Long, nowMs: Long, p: BackoffPolicy): Boolean =
        nowMs >= retryAtMs || retryAtMs - nowMs > p.longestMs

    /** 통신이 된 것이 확인됐을 때 통신 실패 기록을 지운다 */
    private fun clearNetwork(s: RetryState) = s.copy(networkFailures = 0, networkRetryAtMs = 0)

    private fun inc(n: Int) =if (n == Int.MAX_VALUE) n else n + 1
    private fun later(nowMs: Long, delayMs: Long) = if (nowMs > Long.MAX_VALUE - delayMs) Long.MAX_VALUE else nowMs + delayMs
}

/** 재시도 간격과 타임아웃 값. 전부 [임시] (#31). 실차·운영 데이터를 보고 정한다 */
object UpdateTiming {
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60 * SECOND_MS
    private const val HOUR_MS = 60 * MINUTE_MS
    /** 실패마다 간격을 두 배로 */
    private const val DOUBLE = 2.0
    /** 간격의 ±20% 를 무작위로 */
    private const val JITTER = 0.2

    /** [임시] 통신 실패: 1분부터 두 배씩, 최대 1시간. 터널·지하 주차장처럼 금방 풀리는 경우가 많다 */
    val NETWORK_BACKOFF = BackoffPolicy(MINUTE_MS, DOUBLE, HOUR_MS, JITTER)
    /** [임시] 검증 실패: 10분부터 두 배씩, 최대 24시간. 서버 파일이 고쳐질 때까지 같은 파일을 반복해 받지 않는다 */
    val INTEGRITY_BACKOFF = BackoffPolicy(10 * MINUTE_MS, DOUBLE, 24 * HOUR_MS, JITTER)

    fun policy(kind: UpdateFailure): BackoffPolicy = when (kind) {
        UpdateFailure.NETWORK -> NETWORK_BACKOFF
        UpdateFailure.INTEGRITY -> INTEGRITY_BACKOFF
    }

    /** [임시] 연결을 맺기까지 */
    const val CONNECT_TIMEOUT_MS = 10 * SECOND_MS
    /** [임시] 받는 중 다음 바이트가 오기까지 */
    const val READ_TIMEOUT_MS = 15 * SECOND_MS
    /** [임시] manifest 요청 전체 */
    const val MANIFEST_DEADLINE_MS = 20 * SECOND_MS
    /** [임시] 파일 요청 전체의 기본 시간 */
    const val FILE_DEADLINE_BASE_MS = 20 * SECOND_MS
    /** [임시] 이보다 느린 망은 끊는다(초당 바이트). 차량 LTE 약전계 하한 가정 */
    const val MIN_THROUGHPUT_BYTES_PER_S = 20_000L

    /** 파일 하나를 받는 전체 제한 시간. 기본 시간 + 크기 ÷ 최저 속도. 크기는 manifest 값을 쓴다 */
    fun fileDeadlineMs(sizeBytes: Long): Long {
        val size = sizeBytes.coerceAtLeast(0)
        val transfer = size / MIN_THROUGHPUT_BYTES_PER_S * SECOND_MS + size % MIN_THROUGHPUT_BYTES_PER_S * SECOND_MS / MIN_THROUGHPUT_BYTES_PER_S
        return if (transfer > Long.MAX_VALUE - FILE_DEADLINE_BASE_MS) Long.MAX_VALUE else FILE_DEADLINE_BASE_MS + transfer
    }
}

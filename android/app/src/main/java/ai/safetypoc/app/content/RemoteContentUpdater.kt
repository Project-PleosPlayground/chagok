package ai.safetypoc.app.content

import core.content.ContentCodec
import core.content.ContentRejection
import core.content.ContentState
import core.content.ContentUpdate
import core.content.ContentVerdict
import core.content.UpdateFailure
import core.content.UpdateRetry
import core.content.UpdateTiming
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** 받은 바이트와 서버가 알린 크기(Content-Length, 모르면 null). 덜 받은 것을 통신 실패로 구분하는 데 쓴다 */
class Fetched(val bytes: ByteArray, val expectedBytes: Long?)

/** GET 하나. 실패·타임아웃이면 null. [deadlineMs] 는 요청 전체 제한 시간 */
fun interface Fetcher {
    fun get(url: String, deadlineMs: Long): Fetched?
}

/**
 * 원격 콘텐츠 확인·다운로드 (#31). 판단은 전부 core `ContentUpdate`·`UpdateRetry` 가 하고, 여기서는 순서대로 부르고 파일에 쓴다.
 * 한 번에 하나만 돈다(동기화). 비주행 상태에서 [check] 를 부른다. 받은 묶음은 [ContentState.stage] 에 올리고, 적용은 탭 전환 때 `applyStaged`.
 *
 * 서버(Supabase Storage 공개 버킷 `pleospet-content`, 주소는 BuildConfig.CONTENT_BASE_URL) 파일 배치
 * - `<base>/manifest/<appVersionCode>.json` — 이 앱 버전이 볼 manifest. 심사 중인 버전의 파일은 바꾸지 않는다(제출본 고정)
 * - `<base>/<manifest.path>` — 묶음 파일. 예: `packs/content-v2.json`
 * 만드는 도구: `android/tools/content_release.py`
 */
class RemoteContentUpdater(
    private val state: ContentState,
    private val baseUrl: String,
    private val appVersion: Int,
    private val files: ContentFiles,
    private val fetch: Fetcher,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: () -> Double = { Random.nextDouble() },
    private val log: (String) -> Unit = {},
) {
    sealed interface Outcome {
        /** 재시도 간격 안이라 이번엔 건너뜀 */
        data object Waiting : Outcome
        /** 새것이 없거나 이 앱으로는 못 쓰는 묶음 */
        data class NoUpdate(val reason: ContentRejection) : Outcome
        /** 받아서 검증 통과, 적용 대기 */
        data class Staged(val contentVersion: Int) : Outcome
        /** 통신 실패 또는 검증 실패. 다음 시도는 간격 뒤 */
        data class Failed(val kind: UpdateFailure, val reason: ContentRejection? = null) : Outcome
    }

    private val lock = Any()
    private val base = baseUrl.trim().trimEnd('/')

    val enabled: Boolean get() = base.isNotEmpty()
    fun manifestUrl() = "$base/manifest/$appVersion.json"
    fun fileUrl(path: String) = "$base/$path"

    /** manifest 확인 → 필요하면 묶음 다운로드·검증 → 파일 저장 → stage. 통신·파일 IO 가 있으니 메인 스레드에서 부르지 않는다 */
    fun check(): Outcome = synchronized(lock) {
        if (!enabled) return Outcome.NoUpdate(ContentRejection.BAD_MANIFEST)
        var retry = files.readRetry()
        val t = now()
        if (!UpdateRetry.canCheck(retry, t)) return Outcome.Waiting

        val m = fetch.get(manifestUrl(), UpdateTiming.MANIFEST_DEADLINE_MS)
            ?: return fail(retry, UpdateFailure.NETWORK, null, t, "manifest 받기 실패")
        val manifest = ContentUpdate.readManifest(ContentCodec.utf8OrNull(m.bytes))
        if (manifest == null) {
            val kind = UpdateRetry.kindOfBadManifest(m.bytes.size.toLong(), m.expectedBytes)
            return fail(retry, kind, null, t, "manifest 깨짐 (${m.bytes.size}/${m.expectedBytes ?: "?"} bytes)")
        }
        retry = UpdateRetry.onCheckSucceeded(retry); files.writeRetry(retry)

        ContentUpdate.shouldDownload(manifest, state.newestVersion, appVersion)?.let { return Outcome.NoUpdate(it) }
        if (!UpdateRetry.canDownload(retry, manifest.contentVersion, t)) return Outcome.Waiting

        val f = fetch.get(fileUrl(manifest.path), UpdateTiming.fileDeadlineMs(ASSUMED_PACK_BYTES))
            ?: return fail(retry, UpdateFailure.NETWORK, null, t, "묶음 받기 실패 v${manifest.contentVersion}")
        if (f.expectedBytes != null && f.bytes.size < f.expectedBytes)
            return fail(retry, UpdateFailure.NETWORK, null, t, "묶음 덜 받음 (${f.bytes.size}/${f.expectedBytes})")

        return when (val v = ContentUpdate.verify(f.bytes, manifest, state.newestVersion, appVersion)) {
            is ContentVerdict.Accept -> {
                if (!files.writePack(f.bytes)) log("묶음 v${v.pack.contentVersion} 저장 실패 (저장 공간?). 이번 실행에만 적용")
                state.stage(v.pack)
                files.writeRetry(UpdateRetry.onApplied())
                log("콘텐츠 v${v.pack.contentVersion} 받음, 적용 대기")
                Outcome.Staged(v.pack.contentVersion)
            }
            is ContentVerdict.Reject -> {
                val kind = UpdateRetry.kindOf(v.reason) ?: return Outcome.NoUpdate(v.reason)
                fail(retry, kind, manifest.contentVersion, t, "묶음 v${manifest.contentVersion} 거절 ${v.reason} ${v.detail.take(3)}", v.reason)
            }
        }
    }

    private fun fail(retry: core.content.RetryState, kind: UpdateFailure, release: Int?, t: Long, why: String, reason: ContentRejection? = null): Outcome {
        files.writeRetry(UpdateRetry.onFailure(retry, kind, release, t, random()))
        log("콘텐츠 업데이트 실패($kind): $why")
        return Outcome.Failed(kind, reason)
    }

    companion object {
        /** [임시] 묶음 파일 크기 가정(제한 시간 계산용). manifest 에 크기가 없어서 둔다. 지금 내장 묶음은 약 10 KB */
        const val ASSUMED_PACK_BYTES = 512_000L
    }
}

/**
 * HttpURLConnection 으로 받는다. 연결·읽기 타임아웃은 [UpdateTiming], 전체 제한 시간은 읽는 중에 재서 넘으면 끊고 null.
 * 리다이렉트는 따르지 않는다(공개 버킷 직접 주소만). 응답 크기 상한 [MAX_BYTES].
 */
class HttpFetcher(private val now: () -> Long = System::currentTimeMillis) : Fetcher {
    override fun get(url: String, deadlineMs: Long): Fetched? = runCatching {
        val start = now()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = UpdateTiming.CONNECT_TIMEOUT_MS.toInt()
            c.readTimeout = UpdateTiming.READ_TIMEOUT_MS.toInt()
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            val expected = c.contentLengthLong.takeIf { it >= 0 }
            if (expected != null && expected > MAX_BYTES) return null
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            c.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return null
                    if (now() - start > deadlineMs) return Fetched(out.toByteArray(), expected ?: Long.MAX_VALUE)   // 덜 받은 채로 돌려 NETWORK 로 분류되게
                }
            }
            Fetched(out.toByteArray(), expected)
        } finally { c.disconnect() }
    }.getOrNull()

    companion object { const val MAX_BYTES = 5L * 1024 * 1024 }
}

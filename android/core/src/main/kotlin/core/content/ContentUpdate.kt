package core.content

import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * 서버(Supabase Storage)에 두는 목록 파일. 앱은 이것부터 받아 새 묶음이 있는지 본다.
 * 제출본 고정(#31): 서버가 앱 버전별로 manifest 를 따로 두고, 심사 중인 버전의 manifest 는 바꾸지 않는다.
 * 되돌리기: 버전을 낮추면 앱이 받지 않는다. 고친 내용을 더 높은 버전으로 다시 배포한다.
 */
@Serializable
data class ContentManifest(
    val contentVersion: Int,
    val minAppVersion: Int,
    /** 묶음 파일 위치. 서버 기준 상대 경로 */
    val path: String,
    /** 묶음 파일의 SHA-256 (16진수 64자) */
    val sha256: String,
)

/** 새 묶음을 받지 않거나 버리는 이유 */
enum class ContentRejection {
    /** manifest 가 없거나 읽을 수 없거나 값이 맞지 않는다 */
    BAD_MANIFEST,
    /** 지금 쓰는 것보다 새것이 아니다 */
    NOT_NEWER,
    /** 이 앱 버전으로는 읽을 수 없는 묶음이다 (스토어 업데이트 필요) */
    APP_TOO_OLD,
    /** 받은 파일이 manifest 의 해시와 다르다 (손상, 덜 받음) */
    HASH_MISMATCH,
    /** JSON 형식이 맞지 않는다. 앱이 모르는 enum 값이 들어 있어도 여기 해당한다 */
    PARSE_ERROR,
    /** 파일 안의 버전이 manifest 와 다르다 */
    VERSION_MISMATCH,
    /** 형식은 맞지만 값이 규칙에 어긋난다 ([ContentValidator]) */
    INVALID_CONTENT,
}

sealed interface ContentVerdict {
    data class Accept(val pack: ContentPack) : ContentVerdict
    /** detail 은 로그용. INVALID_CONTENT 일 때 어긋난 항목 목록 */
    data class Reject(val reason: ContentRejection, val detail: List<String> = emptyList()) : ContentVerdict
}

/**
 * 원격 콘텐츠 업데이트의 판단 로직. 통신·저장·주행 상태 확인은 :app 이 하고, 여기서는 받을지·쓸지만 정한다.
 * 모든 함수는 상태가 없고 예외를 던지지 않는다. 실패는 거절(null 또는 Reject)로 돌려준다.
 *
 * :app 흐름
 * 1. 비주행 상태에서 manifest 를 받는다 → [readManifest] → [shouldDownload] (activeVersion 은 [ContentState.newestVersion])
 * 2. 받을 만하면 묶음 파일을 받는다 → [verify]. Accept 면 파일을 임시 파일에 쓴 뒤 이름을 바꿔 저장하고 [ContentState.stage]
 * 3. 주행 중이 아닐 때 top-level destination 이 바뀌면(탭 전환, 주행 종료 화면, 앱 시작) [ContentState.applyStaged]. 하위 destination 진입, 같은 destination 복귀(onResume), 다시 그리기(창 크기 변경)에는 부르지 않는다
 *    주행 시작: 자동 시작(출발해 속도가 붙음)은 destination 이 바뀌는 순간 이미 주행 중이라 적용하지 않는다. 수동 시작 버튼(#34)은 정차 중에
 *    주행 화면으로 바뀌므로 적용된다. 출발 직전이라 화면이 바뀌어도 어색하지 않아 그대로 둔다. 엔진 수치는 이와 별개로 세션 시작 때 고정된다(#33)
 * 4. 앱을 다시 켤 때는 저장해 둔 파일과 내장 콘텐츠 중에서 [pickAtStartupFromBytes]
 * 5. MissionEngine·SessionEndPipeline·Wallet 에는 { state.active } 를 넘긴다 (기본값은 내장 콘텐츠)
 *
 * 운영 규칙 (코드로 막지 않는 것)
 * - 아이템은 묶음에서 빼지 않는다. 가진 아이템·스테이지 배치가 아이템 번호를 가리킨다.
 * - 아이템 id 의 칸(category)은 바꾸지 않는다. 바꾸려면 새 id 로 만든다.
 * - contentVersion 은 1씩 올린다. 크게 건너뛰면 그보다 낮은 번호는 다시 쓸 수 없다.
 *
 * :app 이 지킬 것 (여기서 막을 수 없는 것)
 * - 다운로드는 한 번에 하나만. 같은 캐시 파일에 동시에 쓰지 않는다.
 * - 캐시 파일은 임시 파일에 다 쓴 뒤 이름 바꾸기로 교체한다. 저장 공간 부족·앱 강제 종료로 덜 써진 파일이 이전 파일을 덮지 않게.
 * - 통신 실패·타임아웃은 이번 확인을 건너뛰고 지금 콘텐츠를 계속 쓴다. 다시 시도는 [UpdateRetry] 의 간격(지수 백오프)을 지키고, 타임아웃 값은 [UpdateTiming].
 */
object ContentUpdate {
    const val HASH_ALGORITHM = "SHA-256"
    /** SHA-256 16진수 문자열 길이 */
    private const val SHA256_HEX_LENGTH = 64

    /** manifest 텍스트를 읽는다. 없거나, 깨졌거나, 값이 맞지 않으면 null */
    fun readManifest(text: String?): ContentManifest? {
        val m = text?.let { runCatching { ContentCodec.decodeManifest(it) }.getOrNull() } ?: return null
        val hashOk = m.sha256.length == SHA256_HEX_LENGTH && m.sha256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return if (hashOk && isSafeRelativePath(m.path)) m else null
    }

    /**
     * 서버 안 상대 경로만 받는다. 영문·숫자·`.` `_` `-` `/` 만 허용하고, 빈 칸·`.`·`..` 경로 조각은 거절한다.
     * 그래서 절대 경로, 다른 주소(`http:`), 퍼센트 인코딩(`%2e%2e`), 역슬래시가 모두 걸러진다.
     */
    private fun isSafeRelativePath(path: String): Boolean =
        SAFE_PATH_CHARS.matches(path) && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
    private val SAFE_PATH_CHARS = Regex("[A-Za-z0-9._/-]+")

    fun shouldDownload(manifest: ContentManifest?, activeVersion: Int, appVersion: Int): ContentRejection? = when {
        manifest == null -> ContentRejection.BAD_MANIFEST
        manifest.contentVersion <= activeVersion -> ContentRejection.NOT_NEWER
        manifest.minAppVersion > appVersion -> ContentRejection.APP_TOO_OLD
        else -> null
    }

    fun verify(bytes: ByteArray?, manifest: ContentManifest?, activeVersion: Int, appVersion: Int): ContentVerdict {
        shouldDownload(manifest, activeVersion, appVersion)?.let { return ContentVerdict.Reject(it) }
        manifest!!
        if (bytes == null || !sha256Hex(bytes).equals(manifest.sha256, ignoreCase = true))
            return ContentVerdict.Reject(ContentRejection.HASH_MISMATCH)
        val pack = ContentCodec.utf8OrNull(bytes)?.let { text -> runCatching { ContentCodec.decode(text) }.getOrNull() }
            ?: return ContentVerdict.Reject(ContentRejection.PARSE_ERROR)
        if (pack.contentVersion != manifest.contentVersion || pack.minAppVersion != manifest.minAppVersion)
            return ContentVerdict.Reject(ContentRejection.VERSION_MISMATCH)
        val problems = ContentValidator.problems(pack)
        if (problems.isNotEmpty()) return ContentVerdict.Reject(ContentRejection.INVALID_CONTENT, problems)
        return ContentVerdict.Accept(pack)
    }

    /**
     * 앱 시작 때 쓸 묶음. 저장해 둔 묶음이 읽히고, 값 규칙을 지키고, 이 앱 버전에서 쓸 수 있고, 내장보다 새것이면 그것을 쓴다.
     * 스토어 업데이트로 내장 콘텐츠가 더 새것이 되거나 앱을 낮은 버전으로 다시 깔면 저장해 둔 것은 무시된다.
     */
    fun pickAtStartup(bundled: ContentPack, cachedText: String?, appVersion: Int): ContentPack {
        val cached = cachedText?.let { runCatching { ContentCodec.decode(it) }.getOrNull() } ?: return bundled
        val usable = cached.minAppVersion <= appVersion &&
            cached.contentVersion > bundled.contentVersion &&
            ContentValidator.problems(cached).isEmpty()
        return if (usable) cached else bundled
    }

    /** 저장해 둔 파일을 바이트로 읽은 경우. UTF-8 이 아니면(다른 인코딩, 손상) 내장을 쓴다 */
    fun pickAtStartupFromBytes(bundled: ContentPack, cachedBytes: ByteArray?, appVersion: Int): ContentPack =
        pickAtStartup(bundled, cachedBytes?.let { ContentCodec.utf8OrNull(it) }, appVersion)

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance(HASH_ALGORITHM).digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * 지금 쓰는 묶음과 적용을 기다리는 묶음. 받은 묶음은 바로 쓰지 않고, 주행 중이 아닐 때 top-level destination 이 바뀌는 순간 바꾼다.
 * 다운로드 스레드의 [stage]와 UI 스레드의 [applyStaged]가 겹쳐도 묶음을 잃지 않도록 동기화한다.
 * 받은 미션은 제공할 때 정한 목표·보상·이름을 MissionInstance 가 들고 있으므로 묶음이 바뀌어도 조건이 바뀌지 않는다.
 */
class ContentState(initial: ContentPack) {
    private val lock = Any()

    @Volatile var active: ContentPack = initial
        private set
    @Volatile var staged: ContentPack? = null
        private set

    /** 받아 둔 것 중 가장 새 버전. [ContentUpdate.shouldDownload] 의 activeVersion 에 넘긴다(적용 전 같은 묶음을 다시 받지 않게) */
    val newestVersion: Int get() = synchronized(lock) { staged?.contentVersion ?: active.contentVersion }

    /** 지금 쓰는 것과 기다리는 것보다 새것일 때만 올려 둔다. 올렸으면 true */
    fun stage(pack: ContentPack): Boolean = synchronized(lock) {
        val newest = maxOf(active.contentVersion, staged?.contentVersion ?: active.contentVersion)
        if (pack.contentVersion <= newest) return false
        staged = pack
        true
    }

    /** 기다리던 묶음을 적용한다. 바뀐 것이 있으면 새 묶음, 없으면 null. 여러 번 불러도 한 번만 적용된다 */
    fun applyStaged(): ContentPack? = synchronized(lock) {
        val next = staged ?: return null
        active = next
        staged = null
        next
    }
}

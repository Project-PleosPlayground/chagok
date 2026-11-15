package ai.safetypoc.app.content

import core.content.RetryState
import core.content.RetryStateCodec
import java.io.File

/**
 * 원격 콘텐츠의 기기 저장 (#31). 앱 전용 폴더(`filesDir/content/`) 안에 묶음 파일과 재시도 상태를 둔다.
 * 묶음 파일은 임시 파일에 다 쓴 뒤 이름 바꾸기로 교체한다. 저장 공간 부족·시동 OFF 로 덜 써진 파일이 이전 파일을 덮지 않게 (ContentUpdate 주석).
 */
class ContentFiles(private val dir: File) {
    private val pack = File(dir, PACK)
    private val tmp = File(dir, "$PACK.tmp")
    private val retry = File(dir, RETRY)

    /** 저장해 둔 묶음. 없으면 null. 읽기 실패도 null (내장 콘텐츠를 쓴다) */
    fun readPack(): ByteArray? = runCatching { pack.takeIf { it.isFile }?.readBytes() }.getOrNull()

    /** 검증을 통과한 묶음만 넣는다. 다 쓴 뒤 이름을 바꾼다. 실패하면 false 고 이전 파일은 그대로 */
    fun writePack(bytes: ByteArray): Boolean = runCatching {
        dir.mkdirs()
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(pack)) { pack.delete(); check(tmp.renameTo(pack)) { "rename" } }
        true
    }.getOrElse { tmp.delete(); false }

    fun readRetry(): RetryState = RetryStateCodec.decode(runCatching { retry.takeIf { it.isFile }?.readText() }.getOrNull())

    fun writeRetry(s: RetryState) { runCatching { dir.mkdirs(); retry.writeText(RetryStateCodec.encode(s)) } }

    companion object {
        const val DIR = "content"
        const val PACK = "pack.json"
        const val RETRY = "retry.json"
    }
}

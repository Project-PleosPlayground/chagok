package ai.safetypoc.app.data.store

import core.store.DocumentStore
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [DocumentStore] 의 기기 파일 구현. 앱 전용 폴더 아래 `<모음>/<키>.json` 에 문서 하나씩 둔다.
 * - 쓰기·지우기는 저장 스레드 하나에서 부른 순서대로 한다. 부른 스레드(메인)는 기다리지 않는다.
 * - 문서는 임시 파일에 다 쓴 뒤 이름 바꾸기로 교체한다. 덜 써진 파일이 이전 문서를 덮지 않게.
 * - 읽기는 그때까지 맡긴 쓰기를 끝낸 뒤 한다. 저장소는 앱을 켤 때 한 번만 읽는다.
 * 실패는 [log] 에 남기고 넘어간다. 메모리의 값은 그대로라 앱을 다시 켜기 전까지는 화면이 맞다.
 */
class FileDocumentStore(private val root: File, private val log: (String) -> Unit = {}) : DocumentStore {
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD).apply { isDaemon = true } }

    override fun readAll(collection: String): Map<String, String> {
        flush()
        val files = dir(collection).listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyMap()
        return files.mapNotNull { f ->
            runCatching { keyOf(f) to f.readText() }.onFailure { log("저장 문서 읽기 실패 $collection/${f.name}: $it") }.getOrNull()
        }.toMap()
    }

    override fun write(collection: String, key: String, document: String) = enqueue("쓰기 $collection/$key") {
        val dir = dir(collection).apply { mkdirs() }
        val target = File(dir, fileName(key))
        val tmp = File(dir, fileName(key) + TMP)
        // 디스크에 내려 쓴 뒤 이름을 바꾼다. 전원이 끊겨도 새 이름에 빈 파일이 남지 않게
        FileOutputStream(tmp).use { out ->
            out.write(document.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) { target.delete(); check(tmp.renameTo(target)) { "이름 바꾸기 실패" } }
    }

    override fun delete(collection: String, key: String) = enqueue("지우기 $collection/$key") {
        File(dir(collection), fileName(key)).delete()
    }

    override fun deleteAll(collection: String) = enqueue("모음 지우기 $collection") {
        dir(collection).deleteRecursively()
    }

    /** 맡긴 쓰기를 다 끝낼 때까지 기다린다 */
    fun flush() {
        if (!writer.isShutdown) writer.submit {}.get()
    }

    /** 맡긴 쓰기를 끝내고 저장 스레드를 멈춘다. 그 뒤의 쓰기는 하지 않는다 (데이터 전체 초기화 전에 부른다) */
    fun close() {
        writer.shutdown()
        writer.awaitTermination(CLOSE_WAIT_SEC, TimeUnit.SECONDS)
    }

    private fun enqueue(what: String, block: () -> Unit) {
        if (writer.isShutdown) return
        writer.execute { runCatching(block).onFailure { log("저장 $what 실패: $it") } }
    }

    private fun dir(collection: String) = File(root, collection)
    /** URLEncoder 는 `*` 를 그대로 둔다. Windows 파일 이름에 못 쓰므로 따로 바꾼다(테스트가 Windows 에서 돈다) */
    private fun fileName(key: String) = URLEncoder.encode(key, Charsets.UTF_8.name()).replace("*", "%2A") + EXT
    private fun keyOf(f: File) = URLDecoder.decode(f.name.removeSuffix(EXT), Charsets.UTF_8.name())

    companion object {
        /** 앱 전용 폴더(`filesDir`) 아래 저장 폴더 이름 */
        const val DIR = "store"
        private const val EXT = ".json"
        private const val TMP = ".tmp"
        private const val THREAD = "document-store"
        /** 닫을 때 남은 쓰기를 기다리는 최대 시간 */
        private const val CLOSE_WAIT_SEC = 5L
    }
}

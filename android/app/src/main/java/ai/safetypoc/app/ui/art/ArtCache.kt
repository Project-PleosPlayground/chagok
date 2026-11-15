package ai.safetypoc.app.ui.art

import core.item.ItemImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 그림 키 → assets 그림. 화면이 요청한 그림만 [io] 에서 읽고, 읽은 것은 [maxBytes] 안에서 캐시한다(오래 안 쓴 것부터 버린다).
 * 그림이 없거나, 키가 규칙에 맞지 않거나, 읽지 못하면 null 이다. APK 안 그림은 실행 중에 바뀌지 않으므로 없는 그림은 다시 읽지 않는다.
 * 메모리가 모자라 못 읽은 그림은 없는 그림으로 적지 않는다. 캐시를 비우고 다음 요청에 다시 읽는다.
 * 읽기는 한 번에 하나씩 한다. 처음 화면을 열 때 여러 그림을 한꺼번에 디코딩해 메모리가 튀지 않게.
 *
 * @param open assets 경로의 파일을 연다. 없으면 null
 * @param decode 연 파일을 그림으로. 못 읽으면 null
 * @param sizeOf 그림 하나가 차지하는 바이트
 * @param assetPath 키 → assets 경로. 규칙에 맞지 않는 키는 null. 기본은 아이템 이미지 값(ItemImage)
 */
class ArtCache<T : Any>(
    private val open: (assetPath: String) -> InputStream?,
    private val decode: (InputStream) -> T?,
    private val sizeOf: (T) -> Long,
    private val maxBytes: Long,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val assetPath: (key: String) -> String? = ItemImage::assetPath,
) {
    private val lock = Any()
    private val loading = Mutex()
    private val cache = LinkedHashMap<String, T>(0, LOAD_FACTOR, true)
    private val missing = HashSet<String>()
    private var bytes = 0L

    /** 캐시에 있는 그림. 읽지 않는다 */
    fun cached(image: String): T? = synchronized(lock) { cache[image] }

    /** 이미 읽어 봤는데 없거나 못 읽은 그림인가 */
    fun isMissing(image: String): Boolean = synchronized(lock) { image in missing }

    suspend fun load(image: String): T? {
        known(image)?.let { return it.value }
        return loading.withLock {
            known(image)?.let { return@withLock it.value }
            var outOfMemory = false
            val loaded = assetPath(image)?.let { path ->
                withContext(io) {
                    try {
                        open(path)?.use(decode)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfMemoryError) {
                        outOfMemory = true
                        null
                    } catch (e: Exception) {
                        null
                    }
                }
            }
            synchronized(lock) {
                when {
                    loaded != null -> put(image, loaded)
                    outOfMemory -> { cache.clear(); bytes = 0L }
                    else -> missing += image
                }
            }
            loaded
        }
    }

    /** 메모리가 모자랄 때 비운다. 없는 그림 목록은 남긴다 */
    fun clear() = synchronized(lock) { cache.clear(); bytes = 0L }

    private class Known<T>(val value: T?)

    private fun known(image: String): Known<T>? = synchronized(lock) {
        cache[image]?.let { Known(it) } ?: if (image in missing) Known(null) else null
    }

    private fun put(image: String, value: T) {
        val size = sizeOf(value)
        if (size > maxBytes) return   // 캐시보다 큰 그림은 담지 않는다. 쓰는 화면이 들고 있는 동안만 메모리에 있다
        cache.put(image, value)?.let { bytes -= sizeOf(it) }
        bytes += size
        val oldest = cache.entries.iterator()
        while (bytes > maxBytes && oldest.hasNext()) {
            val e = oldest.next()
            if (e.key == image) continue
            bytes -= sizeOf(e.value); oldest.remove()
        }
    }

    private companion object {
        /** LinkedHashMap 기본 load factor */
        const val LOAD_FACTOR = 0.75f
    }
}

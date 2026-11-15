package ai.safetypoc.app.ui.art

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.FileNotFoundException

/**
 * 캐릭터 그림 경로 (기획/06_아트_리소스 "캐릭터"). 차종(그림 폴더 이름)마다 `assets/character/<차종>/` 에 둔다.
 * - `still_lv<단계>.png`: 단계별 정지 그림 (홈, 상점, 설정, 이름 짓기, 주행 결과)
 * - `drive_lv<단계>.png`: 단계별 주행 자세 ([CharacterStill] 의 driving 이 true 일 때)
 * - `profile.png`: 프로필 선택·추가 그림. 단계별 그림이 없는 차종은 이 그림을 대신 쓴다
 * 변신 영상(`transform_*.mp4`)은 ui/transform 이 읽는다.
 */
object CharacterArt {
    const val ASSET_DIR = "character"
    /** 차종 폴더 이름: 영문 소문자, 숫자, 밑줄 (06 "폴더 구조") */
    private val ASSET_KEY = Regex("[a-z0-9_]+")

    fun stillPath(assetKey: String, stage: Int) = "$ASSET_DIR/$assetKey/still_lv$stage.png"
    fun drivePath(assetKey: String, stage: Int) = "$ASSET_DIR/$assetKey/drive_lv$stage.png"
    fun profilePath(assetKey: String) = "$ASSET_DIR/$assetKey/profile.png"

    /** 그릴 그림 후보. 앞에서부터 있는 그림을 쓴다. 주행 중이면 주행 자세 → 정지 그림 → 프로필 그림. 규칙에 맞지 않는 차종이면 없음 */
    fun candidates(assetKey: String, stage: Int, driving: Boolean): List<String> {
        if (!ASSET_KEY.matches(assetKey)) return emptyList()
        val still = listOf(stillPath(assetKey, stage), profilePath(assetKey))
        return if (driving) listOf(drivePath(assetKey, stage)) + still else still
    }

    /** 프로필 그림 후보 */
    fun profileCandidates(assetKey: String): List<String> =
        if (ASSET_KEY.matches(assetKey)) listOf(profilePath(assetKey)) else emptyList()
}

/**
 * 캐릭터 그림 캐시. 키는 assets 경로 그대로다. 앱 전체가 하나를 같이 쓴다.
 * 그림 한 장은 560 × 560 px 로 약 1.25 MB 다. 한 차종의 정지·주행 자세·프로필 11장이 약 14 MB 라, 앱 최대 힙의 1/16 이면 보통 기기에서 다 담긴다.
 * 아이템 캐시(힙의 1/8)와 합쳐 비트맵 캐시가 힙의 3/16 을 넘지 않는다.
 */
object CharacterArtStore {
    private const val HEAP_SHARE = 16L
    /** ARGB_8888 한 픽셀 */
    private const val BYTES_PER_PIXEL = 4L

    @Volatile private var instance: ArtCache<ImageBitmap>? = null

    fun cache(context: Context): ArtCache<ImageBitmap> =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext.assets).also { instance = it } }

    /** 캐시를 비운다. 만든 적이 없으면 아무것도 하지 않는다 */
    fun clear() { instance?.clear() }

    private fun create(assets: AssetManager) = ArtCache(
        open = { path -> try { assets.open(path) } catch (_: FileNotFoundException) { null } },
        decode = { BitmapFactory.decodeStream(it)?.asImageBitmap() },
        sizeOf = { it.width.toLong() * it.height * BYTES_PER_PIXEL },
        maxBytes = Runtime.getRuntime().maxMemory() / HEAP_SHARE,
        assetPath = { it },
    )
}

/** 후보 가운데 캐시에 있는 첫 그림. 아직 읽어 보지 않은 후보를 만나면 그 뒤는 보지 않는다(앞 후보가 있을 수 있으므로) */
internal fun <T : Any> ArtCache<T>.firstCached(keys: List<String>): T? {
    for (key in keys) {
        cached(key)?.let { return it }
        if (!isMissing(key)) return null
    }
    return null
}

/** 후보를 앞에서부터 읽어 처음 있는 그림. 모두 없으면 null */
internal suspend fun <T : Any> ArtCache<T>.loadFirst(keys: List<String>): T? {
    for (key in keys) load(key)?.let { return it }
    return null
}

/** 캐릭터 그림을 [modifier] 안에 비율을 지켜 그린다. [driving] 이면 주행 자세. 읽는 동안이나 그림이 없으면 [fallback] */
@Composable
fun CharacterStill(assetKey: String?, stage: Int, modifier: Modifier = Modifier, driving: Boolean = false, fallback: @Composable () -> Unit) {
    val keys = remember(assetKey, stage, driving) { assetKey?.let { CharacterArt.candidates(it, stage, driving) }.orEmpty() }
    CharacterImage(keys, modifier, colorFilter = null, fallback)
}

/** 프로필 그림을 [modifier] 안에 비율을 지켜 그린다. 읽는 동안이나 그림이 없으면 [fallback] */
@Composable
fun CharacterProfileArt(assetKey: String?, modifier: Modifier = Modifier, colorFilter: ColorFilter? = null, fallback: @Composable () -> Unit) {
    val keys = remember(assetKey) { assetKey?.let(CharacterArt::profileCandidates).orEmpty() }
    CharacterImage(keys, modifier, colorFilter, fallback)
}

/** 파일 읽기와 디코딩은 캐시가 IO 스레드에서 한다. 이미 읽은 그림은 첫 프레임부터 그린다 */
@Composable
private fun CharacterImage(keys: List<String>, modifier: Modifier, colorFilter: ColorFilter?, fallback: @Composable () -> Unit) {
    val context = LocalContext.current
    val cache = remember(context) { CharacterArtStore.cache(context) }
    // 후보가 바뀌면(차종·단계·주행 자세) 상태를 새로 만든다
    val bitmap = remember(keys, cache) { mutableStateOf(cache.firstCached(keys)) }
    LaunchedEffect(keys, cache) { if (bitmap.value == null) bitmap.value = cache.loadFirst(keys) }
    bitmap.value?.let { Image(it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Fit, colorFilter = colorFilter) } ?: fallback()
}

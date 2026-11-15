package ai.safetypoc.app.ui.art

import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import java.io.FileNotFoundException

/**
 * 아이템 그림. 원본은 기획/디자인/Image/Item/ 의 PNG이고 빌드가 assets/item/ 에 넣는다(app/build.gradle.kts `syncItemArt`).
 * 이미지 값(ItemDef.imageKey, 예: Pet/Haechi/Haechi_Lv1)으로 찾는다. 앱 전체가 캐시 하나를 같이 쓴다.
 */
object ItemArtStore {
    /** 캐시가 쓸 수 있는 메모리: 앱 최대 힙의 1/8 (Android 비트맵 캐시 권장치) */
    private const val HEAP_SHARE = 8L
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
    )
}

/**
 * 아이템 그림을 [modifier] 크기 안에 비율을 지켜 그린다. 읽는 동안, 그리고 그림이 없거나 못 읽으면 같은 자리에 platform_gray 를 칠한다.
 * 모양(둥근 모서리 등)은 [modifier] 로 정한다.
 */
@Composable
fun ItemArt(image: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cache = remember(context) { ItemArtStore.cache(context) }
    // image 가 바뀌면 상태를 새로 만든다. 목록 칸이 다른 아이템으로 재사용될 때 앞 그림이 남지 않게
    val bitmap = remember(image, cache) { mutableStateOf(cache.cached(image)) }
    LaunchedEffect(image, cache) { if (bitmap.value == null) bitmap.value = cache.load(image) }
    val shown = bitmap.value
    if (shown != null) {
        Image(shown, contentDescription, modifier, contentScale = ContentScale.Fit)
    } else {
        Box(modifier.background(AppPalette.platformGray).semantics { contentDescription?.let { this.contentDescription = it } })
    }
}

/** 확인용 그림 4장과 없는 그림 1장(회색 자리) */
private val previewImages = listOf("Pet/Haechi/Haechi_Lv1", "Pet/Bird/Bird_Lv3", "Accessories/Cloud", "Accessories/Fortune_pouch", "Accessories/Not_delivered")
private val previewArtSize = AppDimens.touchTarget * 3

@Preview
@Composable
internal fun ItemArtPreview() = AppTheme {
    Row(
        Modifier.background(AppPalette.white).padding(AppDimens.screenMargin),
        horizontalArrangement = Arrangement.spacedBy(AppDimens.touchGap),
    ) {
        for (image in previewImages) ItemArt(image, contentDescription = image, modifier = Modifier.size(previewArtSize))
    }
}

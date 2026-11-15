package ai.safetypoc.app.ui.transform

import ai.safetypoc.app.R
import ai.safetypoc.app.SafetyApp.Companion.LOG_TAG
import ai.safetypoc.app.ui.component.pressable
import ai.safetypoc.app.ui.theme.AppDimens
import ai.safetypoc.app.ui.theme.AppPalette
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope



/**
 * 변신 영상을 창 전체(tab_bar 포함)에 영화 포스터처럼 튼다 [가안]. 흰 바탕, 오른쪽에 변신 영상, 왼쪽에 포스터 글자 묶음
 * (작은 머리글 · 큰 EVOLUTION · 아주 큰 단계 숫자 · 한 줄 부제 · 빌링 블록). 글꼴은 Bebas Neue(SIL OFL 1.1).
 * 여러 단계를 이어 붙인 영상은 재생 위치로 지금 단계를 계산해 숫자만 바꾼다(영상은 멈추지 않는다). 뒤 화면은 눌리지 않는다.
 */
@Composable
fun TransformOverlay(
    state: TransformState, characterName: String?, characterAssetKey: String?, actions: TransformActions,
    /** 효과음을 낼지. 설정 "캐릭터 음성"을 끄면 소리 없이 */
    sound: Boolean = true,
) {
    val clip = state.current ?: return
    var progress by remember(clip.path) { mutableFloatStateOf(0f) }
    // 이어 붙인 영상에서 지금 보이는 단계: 1단계에서 시작하면 0초부터 2단계 영상
    val step = (progress * clip.steps).toInt().coerceIn(0, clip.steps - 1)
    val stage = clip.fromStage + 1 + step
    // 다른 차종 영상을 대신 틀 때는 이름을 붙이지 않는다
    val name = characterName?.takeIf { clip.assetKey == characterAssetKey }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(APPEAR_MS, easing = FastOutSlowInEasing)) }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }.background(Color.White)
            .clickable(remember { MutableInteractionSource() }, indication = null) {},
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = minOf(maxWidth * VIDEO_WIDTH, maxHeight * VIDEO_HEIGHT)
            // 오른쪽: 변신 영상. 가장자리를 흰색으로 녹여 네모가 보이지 않게
            Box(Modifier.align(Alignment.CenterEnd).padding(end = VIDEO_END).size(side)) {
                key(clip.path) {
                    AssetVideo(clip.path, sound, onEnd = actions::next, onProgress = { progress = it }, Modifier.fillMaxSize())
                }
                EdgeFade(Modifier.fillMaxSize())
            }
            // 왼쪽: 포스터 글자
            Column(
                Modifier.align(Alignment.CenterStart).padding(start = TEXT_START)
                    .graphicsLayer { translationX = (1f - appear.value) * -40.dp.toPx() },
            ) {
                Text(stringResource(R.string.transform_presents), style = KICKER)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.transform_evolution), style = TITLE)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.transform_lv_prefix), style = LV_PREFIX, modifier = Modifier.padding(bottom = 36.dp, end = 12.dp))
                    AnimatedContent(
                        targetState = stage,
                        transitionSpec = {
                            (fadeIn(tween(STEP_MS)) + slideInVertically(tween(STEP_MS)) { it / 4 }) togetherWith
                                (fadeOut(tween(STEP_MS / 2)) + slideOutVertically(tween(STEP_MS / 2)) { -it / 4 })
                        },
                        label = "stage",
                    ) { s -> Text("%02d".format(s), style = NUMBER) }
                }
                Text(
                    name?.let { stringResource(R.string.transform_tagline_named, it, stage) } ?: stringResource(R.string.transform_tagline, stage),
                    style = TAGLINE,
                )
                Spacer(Modifier.height(28.dp))
                Box(Modifier.size(72.dp, 3.dp).background(AppPalette.primaryBlue))
                Spacer(Modifier.height(28.dp))
                Text(stringResource(R.string.transform_billing), style = BILLING)
            }
        }
        Text(
            stringResource(R.string.transform_close),
            Modifier.align(Alignment.TopEnd).padding(AppDimens.screenMargin)
                .border(1.dp, AppPalette.ink.copy(alpha = 0.25f), CircleShape)
                .pressable(remember { MutableInteractionSource() }, onClick = actions::close)
                .padding(horizontal = 28.dp, vertical = 14.dp),
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium, color = AppPalette.ink),
        )
    }
}

/* ---------- 포스터 값 [가안] ---------- */

/** 포스터 글꼴. 영문·숫자 Bebas Neue (SIL OFL 1.1) */
private val BEBAS = FontFamily(Font(R.font.bebas_neue))
private val GREY = Color(0xFF8E8E93)

private val KICKER = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 6.sp, color = GREY)
private val TITLE = TextStyle(fontFamily = BEBAS, fontSize = 112.sp, lineHeight = 104.sp, letterSpacing = 2.sp, color = Color(0xFF111111))
private val LV_PREFIX = TextStyle(fontFamily = BEBAS, fontSize = 64.sp, color = Color(0xFF111111))
private val NUMBER = TextStyle(fontFamily = BEBAS, fontSize = 280.sp, lineHeight = 250.sp, color = Color(0xFF111111))
private val TAGLINE = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF111111))
private val BILLING = TextStyle(fontFamily = BEBAS, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = 3.sp, color = GREY)

/** 영상 한 변: 창 폭·높이에 대한 비율, 오른쪽 여백. 왼쪽 글자 시작 */
private const val VIDEO_WIDTH = 0.6f
private const val VIDEO_HEIGHT = 0.94f
private val VIDEO_END = 24.dp
private val TEXT_START = 96.dp
private const val APPEAR_MS = 600
private const val STEP_MS = 420
/** 재생 위치를 읽는 간격 */
private const val PROGRESS_POLL_MS = 80L

/**
 * 영상 가장자리를 흰색으로 덮는다. 영상 바탕은 흰색이지만 기기 디코더에 따라 조금 어둡게 나와(에뮬레이터 247~253)
 * 영상 둘레에 네모가 보인다. 캐릭터는 가장자리에서 [EDGE_FRACTION] 보다 안쪽에 있다.
 */
@Composable
private fun EdgeFade(modifier: Modifier) {
    val white = Color.White
    val clear = white.copy(alpha = 0f)
    Box(modifier.drawBehind {
        val e = size.minDimension * EDGE_FRACTION
        drawRect(Brush.horizontalGradient(listOf(white, clear), startX = 0f, endX = e))
        drawRect(Brush.horizontalGradient(listOf(clear, white), startX = size.width - e, endX = size.width))
        drawRect(Brush.verticalGradient(listOf(white, clear), startY = 0f, endY = e))
        drawRect(Brush.verticalGradient(listOf(clear, white), startY = size.height - e, endY = size.height))
    })
}
private const val EDGE_FRACTION = 0.14f

/**
 * 효과음 용도: 시작 효과음(OnboardingScreen)과 같은 시스템 효과음. 포커스는 잠깐 받고 다른 소리는 줄인다(MAY_DUCK).
 * 포커스를 못 받거나 잃으면 영상은 소리 없이 계속 튼다.
 */
private val SOUND_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
    .build()

/**
 * assets 안 영상을 한 번 튼다. [PLAY_SPEED] 배로 조금 빨리 튼다.
 * 끝나면 마지막 장면(변신 완료)을 [END_HOLD_MS] 동안 그대로 보여 준 뒤 [onEnd]. 열지 못하면 바로 [onEnd] (11/14)
 */
@Composable
private fun AssetVideo(path: String, sound: Boolean, onEnd: () -> Unit, onProgress: (Float) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val end by rememberUpdatedState(onEnd)
    val report by rememberUpdatedState(onProgress)
    val player = remember { MediaPlayer().apply { setAudioAttributes(SOUND_ATTRIBUTES) } }
    val scope = rememberCoroutineScope()
    // 끝까지 다 틀었을 때만 마지막 장면을 잠깐 붙잡는다. 단계 숫자가 마지막 단계에 머물게 진행도를 끝으로 둔다
    val finished: () -> Unit = { scope.launch { report(1f); delay(END_HOLD_MS); end() } }
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val focus = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(SOUND_ATTRIBUTES)
            .setOnAudioFocusChangeListener { change -> if (change < 0) runCatching { player.setVolume(0f, 0f) } }
            .build()
    }
    DisposableEffect(player) {
        val hasFocus = sound && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) player.setVolume(0f, 0f)
        onDispose {
            player.release()
            if (hasFocus) audio.abandonAudioFocusRequest(focus)
        }
    }
    LaunchedEffect(player) {
        while (true) {
            runCatching { if (player.isPlaying && player.duration > 0) report(player.currentPosition.toFloat() / player.duration) }
            delay(PROGRESS_POLL_MS)
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = play(context, player, path, Surface(st), onFinished = finished) { end() }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }
        },
    )
}

private fun play(context: Context, player: MediaPlayer, path: String, surface: Surface, onFinished: () -> Unit, onEnd: () -> Unit) {
    try {
        context.assets.openFd(path).use { fd -> player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        player.setSurface(surface)
        player.setOnPreparedListener { mp ->
            runCatching { mp.playbackParams = PlaybackParams().setSpeed(PLAY_SPEED) }
            mp.start()
        }
        player.setOnCompletionListener { onFinished() }
        player.setOnErrorListener { _, what, extra -> Log.w(LOG_TAG, "변신 영상 재생 실패($what/$extra): $path"); onEnd(); true }
        player.prepareAsync()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "변신 영상을 열지 못함: $path", e)
        onEnd()
    }
}

/** 변신 영상 재생 속도. 영상은 이미 1.3배로 만들어 두었고, 그 위에 조금 더 빠르게 (11/14) */
private const val PLAY_SPEED = 1.2f
/** 변신이 끝난 마지막 장면을 붙잡아 두는 시간 */
private const val END_HOLD_MS = 2_000L

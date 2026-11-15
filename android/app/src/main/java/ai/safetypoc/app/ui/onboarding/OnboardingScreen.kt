package ai.safetypoc.app.ui.onboarding

import ai.safetypoc.app.R
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import ai.safetypoc.app.ui.theme.AppPalette
import ai.safetypoc.app.ui.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.delay

/** 온보딩을 보여 주는 시간 (#44 화면 개요: 앱 실행 시 1.5초) */
const val ONBOARDING_MS = 1_500L

/** 온보딩에 들어온 때의 상태. [first] 면 처음 보여 주는 것이라 시동음을 낸다. [remainingMs] 뒤 홈으로 간다 */
data class OnboardingTiming(val first: Boolean, val remainingMs: Long)

/**
 * 온보딩. [timing] 의 남은 시간 동안 보이고 [onDone].
 * 시동음은 처음 들어왔을 때만 내고, 온보딩이 끝나거나 화면을 떠나면 멈춘다(#44 "1.5초 동안 재생").
 */
@Composable
fun OnboardingScreen(sound: StartupSound, timing: OnboardingTiming, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) {
        if (timing.first) sound.play()
        delay(timing.remainingMs)
        sound.stop()
        done()
    }
    DisposableEffect(Unit) { onDispose { sound.stop() } }
    OnboardingContent(modifier)
}

/**
 * 온보딩 화면 모습 (영역사양_온보딩화면): 창 정중앙에 app_icon.
 * 앱 아이콘 그림이 정해지기 전까지 같은 크기의 platform_gray 사각으로 자리를 둔다(디자인 시스템 6절 app_icon).
 */
@Composable
fun OnboardingContent(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.app_icon_description)
    Box(modifier.fillMaxSize().background(AppPalette.white), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(OnboardingSizes.appIcon)
                .background(AppPalette.platformGray)
                .semantics { contentDescription = description },
        )
    }
}

/** Preview 창 폭(dp). 기준 기종 2칸 창 (디자인 시스템 3절) */
private const val PREVIEW_WINDOW_WIDTH = 1700
/** Preview 창 높이(dp) */
private const val PREVIEW_WINDOW_HEIGHT = 1184

@Preview(widthDp = PREVIEW_WINDOW_WIDTH, heightDp = PREVIEW_WINDOW_HEIGHT)
@Composable
internal fun OnboardingPreview() {
    AppTheme { OnboardingContent() }
}

/**
 * 온보딩 시동음 (#44). 소리 파일이 없으면 아무것도 하지 않는다.
 * Pleos 오디오 포커스 정책(audio-focus-development-guide)대로 재생 전에 포커스를 요청하고, 잃으면 멈춘다.
 */
interface StartupSound {
    fun play()
    fun stop()

    companion object {
        /** res/raw/ 에 넣을 시동음 파일 이름 (확장자 빼고) */
        const val RAW_NAME = "engine_start"

        /** res/raw/[RAW_NAME] 을 재생한다. 파일이 없으면 재생하지 않는다. 재생 스레드를 하나 띄우므로 앱에 하나만 만든다 */
        fun raw(context: Context): StartupSound = RawStartupSound(context.applicationContext)
    }
}

/**
 * 포커스: 재생 준비를 마친 뒤, 짧은 효과음이라 잠깐 받고 다른 소리는 줄여 두게(GAIN_TRANSIENT_MAY_DUCK) 요청한다. 받지 못하면 재생하지 않는다.
 * 잃으면(LOSS, LOSS_TRANSIENT: Gleo 호출 등) 바로 멈추고 반납한다. CAN_DUCK 은 시스템이 소리를 줄인다.
 * 용도: 시스템 효과음(ASSISTANCE_SONIFICATION, AAOS 의 SYSTEM_SOUND). 속성은 재생 준비 전에 넣는다(MediaPlayer.create 의 속성 받는 판).
 * 늦게 받는 포커스(DELAYED)는 받지 않는다.
 * 재생 준비(MediaPlayer.create)는 오래 걸릴 수 있어 메인이 아닌 스레드 하나에서만 한다. play·stop 은 그 스레드에 넘긴다.
 */
private class RawStartupSound(private val context: Context) : StartupSound {
    private val handler = Handler(HandlerThread("startup-sound").apply { start() }.looper)
    private val audio by lazy { context.getSystemService(AudioManager::class.java) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change -> if (stopsOnFocusChange(change)) release() }, handler)
        .build()
    /** [handler] 스레드에서만 읽고 쓴다 */
    private var player: MediaPlayer? = null

    override fun play() { handler.post(::start) }

    override fun stop() { handler.post(::release) }

    private fun start() {
        @Suppress("DiscouragedApi")   // 파일을 넣기만 하면 코드 수정 없이 나오게 이름으로 찾는다
        val id = context.resources.getIdentifier(StartupSound.RAW_NAME, "raw", context.packageName)
        if (id == 0) return
        release()
        // 준비를 마친 뒤에 포커스를 요청한다. 준비가 오래 걸려도 그동안 다른 소리를 줄여 두지 않는다
        val p = MediaPlayer.create(context, id, attributes, audio.generateAudioSessionId()) ?: return
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            p.release()
            return
        }
        player = p.apply {
            setOnCompletionListener { release() }
            start()
        }
    }

    private fun release() {
        val p = player ?: return
        player = null
        // 소리를 바로 멈추고, 포커스를 반납한 뒤 플레이어를 놓는다. 해제는 오래 걸릴 수 있다
        try {
            p.stop()
        } catch (_: IllegalStateException) {
            // 재생 중·재생 끝 상태가 아니면 멈출 것이 없다
        }
        audio.abandonAudioFocusRequest(focus)
        p.release()
    }
}

/** 시동음이 이 포커스 변화에서 멈추는지. 잃으면(LOSS, LOSS_TRANSIENT) 멈추고, CAN_DUCK 은 시스템이 줄이게 둔다 */
internal fun stopsOnFocusChange(change: Int): Boolean =
    change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT

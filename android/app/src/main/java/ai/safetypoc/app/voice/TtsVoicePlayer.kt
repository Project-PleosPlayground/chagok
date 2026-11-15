package ai.safetypoc.app.voice

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import engine.FeedbackCue
import java.util.Locale

/**
 * Pleos TextToSpeech SDK 3.3.1, ON_DEVICE 모드 (앱 등록 불필요, 에뮬레이터에서 한국어 재생 확인 11/4).
 * 주의: addEventListener 는 initialize 성공 뒤에 불러야 한다. 먼저 부르면 SDK 내부에서 크래시.
 * 첫 소리까지 약 7초 걸리므로 앱 시작 때 미리 초기화한다.
 */
class TtsVoicePlayer(private val context: Context) : VoicePlayer {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val events = object : EventListener {
        override fun onStart() {}
        override fun onReady() {}
        override fun onDone() {}
        override fun onStop() {}
        override fun onError(e: ErrorType) { Log.w(TAG, "TTS 오류: $e") }
        override fun onUpdatedRms(rms: Double) {}
    }

    override fun start() {
        if (tts != null) return
        val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
        tts = t
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { t.addEventListener(events) }
                    runCatching { t.setLocale(Locale.KOREA) }
                    ready = true
                }
                override fun onFailure(e: ErrorType) { Log.e(TAG, "TTS 초기화 실패: $e") }
            })
        }.onFailure { Log.e(TAG, "TTS 초기화 예외", it) }
    }

    override fun play(cue: FeedbackCue) {
        val text = CueScripts.textFor(cue)
        val t = tts
        if (t == null || !ready || text.isEmpty()) {
            Log.i(TAG, "재생 건너뜀(ready=$ready): ${cue.type}")
            return
        }
        // 두 번째 인자 true: 재생 중인 발화를 끊고 새로 말한다 (테스트 앱과 같은 값).
        runCatching { t.speak(text, true) }.onFailure { Log.w(TAG, "speak 예외", it) }
    }

    override fun release() {
        runCatching { tts?.release() }
        tts = null
        ready = false
    }

    private companion object { const val TAG = "TtsVoice" }
}

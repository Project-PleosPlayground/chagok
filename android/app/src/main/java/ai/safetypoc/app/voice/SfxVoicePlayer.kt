package ai.safetypoc.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import engine.CueType
import engine.FeedbackCue

/**
 * 주행 중 사건에 캐릭터가 짧게 내는 효과음 (11/14, TTS 음성 안내 대신). 말이 아니라 "앗!", "우와앗~" 같은 소리라
 * 운전자가 알아들으려 애쓰지 않고 "얘가 놀랐구나"로 받는다. 언제 낼지(간격·우선순위)는 엔진 FeedbackDecider 가 정한다.
 * 소리는 assets/sfx 에 있다(ElevenLabs 로 만든 오리지널 소리). 세션 시작 인사와 거리 칭찬은 소리를 내지 않는다.
 */
class SfxVoicePlayer(private val context: Context) : VoicePlayer {
    private var pool: SoundPool? = null
    private val ids = HashMap<CueType, Int>()

    override fun start() {
        if (pool != null) return
        val p = SoundPool.Builder().setMaxStreams(MAX_STREAMS).setAudioAttributes(ATTRIBUTES).build()
        pool = p
        for ((type, file) in FILES) {
            runCatching { context.assets.openFd("$DIR/$file").use { fd -> ids[type] = p.load(fd, 1) } }
                .onFailure { Log.w(TAG, "효과음을 열지 못함: $file", it) }
        }
    }

    override fun play(cue: FeedbackCue) {
        val id = ids[cue.type] ?: return
        val stream = pool?.play(id, VOLUME, VOLUME, 1, 0, 1f) ?: 0
        Log.i(TAG, "효과음 ${cue.type} (stream=$stream)")
    }

    override fun release() {
        pool?.release(); pool = null; ids.clear()
    }

    private companion object {
        const val TAG = "SfxVoice"
        const val DIR = "sfx"
        const val MAX_STREAMS = 2
        const val VOLUME = 1f
        val FILES = mapOf(
            CueType.HARSH_BRAKE to "brake.mp3",
            CueType.HARSH_ACCEL to "accel.mp3",
            CueType.LANE_NO_SIGNAL to "lane_no_signal.mp3",
            CueType.LANE_SIGNALED_FIRST to "lane_signaled.mp3",
            CueType.LOW_BATTERY to "low_battery.mp3",
        )
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    }
}

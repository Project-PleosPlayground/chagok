package ai.safetypoc.app.voice

import engine.CueType
import engine.FeedbackCue

/**
 * 엔진의 발화 cue 를 소리로 바꾼다. 녹음 클립 / TTS / 혼합 중 무엇을 쓸지는 팀 결정 전이라
 * 구현을 갈아 끼울 수 있게 인터페이스로 둔다 (조사_음성대화 8절 1번).
 */
interface VoicePlayer {
    fun start()
    fun play(cue: FeedbackCue)
    fun release()
}

/** 설정의 음성 끄기 (05 "음성 끄기 설정 필수"). 꺼져 있으면 cue 를 버린다. 재생 중인 것은 끝까지 둔다 */
class SwitchableVoicePlayer(private val inner: VoicePlayer, private val on: () -> Boolean) : VoicePlayer {
    override fun start() = inner.start()
    override fun play(cue: FeedbackCue) { if (on()) inner.play(cue) }
    override fun release() = inner.release()
}

/**
 * [가안] cue 종류별 문구. 캐릭터 말투가 정해지면 기획 쪽에서 바꾼다.
 * 엔진은 같은 종류를 variant 0..2 로 돌려쓴다 (FeedbackConfig.variantsPerType).
 */
object CueScripts {
    private val lines: Map<CueType, List<String>> = mapOf(
        CueType.SESSION_START to listOf("출발할게요. 오늘도 안전하게 가요.", "좋아요, 같이 달려 봐요.", "안전벨트 확인하셨죠? 출발!"),
        CueType.HARSH_BRAKE to listOf("방금 꽤 세게 멈췄어요.", "앞차와 거리를 조금 더 둬 볼까요?", "급하게 서면 저도 놀라요."),
        CueType.HARSH_ACCEL to listOf("천천히 출발해도 괜찮아요.", "방금은 조금 급했어요.", "부드럽게 밟아 볼까요?"),
        CueType.LANE_NO_SIGNAL to listOf("차선 바꿀 때 깜빡이 켜 주세요.", "깜빡이를 잊으셨어요.", "다음엔 깜빡이 먼저!"),
        CueType.LANE_SIGNALED_FIRST to listOf("깜빡이 좋아요!", "신호 잘 주셨어요.", "역시 깔끔해요."),
        CueType.MILESTONE to listOf("잘 달리고 있어요.", "지금처럼만 가요.", "안전운전 중이에요."),
    )

    fun textFor(cue: FeedbackCue): String {
        val options = lines[cue.type].orEmpty()
        return if (options.isEmpty()) "" else options[cue.variant % options.size]
    }
}

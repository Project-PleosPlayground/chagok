package ai.safetypoc.app.settings

/**
 * 앱 설정 (02 화면목록 "설정"). 기기 하나에 하나: 음성, 위치 기록.
 * - 음성: 기본 켬. 끄면 주행 중 캐릭터가 말하지 않는다(05 "음성 끄기 설정 필수")
 * - 위치 기록: 기본 켬. 급제동 같은 이벤트가 난 곳을 이 기기 안에만 저장해 자주 놀라는 곳을 찾는다. 끄면 GPS 를 받지 않고 위치를 붙이지 않는다
 * AI 리포트 동의는 프로필마다라 [ai.safetypoc.app.ai.AiConsent] 에 따로 둔다.
 */
class AppSettings(private val flags: Flags) {
    var voiceOn: Boolean
        get() = flags.get(VOICE, true)
        set(v) = flags.put(VOICE, v)
    var locationOn: Boolean
        get() = flags.get(LOCATION, true)
        set(v) = flags.put(LOCATION, v)
    /** 디버그 탭을 보일지. 기본 숨김. 디버그 빌드에서 홈 coin_bar 를 길게 누르면 바뀐다(시연 영상에 디버그 탭이 나오지 않게) */
    var debugTabOn: Boolean
        get() = flags.get(DEBUG_TAB, false)
        set(v) = flags.put(DEBUG_TAB, v)

    companion object {
        const val PREFS = "settings"
        private const val VOICE = "voice_on"
        private const val LOCATION = "location_on"
        private const val DEBUG_TAB = "debug_tab_on"
    }
}

package ai.safetypoc.app.ai

import ai.safetypoc.app.settings.Flags

/**
 * AI 리포트 동의 (#13). 프로필마다 따로 둔다. 기본은 꺼짐: 켜야만 주행 요약을 AI 중계 서버·Claude API 로 보낸다.
 * 끄면 다음 주행부터 바로 보내지 않는다(동의 철회). 보내는 항목과 안내 문구는 `R.string.ai_consent_notice`.
 * 화면: 설정 탭 토글, 그리고 첫 주행 종료 화면에서 한 번 묻는다([asked]).
 */
class AiConsent(private val flags: Flags) {
    fun isOn(profileId: String): Boolean = flags.get(KEY_PREFIX + profileId, false)
    fun set(profileId: String, on: Boolean) {
        flags.put(KEY_PREFIX + profileId, on)
        flags.put(ASKED_PREFIX + profileId, true)
    }

    /** 이 프로필에 켤지 물어본 적이 있는지. 켜든 안 켜든 한 번 답하면 다시 묻지 않는다 */
    fun asked(profileId: String): Boolean = flags.get(ASKED_PREFIX + profileId, false)

    /** 이 프로필의 동의와 물어본 기록을 지운다. 프로필 삭제에서 부른다 */
    fun remove(profileId: String) {
        flags.remove(KEY_PREFIX + profileId)
        flags.remove(ASKED_PREFIX + profileId)
    }

    /** [from] 프로필의 동의와 물어본 기록을 [to] 프로필로 옮기고 [from] 것은 지운다. 물어본 적이 없으면 [to] 는 그대로 둔다 */
    fun move(from: String, to: String) {
        if (asked(from)) set(to, isOn(from))
        remove(from)
    }

    /** 모든 프로필의 동의와 물어본 기록을 지운다(모두 꺼짐, 다시 묻는다). 프로필 초기화에서 부른다 */
    fun clear() = flags.clear()

    companion object {
        const val PREFS = "ai_consent"
        private const val KEY_PREFIX = "on_"
        private const val ASKED_PREFIX = "asked_"
    }
}

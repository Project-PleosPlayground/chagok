package ai.safetypoc.app.ui.settings

import ai.safetypoc.app.ai.AiConsent
import ai.safetypoc.app.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 설정 탭이 보여 줄 값 */
data class SettingsState(
    /** 지금 프로필의 AI 리포트 동의 */
    val aiOn: Boolean = false,
    /** AI 중계 서버가 설정돼 있는지. 없으면 AI 리포트 항목을 끈 채로 보이고 바꿀 수 없다 */
    val aiAvailable: Boolean = true,
    val voiceOn: Boolean = true,
    val locationOn: Boolean = true,
    /** 주행 중이면 아무것도 바꿀 수 없다 (02 "설정은 정차 중에만") */
    val driving: Boolean = false,
    /** 주행 종료 화면에서 AI 리포트를 켤지 물을지: 서버가 있고, 꺼져 있고, 아직 물은 적이 없을 때 */
    val askAiOnDriveEnd: Boolean = false,
)

interface SettingsActions {
    fun setAi(on: Boolean)
    fun setVoice(on: Boolean)
    fun setLocation(on: Boolean)
    /** 프로필 선택 화면으로 간다 */
    fun openProfiles()
}

/**
 * 설정 탭 (02 화면목록 "설정", #101). 주행 중에는 바꾸지 않는다.
 * [onAiChanged]·[onLocationChanged] 는 바뀐 뒤 앱이 할 일(로그, GPS 켜고 끄기, 방금 주행의 AI 리포트 요청).
 * [onOpenProfiles] 는 프로필 선택 화면으로 가는 길 (#119).
 */
class SettingsModel(
    /** 지금 쓰는 프로필. 읽을 때마다 부른다 */
    private val currentProfile: () -> String,
    private val consent: AiConsent,
    private val settings: AppSettings,
    private val aiAvailable: Boolean,
    private val driving: () -> Boolean,
    private val onAiChanged: (Boolean) -> Unit = {},
    private val onLocationChanged: (Boolean) -> Unit = {},
    private val onOpenProfiles: () -> Unit = {},
) : SettingsActions {
    private val profileId: String get() = currentProfile()

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun refresh() { _state.value = read() }

    override fun setAi(on: Boolean) {
        if (driving() || !aiAvailable) return
        consent.set(profileId, on)
        refresh(); onAiChanged(on)
    }

    override fun setVoice(on: Boolean) {
        if (driving()) return
        settings.voiceOn = on
        refresh()
    }

    override fun setLocation(on: Boolean) {
        if (driving()) return
        settings.locationOn = on
        refresh(); onLocationChanged(on)
    }

    override fun openProfiles() {
        if (driving()) return
        onOpenProfiles()
    }

    private fun read(): SettingsState {
        val aiOn = aiAvailable && consent.isOn(profileId)
        return SettingsState(
            aiOn = aiOn, aiAvailable = aiAvailable,
            voiceOn = settings.voiceOn, locationOn = settings.locationOn,
            driving = driving(),
            askAiOnDriveEnd = aiAvailable && !aiOn && !consent.asked(profileId),
        )
    }
}

package ai.safetypoc.app.ui.settings

import ai.safetypoc.app.ai.AiConsent
import ai.safetypoc.app.settings.AppSettings
import ai.safetypoc.app.settings.MemoryFlags
import ai.safetypoc.app.voice.SwitchableVoicePlayer
import ai.safetypoc.app.voice.VoicePlayer
import engine.CueType
import engine.FeedbackCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {
    private val flags = MemoryFlags()
    private val consent = AiConsent(flags)
    private val settings = AppSettings(flags)
    private var driving = false
    private val aiChanges = mutableListOf<Boolean>()
    private val locationChanges = mutableListOf<Boolean>()
    private var profilesOpened = 0
    private fun model(aiAvailable: Boolean = true) =
        SettingsModel({ "p1" }, consent, settings, aiAvailable, { driving }, { aiChanges += it }, { locationChanges += it }, { profilesOpened++ })

    @Test fun `프로필 선택은 정차 중에만 연다`() {
        val m = model()
        m.openProfiles()
        assertEquals(1, profilesOpened)
        driving = true; m.refresh()
        m.openProfiles()
        assertEquals("주행 중에는 열지 않는다", 1, profilesOpened)
    }

    @Test fun `기본값 - AI 끔, 음성·위치 켬, 주행 종료 화면에서 한 번 묻는다`() {
        val s = model().state.value
        assertFalse(s.aiOn); assertTrue(s.voiceOn); assertTrue(s.locationOn); assertTrue(s.askAiOnDriveEnd)
    }

    @Test fun `AI 를 켜고 끄면 저장되고, 한 번 답하면 다시 묻지 않는다`() {
        val m = model()
        m.setAi(false)
        assertFalse(m.state.value.aiOn); assertFalse("괜찮아요도 답이다", m.state.value.askAiOnDriveEnd)
        m.setAi(true)
        assertTrue(consent.isOn("p1")); assertTrue(m.state.value.aiOn)
        assertFalse("다른 프로필은 따로", consent.isOn("p2"))
        assertEquals(listOf(false, true), aiChanges)
    }

    @Test fun `주행 중에는 아무것도 바꾸지 않는다`() {
        val m = model()
        driving = true; m.refresh()
        m.setAi(true); m.setVoice(false); m.setLocation(false)
        val s = m.state.value
        assertTrue(s.driving); assertFalse(s.aiOn); assertTrue(s.voiceOn); assertTrue(s.locationOn)
        assertTrue(aiChanges.isEmpty()); assertTrue(locationChanges.isEmpty())
    }

    @Test fun `서버가 없는 빌드는 AI 를 켤 수 없고 묻지도 않는다`() {
        val m = model(aiAvailable = false)
        m.setAi(true)
        assertFalse(m.state.value.aiOn); assertFalse(m.state.value.askAiOnDriveEnd); assertFalse(consent.isOn("p1"))
    }

    @Test fun `음성을 끄면 cue 를 버리고, 위치를 끄면 앱에 알린다`() {
        val played = mutableListOf<FeedbackCue>()
        val voice = SwitchableVoicePlayer(object : VoicePlayer {
            override fun start() = Unit
            override fun play(cue: FeedbackCue) { played += cue }
            override fun release() = Unit
        }) { settings.voiceOn }
        val m = model()
        val cue = FeedbackCue(CueType.HARSH_BRAKE, 0L, 0, null)
        voice.play(cue)
        m.setVoice(false); voice.play(cue)
        m.setVoice(true); voice.play(cue)
        assertEquals(2, played.size)
        m.setLocation(false)
        assertFalse(settings.locationOn); assertEquals(listOf(false), locationChanges)
    }
}

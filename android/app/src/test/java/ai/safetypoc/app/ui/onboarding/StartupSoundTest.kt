package ai.safetypoc.app.ui.onboarding

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class StartupSoundTest {
    @Test fun `포커스를 잃으면 멈추고, 줄이라는 알림과 다시 받음에는 멈추지 않는다`() {
        val stops = listOf(
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, AudioManager.AUDIOFOCUS_GAIN,
        ).map(::stopsOnFocusChange)
        assertEquals(listOf(true, true, false, false), stops)
    }
}

package ai.safetypoc.app.ai

import ai.safetypoc.app.settings.MemoryFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConsentTest {
    private val consent = AiConsent(MemoryFlags())

    @Test fun `옮기면 동의와 물어본 기록이 새 프로필로 가고 이전 프로필 것은 지운다`() {
        consent.set("default", true)
        consent.move("default", "a")
        assertTrue(consent.isOn("a")); assertTrue(consent.asked("a"))
        assertFalse(consent.isOn("default")); assertFalse(consent.asked("default"))
    }

    @Test fun `끈다고 답한 것도 물어본 기록과 함께 옮긴다`() {
        consent.set("default", false)
        consent.move("default", "a")
        assertFalse(consent.isOn("a")); assertTrue(consent.asked("a"))
    }

    @Test fun `물어본 적이 없으면 새 프로필은 그대로 둔다`() {
        consent.set("a", true)
        consent.move("default", "a")
        assertTrue(consent.isOn("a"))
    }
}

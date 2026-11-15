package ai.safetypoc.app

import org.junit.Assert.assertFalse
import ai.safetypoc.app.ui.home.HomeModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGraphDefaultsTest {
    private fun selected(g: AppGraph) = g.profiles.state(g.profileId).selected!!
    private fun assetOf(g: AppGraph) = g.content.active.characters.first { it.id == selected(g) }.assetKey

    @Test fun `기본 차종을 주면 처음 고른 캐릭터가 그 차`() {
        assertEquals("grandeur", assetOf(AppGraph(defaultCharacterAsset = "grandeur")))
    }

    @Test fun `없는 차종이면 첫 캐릭터`() {
        val g = AppGraph(defaultCharacterAsset = "nope")
        assertEquals(g.content.active.characters.first().id, selected(g))
    }

    @Test fun `시연 모드 - 그 단계에서 다음 단계까지 60% 찬 상태로 시작`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur", demoStartStage = 3)
        val p = g.profiles.progress(g.profileId, selected(g))
        assertEquals(3, p.stage)
        assertEquals(0.6f, g.growth.progress(p.xp), 0.01f)
    }

    @Test fun `시연 모드 최고 단계 - 그 단계 최소 경험치`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur", demoStartStage = 5)
        assertEquals(g.growth.minXp(5), g.profiles.progress(g.profileId, selected(g)).xp)
    }

    @Test fun `시연 모드 - 산타 모자를 가지고 머리에 쓰고 시작, 다시 켜도 하나`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur", demoStartStage = 3)
        val hat = g.content.active.items.first { it.imageKey == AppGraph.DEMO_HAT_IMAGE }.id
        val s = g.profiles.state(g.profileId)
        assertTrue(hat in s.owned)
        assertEquals(listOf(null), s.placed.filter { it.itemId == hat }.map { it.position })   // 단계별 머리 자리를 따른다
        g.ensureStarterCharacter()
        assertEquals(1, g.profiles.state(g.profileId).placed.count { it.itemId == hat })
    }

    @Test fun `시연 모드 꺼짐 - 산타 모자 없음`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur")
        assertTrue(g.profiles.state(g.profileId).owned.isEmpty())
    }

    @Test fun `별명은 자동으로 채우지 않는다 - 홈 이름 짓기 팝업이 정한다`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur", defaultAlias = "차곡")
        assertEquals("", g.profiles.progress(g.profileId, selected(g)).alias)
        val home = HomeModel({ g.profileId }, g.profiles, g.growth, content = { g.content.active })
        home.refresh(); assertTrue(home.state.value.needsName)
        home.setAlias("  붕붕이 ")
        assertEquals("붕붕이", g.profiles.progress(g.profileId, selected(g)).alias)
        assertFalse(home.state.value.needsName); assertEquals("붕붕이", home.state.value.characterName)
    }

    @Test fun `시연 모드 꺼짐 - 1단계`() {
        val g = AppGraph(defaultCharacterAsset = "grandeur")
        assertEquals(1, g.profiles.progress(g.profileId, selected(g)).stage)
    }
}

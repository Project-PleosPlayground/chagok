package ai.safetypoc.app.ui.home

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.ui.stage.StageItemView
import ai.safetypoc.app.ui.stage.StageLayout
import core.item.ItemCategory
import core.item.ItemId
import core.item.ItemSlot
import core.item.StageItem
import core.item.StagePoint
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeModelTest {
    private val graph = AppGraph()
    private val model = HomeModel({ graph.profileId }, graph.profiles, graph.growth, content = { graph.content.active })

    @Test fun `처음 실행하면 첫 캐릭터, 1단계, 포인트 0, 아이템 없음`() {
        model.refresh()
        val s = model.state.value
        assertEquals(graph.content.active.characters.first().name, s.characterName)
        assertEquals(1, s.stage); assertEquals(0, s.coins); assertEquals(emptyList<StageItemView>(), s.placed)
    }

    @Test fun `별칭, 경험치, 포인트, 저장한 스테이지를 놓은 순서와 위치로 보여 준다`() {
        val pid = graph.profileId
        val selected = graph.profiles.state(pid).selected!!
        graph.profiles.saveProgress(graph.profiles.progress(pid, selected).copy(alias = "붕붕", xp = 150, stage = graph.growth.stageFor(150)))
        var st = graph.profiles.state(pid).copy(coins = 50, owned = setOf(ItemId(1), ItemId(6)))
        st = graph.wallet.saveStage(st, listOf(StageItem(ItemId(6), StagePoint(10f, 20f)), StageItem(ItemId(1))))
        graph.profiles.saveState(st)
        model.refresh()
        val s = model.state.value
        assertEquals("붕붕", s.characterName); assertEquals(2, s.stage); assertEquals(150, s.xp); assertEquals(50, s.coins)
        assertEquals(
            listOf(
                StageItemView(ItemId(6), ItemCategory.PET, "주작 Lv1", "Pet/Bird/Bird_Lv1", StagePoint(10f, 20f)),
                StageItemView(ItemId(1), ItemCategory.PET, "현무 Lv1", "Pet/Turtle/Turtle_Lv1", StageLayout.GROUND_PET_POINT),
            ),
            s.placed,
        )
    }
}

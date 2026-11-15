package ai.safetypoc.app.ui.decorate

import ai.safetypoc.app.AppGraph
import ai.safetypoc.app.ui.stage.StageLayout
import core.item.ItemCategory
import core.item.ItemId
import core.item.ItemSlot
import core.item.StageItem
import core.item.StagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 08 "상점", "개별 아이템 페이지", "스테이지 배치" */
class ShopModelTest {
    private val graph = AppGraph()
    private val pid = graph.profileId
    private var driving = false
    private var changed = 0
    private var left = 0
    private var wentToMissions = 0
    private val model = ShopModel(
        { pid }, graph.profiles, graph.wallet, content = { graph.content.active },
        driving = { driving }, onChanged = { changed++ },
        onLeave = { left++ }, onGoMissions = { wentToMissions++ },
    )
    private val turtle = ItemId(1)      // 현무 Lv1, 200포인트
    private val bird = ItemId(6)        // 주작 Lv1
    private val haechi = ItemId(11)     // 해태 Lv1

    private fun profile() = graph.profiles.state(pid)
    private fun setProfile(coins: Int = 0, owned: Set<ItemId> = emptySet()) =
        graph.profiles.saveState(profile().copy(coins = coins, owned = owned))
    private fun s() = model.state.value
    private fun stageIds() = s().stage.map { it.id }

    @Before fun enter() = model.enter()

    @Test fun `펫·소품·배경 탭이 모두 열리고, 가진 아이템이 앞, 그 안에서 이름순`() {
        setProfile(owned = setOf(haechi, bird)); model.enter()
        assertEquals(listOf(true, true, true), s().tabs.map { it.enabled })
        val slots = s().slots
        assertEquals(listOf(bird, haechi), slots.take(2).map { it.id })
        assertTrue(slots.take(2).all { it.owned }); assertTrue(slots.drop(2).none { it.owned })
        assertEquals(slots.drop(2).map { it.name }.sorted(), slots.drop(2).map { it.name })
        assertEquals(graph.content.active.items.count { it.category == ItemCategory.PET }, slots.size)
        model.selectTab(ItemCategory.PROP)
        assertEquals(ItemCategory.PROP, s().selected)
        assertEquals(graph.content.active.items.count { it.category == ItemCategory.PROP }, s().slots.size)
        model.selectTab(ItemCategory.BACKGROUND)
        assertEquals(graph.content.active.items.count { it.category == ItemCategory.BACKGROUND }, s().slots.size)
    }

    @Test fun `열지 않은 종류의 탭은 누를 수 없다`() {
        val petOnly = ShopModel({ graph.profileId }, graph.profiles, graph.wallet, content = { graph.content.active }, driving = { false }, enabledCategories = setOf(ItemCategory.PET))
        petOnly.enter()
        petOnly.selectTab(ItemCategory.PROP)
        assertEquals(ItemCategory.PET, petOnly.state.value.selected)
    }

    @Test fun `가진 슬롯을 누르면 선택되고 스테이지에 놓인다, 다시 누르면 빠진다, 저장 전에는 프로필이 그대로다`() {
        setProfile(owned = setOf(turtle)); model.enter()
        model.tapSlot(turtle)
        assertTrue(s().slots.first { it.id == turtle }.selected)
        assertEquals(listOf(turtle), stageIds())
        assertEquals(StageLayout.GROUND_PET_POINT, s().stage.single().position)
        assertTrue(s().unsaved); assertTrue(profile().placed.isEmpty())
        model.tapSlot(turtle)
        assertFalse(s().slots.first { it.id == turtle }.selected)
        assertTrue(s().stage.isEmpty()); assertFalse(s().unsaved)
        assertEquals(0, changed)
    }

    @Test fun `여러 개 놓고, 새로 놓은 것이 맨 위다`() {
        setProfile(owned = setOf(turtle, bird, haechi)); model.enter()
        model.tapSlot(bird); model.tapSlot(turtle); model.tapSlot(haechi)
        assertEquals(listOf(bird, turtle, haechi), stageIds())
    }

    @Test fun `옮긴 아이템은 그 위치로 가고 맨 위로 온다, 스테이지 밖으로는 나가지 않는다`() {
        setProfile(owned = setOf(turtle, bird)); model.enter()
        model.tapSlot(turtle); model.tapSlot(bird)
        model.moveItem(turtle, StagePoint(300f, 900f))
        assertEquals(listOf(bird, turtle), stageIds())
        assertEquals(StagePoint(300f, 900f), s().stage.last().position)
        model.moveItem(turtle, StagePoint(-50f, 99_999f))
        assertEquals(StagePoint(0f, StageLayout.HEIGHT), s().stage.last().position)
    }

    @Test fun `저장하면 프로필에 남고, 저장하지 않고 상점을 나갔다 오면 저장한 배치로 돌아간다`() {
        setProfile(owned = setOf(turtle, bird)); model.enter()
        model.tapSlot(turtle); model.moveItem(turtle, StagePoint(300f, 900f))
        model.save()
        assertEquals(listOf(StageItem(turtle, StagePoint(300f, 900f))), profile().placed)
        assertFalse(s().unsaved); assertEquals(1, changed)
        model.tapSlot(bird)
        model.enter()
        assertEquals(listOf(turtle), stageIds()); assertFalse(s().unsaved)
    }

    @Test fun `가지지 않은 슬롯을 누르면 개별 아이템 페이지, 적용하면 미리보기로 맨 위에 놓이고 닫으면 풀린다`() {
        setProfile(owned = setOf(bird)); model.enter()
        model.tapSlot(bird)
        model.tapSlot(turtle)
        assertEquals(turtle, s().page?.item?.id); assertFalse(s().page!!.applied)
        model.apply()
        assertTrue(s().page!!.applied)
        assertEquals(listOf(bird, turtle), stageIds()); assertTrue(s().stage.last().preview)
        model.moveItem(turtle, StagePoint(100f, 100f))
        assertEquals(StagePoint(100f, 100f), s().stage.last().position)
        model.moveItem(bird, StagePoint(200f, 200f))
        assertEquals("미리보기 뒤에 옮긴 아이템이 맨 위", listOf(turtle, bird), stageIds())
        model.closePage()
        assertNull(s().page); assertEquals(listOf(bird), stageIds())
    }

    @Test fun `적용한 미리보기는 저장하지 않는다`() {
        setProfile(owned = setOf(bird)); model.enter()
        model.tapSlot(bird); model.tapSlot(turtle); model.apply()
        model.save()
        assertEquals(listOf(StageItem(bird)), profile().placed)
        assertEquals("저장해도 미리보기를 포함한 순서는 그대로", listOf(bird, turtle), stageIds())
        model.moveItem(bird, StagePoint(50f, 50f)); model.save()
        assertEquals(listOf(turtle, bird), stageIds()); assertTrue(s().stage.first().preview)
    }

    @Test fun `구매하면 포인트가 빠지고 가진 아이템이 된다, 적용은 풀리고 목록으로 돌아가며 스테이지에 놓이지 않는다`() {
        setProfile(coins = 500); model.enter()
        model.tapSlot(turtle); model.apply()
        model.buy()
        assertEquals(300, profile().coins); assertTrue(turtle in profile().owned)
        assertNull(s().page); assertTrue(s().stage.isEmpty())
        assertEquals(300, s().points)
        val slot = s().slots.first()
        assertEquals(turtle, slot.id); assertTrue(slot.owned); assertFalse(slot.selected)
        assertEquals(1, changed)
    }

    @Test fun `포인트가 모자라면 팝업으로 알리고 아무것도 바꾸지 않는다`() {
        setProfile(coins = 150); model.enter()
        model.tapSlot(turtle)
        model.buy()
        val d = s().dialog as ShopDialog.NotEnough
        assertEquals(200, d.item.price); assertEquals(150, d.points)
        assertEquals(150, profile().coins); assertFalse(turtle in profile().owned)
        assertEquals(turtle, s().page?.item?.id)
        model.dismissDialog()
        assertNull(s().dialog); assertEquals(0, changed)
    }

    @Test fun `주행 중에는 놓기·적용·구매·옮기기·저장을 하지 않고 알린다`() {
        setProfile(coins = 500, owned = setOf(bird)); model.enter()
        model.tapSlot(bird)
        driving = true
        model.tapSlot(bird)
        assertEquals(ShopDialog.Driving, s().dialog)
        model.dismissDialog()
        model.moveItem(bird, StagePoint(1f, 1f)); model.save()
        assertEquals(listOf(bird), stageIds()); assertEquals(StageLayout.slotPoint(ItemSlot.BOTTOM_RIGHT), s().stage.single().position)
        model.dismissDialog(); model.tapSlot(turtle)
        assertEquals("페이지를 여는 것은 배치를 바꾸지 않아 주행 중에도 연다", turtle, s().page?.item?.id); assertNull(s().dialog)
        model.apply(); model.buy()
        assertEquals(ShopDialog.Driving, s().dialog)
        assertFalse(s().page!!.applied); assertEquals(500, profile().coins); assertTrue(profile().placed.isEmpty())
        assertEquals(0, changed)
    }

    @Test fun `구매하기를 누르면 포인트가 넉넉할 때 살지 묻고, 구매하면 팝업이 닫힌다`() {
        setProfile(coins = 500); model.enter()
        model.tapSlot(turtle)
        model.requestBuy()
        assertEquals(turtle, (s().dialog as ShopDialog.ConfirmBuy).item.id)
        assertEquals(500, profile().coins)
        model.buy()
        assertNull(s().dialog); assertNull(s().page)
        assertEquals(300, profile().coins); assertTrue(turtle in profile().owned)
    }

    @Test fun `구매하기를 누르면 포인트가 모자랄 때 살지 묻지 않고 포인트 부족을 알린다`() {
        setProfile(coins = 150); model.enter()
        model.tapSlot(turtle)
        model.requestBuy()
        val d = s().dialog as ShopDialog.NotEnough
        assertEquals(turtle, d.item.id); assertEquals(150, d.points)
        assertEquals(150, profile().coins); assertFalse(turtle in profile().owned)
    }

    @Test fun `살지 묻는 팝업을 닫으면 아무것도 바꾸지 않고 개별 아이템 페이지에 남는다`() {
        setProfile(coins = 500); model.enter()
        model.tapSlot(turtle); model.requestBuy()
        model.dismissDialog()
        assertNull(s().dialog); assertEquals(turtle, s().page?.item?.id)
        assertEquals(500, profile().coins); assertEquals(0, changed)
    }

    @Test fun `뒤로는 개별 아이템 페이지가 열려 있으면 페이지만 닫고, 목록이면 상점을 나간다`() {
        model.tapSlot(turtle); model.apply()
        model.back()
        assertNull(s().page); assertTrue(s().stage.isEmpty()); assertEquals(0, left)
        model.back()
        assertEquals(1, left)
    }

    @Test fun `미션하러 가기는 팝업과 개별 아이템 페이지를 닫고 미션 탭으로 간다`() {
        setProfile(coins = 150); model.enter()
        model.tapSlot(turtle); model.apply(); model.requestBuy()
        model.goToMissions()
        assertNull(s().dialog); assertNull(s().page); assertTrue(s().stage.isEmpty())
        assertEquals(1, wentToMissions)
    }

    @Test fun `주행 중에는 구매하기를 눌러도 살지 묻지 않고 알린다`() {
        setProfile(coins = 500); model.enter()
        model.tapSlot(turtle)
        driving = true
        model.requestBuy()
        assertEquals(ShopDialog.Driving, s().dialog)
    }

    @Test fun `저장할 변경은 가진 아이템의 배치가 저장된 것과 다를 때만 있다, 미리보기는 세지 않는다`() {
        setProfile(owned = setOf(bird)); model.enter()
        assertFalse("들어온 직후", s().unsaved)
        model.tapSlot(turtle); model.apply(); model.moveItem(turtle, StagePoint(100f, 100f))
        assertFalse("가지지 않은 아이템만 적용하고 옮김", s().unsaved)
        model.closePage(); model.tapSlot(bird)
        assertTrue("가진 아이템을 놓음", s().unsaved)
        model.tapSlot(turtle)
        assertTrue("저장할 변경이 있으면 개별 아이템 페이지에서도 있다", s().unsaved)
        model.closePage(); model.tapSlot(bird)
        assertFalse("다시 빼서 저장된 배치와 같아짐", s().unsaved)
        model.tapSlot(bird); model.save()
        assertFalse("저장함", s().unsaved)
        model.moveItem(bird, StagePoint(50f, 50f))
        assertTrue("가진 아이템을 옮김", s().unsaved)
    }
}

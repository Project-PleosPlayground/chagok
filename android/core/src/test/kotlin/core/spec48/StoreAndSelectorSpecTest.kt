package core.spec48

import core.character.CharacterDef
import core.character.CharacterId
import core.character.CharacterProgress
import core.character.CharacterSelector
import core.character.GrowthModule
import core.character.InMemoryProfileStore
import core.character.ProfileState
import core.character.Wallet
import core.item.ItemCategory
import core.item.ItemDef
import core.item.ItemId
import core.item.StageItem
import core.item.StagePoint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** 결정 1~4, 8, 9, 11 */
class StoreAndSelectorSpecTest {
    private val chars = listOf(
        CharacterDef(CharacterId(1), "아반떼", "avante"),
        CharacterDef(CharacterId(2), "캐스퍼", "casper"),
    )

    // ---- 결정 8: 저장소 ----
    @Test fun `state 기본값`() {
        val s = InMemoryProfileStore().state("p1")
        assertEquals("p1", s.profileId)
        assertNull(s.selected)
        assertEquals(0, s.coins)
        assertTrue(s.owned.isEmpty())
        assertTrue(s.placed.isEmpty())
    }

    @Test fun `saveState 후 state 는 저장한 값`() {
        val store = InMemoryProfileStore()
        val s = ProfileState("p1", CharacterId(2), 30, setOf(ItemId(1)), listOf(StageItem(ItemId(1), StagePoint(3f, 4f))))
        store.saveState(s)
        assertEquals(s, store.state("p1"))
    }

    @Test fun `progress 기본값`() {
        val pr = InMemoryProfileStore().progress("p1", CharacterId(3))
        assertEquals("p1", pr.profileId)
        assertEquals(CharacterId(3), pr.characterId)
        assertEquals("", pr.alias)
        assertEquals(0, pr.xp)
        assertEquals(1, pr.stage)
    }

    @Test fun `progress 를 읽기만 하면 저장되지 않는다`() {
        val store = InMemoryProfileStore()
        store.progress("p1", CharacterId(1))
        store.progress("p1", CharacterId(2))
        assertTrue(store.progresses("p1").isEmpty())
    }

    @Test fun `progresses 는 그 프로필의 저장된 것만`() {
        val store = InMemoryProfileStore()
        val a = CharacterProgress("p1", CharacterId(1), "쌩쌩이", 120, 2)
        val b = CharacterProgress("p1", CharacterId(2), "", 10, 1)
        val c = CharacterProgress("p2", CharacterId(1), "남의것", 999, 4)
        store.saveProgress(a); store.saveProgress(b); store.saveProgress(c)
        assertEquals(setOf(a, b), store.progresses("p1").toSet())
        assertEquals(listOf(c), store.progresses("p2"))
        assertEquals(a, store.progress("p1", CharacterId(1)))
        assertEquals(c, store.progress("p2", CharacterId(1)))
    }

    @Test fun `같은 프로필 같은 캐릭터를 다시 저장하면 덮어쓴다`() {
        val store = InMemoryProfileStore()
        store.saveProgress(CharacterProgress("p1", CharacterId(1), xp = 10))
        store.saveProgress(CharacterProgress("p1", CharacterId(1), xp = 20))
        assertEquals(1, store.progresses("p1").size)
        assertEquals(20, store.progress("p1", CharacterId(1)).xp)
    }

    @Test fun `프로필끼리 state 는 독립`() {
        val store = InMemoryProfileStore()
        store.saveState(ProfileState("p1", CharacterId(1), coins = 50))
        val s2 = store.state("p2")
        assertNull(s2.selected)
        assertEquals(0, s2.coins)
        assertEquals(50, store.state("p1").coins)
    }

    // ---- 결정 3: 별명은 (프로필, 캐릭터)별 ----
    @Test fun `별명은 캐릭터마다 따로`() {
        val store = InMemoryProfileStore()
        store.saveProgress(store.progress("p1", CharacterId(1)).copy(alias = "하나"))
        assertEquals("하나", store.progress("p1", CharacterId(1)).alias)
        assertEquals("", store.progress("p1", CharacterId(2)).alias)
        assertEquals("", store.progress("p2", CharacterId(1)).alias)
    }

    // ---- 결정 9: 선택 ----
    @Test fun `없는 캐릭터 선택은 null`() {
        val sel = CharacterSelector { chars }
        assertNull(sel.select(ProfileState("p1"), CharacterId(99)))
        assertNull(sel.find(CharacterId(99)))
    }

    @Test fun `있는 캐릭터 선택은 selected 만 바뀐다`() {
        val sel = CharacterSelector { chars }
        val s = ProfileState("p1", CharacterId(1), 77, setOf(ItemId(3)), listOf(StageItem(ItemId(3))))
        val r = sel.select(s, CharacterId(2))
        assertEquals(s.copy(selected = CharacterId(2)), r)
        assertEquals(chars[1], sel.find(CharacterId(2)))
    }

    @Test fun `선택은 목록 순서와 무관하게 id 로 찾는다`() {
        val sel = CharacterSelector { chars.reversed() }
        assertEquals("avante", sel.find(CharacterId(1))?.assetKey)
    }

    @Test fun `캐릭터 목록을 매번 새로 읽는다`() {
        var list = chars
        val sel = CharacterSelector { list }
        assertNull(sel.select(ProfileState("p1"), CharacterId(3)))
        list = chars + CharacterDef(CharacterId(3), "그랜저", "grandeur")
        assertEquals(CharacterId(3), sel.select(ProfileState("p1"), CharacterId(3))?.selected)
    }

    // ---- 결정 1, 4: 아이템은 프로필 것, 1개만 ----
    private val catalog = listOf(
        ItemDef(ItemId(7), ItemCategory.PROP, nameKey = "item_7_name", imageKey = "kite", price = 30),
        ItemDef(ItemId(3), ItemCategory.PET, nameKey = "item_3_name", imageKey = "turtle_lv1", price = 20),
    )

    @Test fun `아이템은 목록 순서와 무관하게 id 로 찾는다`() {
        val w = Wallet(catalog)
        assertEquals("item_3_name", w.item(ItemId(3))?.nameKey)
        assertEquals("item_7_name", w.item(ItemId(7))?.nameKey)
        assertNull(w.item(ItemId(1)))
    }

    @Test fun `같은 아이템은 두 번 살 수 없다`() {
        val w = Wallet(catalog)
        val s0 = w.earn(ProfileState("p1"), 100)
        val ok = w.buy(s0, ItemId(7))
        assertTrue(ok is Wallet.BuyResult.Ok)
        val s1 = (ok as Wallet.BuyResult.Ok).state
        assertEquals(70, s1.coins)
        assertEquals(setOf(ItemId(7)), s1.owned)
        assertEquals(Wallet.BuyResult.AlreadyOwned, w.buy(s1, ItemId(7)))
    }

    @Test fun `없는 아이템 구매는 Unknown`() {
        val w = Wallet(catalog)
        assertEquals(Wallet.BuyResult.Unknown, w.buy(w.earn(ProfileState("p1"), 100), ItemId(1)))
    }

    @Test fun `캐릭터를 바꿔도 코인 보유 스테이지 배치는 그대로`() {
        val w = Wallet(catalog)
        val sel = CharacterSelector { chars }
        var s = w.earn(ProfileState("p1", selected = CharacterId(1)), 100)
        s = (w.buy(s, ItemId(7)) as Wallet.BuyResult.Ok).state
        s = w.saveStage(s, listOf(StageItem(ItemId(7))))
        val switched = sel.select(s, CharacterId(2))!!
        assertEquals(s.coins, switched.coins)
        assertEquals(s.owned, switched.owned)
        assertEquals(listOf(StageItem(ItemId(7))), switched.placed)
    }

    // ---- 결정 11: 단계 경계 ----
    @Test fun `단계 경계값`() {
        val g = GrowthModule()
        val cases = mapOf(0 to 1, 99 to 1, 100 to 2, 299 to 2, 300 to 3, 599 to 3, 600 to 4, 999 to 4, 1000 to 5, 100_000 to 5)
        cases.forEach { (xp, stage) -> assertEquals(stage, g.stageFor(xp), "xp=$xp") }
    }

    @Test fun `addXp 는 그 캐릭터 진행만 바꾸고 단계 상승을 알린다`() {
        val g = GrowthModule()
        val before = CharacterProgress("p1", CharacterId(4), "별명", 90, 1)
        val r = g.addXp(before, 15)
        assertEquals(15, r.gained)
        assertEquals(105, r.state.xp)
        assertEquals(2, r.state.stage)
        assertEquals(2, r.leveledUpTo)
        assertEquals("p1", r.state.profileId)
        assertEquals(CharacterId(4), r.state.characterId)
        assertEquals("별명", r.state.alias)
    }

    @Test fun `addXp 단계가 안 바뀌면 leveledUpTo null`() {
        val r = GrowthModule().addXp(CharacterProgress("p1", CharacterId(1), xp = 10), 50)
        assertEquals(60, r.state.xp)
        assertEquals(1, r.state.stage)
        assertNull(r.leveledUpTo)
    }

    @Test fun `addXp 여러 단계를 한 번에 넘으면 최종 단계`() {
        val r = GrowthModule().addXp(CharacterProgress("p1", CharacterId(1)), 650)
        assertEquals(4, r.state.stage)
        assertEquals(4, r.leveledUpTo)
    }
}

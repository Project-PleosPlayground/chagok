package core.character

import core.content.DefaultItems
import core.item.ItemId
import core.item.StageItem
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GrowthAndWalletTest {
    @Test fun `경험치 구간과 레벨업`() {
        val g = GrowthModule()
        assertEquals(1, g.stageFor(0)); assertEquals(2, g.stageFor(100)); assertEquals(5, g.stageFor(1000))
        val r = g.addXp(CharacterProgress("p", CharacterId(1), xp = 90, stage = 1), 20)
        assertEquals(110, r.state.xp); assertEquals(2, r.state.stage); assertEquals(2, r.leveledUpTo)
        assertNull(g.addXp(r.state, 10).leveledUpTo)
        assertEquals(0.5f, g.progress(200), 0.001f)   // 100~300 사이 중간
        assertEquals(1f, g.progress(5000))
    }
    @Test fun `단계별 최소 경험치`() {
        val g = GrowthModule()
        assertEquals(0, g.minXp(1)); assertEquals(300, g.minXp(3)); assertEquals(1000, g.minXp(5))
        assertEquals(3, g.stageFor(g.minXp(3)))
        assertEquals(0, g.minXp(0)); assertEquals(1000, g.minXp(9))
    }

    @Test fun `포인트로 사고 스테이지에 놓아 저장`() {
        val w = Wallet(DefaultItems.all)
        var s = ProfileState("p", coins = 100)
        assertTrue(w.buy(s, ItemId(1)) is Wallet.BuyResult.NotEnough)          // 200포인트
        s = (w.buy(s.copy(coins = 200), ItemId(1)) as Wallet.BuyResult.Ok).state
        assertEquals(0, s.coins); assertTrue(ItemId(1) in s.owned)
        assertTrue(w.buy(s, ItemId(1)) is Wallet.BuyResult.AlreadyOwned)
        s = w.saveStage(s, listOf(StageItem(ItemId(2)), StageItem(ItemId(1))))   // 안 가진 2 는 빠진다
        assertEquals(listOf(StageItem(ItemId(1))), s.placed)
    }

    @Test fun `장소 선물은 살 수 없고, 반경 안에 들르면 한 번만 받는다`() {
        val w = Wallet(core.content.BundledContent.pack.items)
        var s = ProfileState(profileId = "p", coins = 10_000)
        val namsan = ItemId(26)
        assertEquals(Wallet.BuyResult.PlaceOnly, w.buy(s, namsan))
        val (none, noGift) = w.visit(s, listOf(37.5045 to 127.0490))   // 선릉역: 아무 장소도 아님
        assertTrue(noGift.isEmpty()); assertEquals(s, none)
        val (got, gifts) = w.visit(s, listOf(37.5045 to 127.0490, 37.5515 to 126.9880))   // 남산 정상 근처
        assertEquals(listOf(namsan), gifts.map { it.id }); assertTrue(namsan in got.owned); assertEquals(10_000, got.coins)
        s = got
        assertTrue(w.visit(s, listOf(37.5515 to 126.9880)).second.isEmpty(), "이미 받은 선물은 다시 주지 않는다")
    }
}

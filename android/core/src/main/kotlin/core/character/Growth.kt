package core.character

import core.ai.Hotspots
import core.item.ItemDef
import core.item.ItemId
import core.item.StageItem
import core.item.StageRules
import core.saturatingAdd

/** 경험치 → 단계. 점수 배수 없음(스펙 원칙). 구간은 가안 (#14). */
class GrowthModule(private val stageXp: IntArray = intArrayOf(0, 100, 300, 600, 1000)) {
    val maxStage: Int get() = stageXp.size
    fun stageFor(xp: Int): Int = stageXp.indexOfLast { xp >= it } + 1
    /** [stage] 단계가 되는 최소 경험치. 범위를 벗어나면 1단계·최고 단계로 본다 */
    fun minXp(stage: Int): Int = stageXp[stage.coerceIn(1, maxStage) - 1]
    /** 다음 단계까지 진행도 0~1. 최고 단계면 1 */
    fun progress(xp: Int): Float {
        val s = stageFor(xp); if (s >= maxStage) return 1f
        val lo = stageXp[s - 1]; val hi = stageXp[s]
        return ((xp - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
    }
    /** [gained] 는 실제로 바뀐 경험치(깎였으면 음수). 단계가 바뀌었으면 [leveledUpTo] 또는 [leveledDownTo] */
    data class XpResult(val state: CharacterProgress, val gained: Int, val leveledUpTo: Int?, val leveledDownTo: Int? = null)
    fun addXp(state: CharacterProgress, xp: Int): XpResult {
        val newXp = saturatingAdd(state.xp, xp).coerceAtLeast(0)
        val newStage = stageFor(newXp)
        return XpResult(state.copy(xp = newXp, stage = newStage), xp, if (newStage > state.stage) newStage else null,
            if (newStage < state.stage) newStage else null)
    }
    /** 경험치를 깎는다. 0 밑으로는 내려가지 않고, 단계가 내려갈 수 있다 (04) */
    fun removeXp(state: CharacterProgress, xp: Int): XpResult {
        val r = addXp(state, -xp.coerceAtLeast(0))
        return r.copy(gained = r.state.xp - state.xp)
    }
}

/**
 * 코인·아이템. 미션 보상으로 받고 상점에서 쓴다. 프로필 상태를 바꾼다(모든 캐릭터 공용, #48).
 * 아이템 목록은 부를 때마다 [items] 에서 읽는다. :app 은 { ContentState.active.items } 를 넘겨 새 묶음을 바로 반영한다 (#31).
 */
class Wallet(private val items: () -> List<ItemDef>) {
    constructor(catalog: List<ItemDef>) : this({ catalog })
    fun item(id: ItemId) = items().firstOrNull { it.id == id }
    fun earn(state: ProfileState, coins: Int) = state.copy(coins = saturatingAdd(state.coins, coins))
    /** 코인을 깎는다. 0 밑으로는 내려가지 않는다 (미션 취소·실패 패널티, 07) */
    fun deduct(state: ProfileState, coins: Int) = state.copy(coins = (state.coins - coins.coerceAtLeast(0)).coerceAtLeast(0))
    sealed interface BuyResult {
        data class Ok(val state: ProfileState) : BuyResult
        data object NotEnough : BuyResult
        data object AlreadyOwned : BuyResult
        data object Unknown : BuyResult
        /** 장소 선물 아이템. 코인으로 살 수 없다 */
        data object PlaceOnly : BuyResult
    }
    fun buy(state: ProfileState, itemId: ItemId): BuyResult {
        val it = item(itemId) ?: return BuyResult.Unknown
        if (itemId in state.owned) return BuyResult.AlreadyOwned
        if (it.place != null) return BuyResult.PlaceOnly
        if (state.coins < it.price) return BuyResult.NotEnough
        return BuyResult.Ok(state.copy(coins = state.coins - it.price, owned = state.owned + itemId))
    }

    /**
     * 장소 선물 (11/15): 이번 주행에서 지난 좌표들([points], 사건 자리·도착 자리) 중 하나라도 장소 반경 안이면 그 아이템을 준다.
     * 이미 가진 것은 다시 주지 않는다. 받은 아이템과 바뀐 상태를 돌려준다
     */
    fun visit(state: ProfileState, points: List<Pair<Double, Double>>): Pair<ProfileState, List<ItemDef>> {
        val got = items().filter { def ->
            val p = def.place ?: return@filter false
            def.id !in state.owned && points.any { (lat, lng) -> Hotspots.distanceM(lat, lng, p.lat, p.lng) <= p.radiusM }
        }
        return (if (got.isEmpty()) state else state.copy(owned = state.owned + got.map { it.id })) to got
    }

    /** 스테이지 배치를 저장한다(상점의 저장 버튼, 08 "스테이지 배치"). 저장할 수 없는 것은 [StageRules.sanitize] 로 뺀다 */
    fun saveStage(state: ProfileState, stage: List<StageItem>): ProfileState =
        state.copy(placed = StageRules.sanitize(stage, state.owned, ::item))
}

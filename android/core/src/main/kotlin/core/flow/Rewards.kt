package core.flow

import core.character.CharacterProgress
import core.character.GrowthModule
import core.character.ProfileState
import core.character.ProfileStore
import core.character.Wallet

/** 코인·경험치를 프로필과 지금 고른 캐릭터에 주거나 깎고 저장한다. 코인은 프로필에, 경험치는 캐릭터에 (#48) */
class Rewards(
    private val profiles: ProfileStore,
    private val growth: GrowthModule,
    private val wallet: Wallet,
) {
    /** 반영한 결과. [xp] 는 실제로 바뀐 경험치. 캐릭터를 고르지 않은 프로필이면 [character] 는 null 이고 경험치는 바뀌지 않는다 */
    data class Applied(
        val profile: ProfileState,
        val character: CharacterProgress?,
        val coins: Int,
        val xp: Int,
        val leveledUpTo: Int?,
        val leveledDownTo: Int?,
    )

    fun give(profileId: String, coins: Int, xp: Int): Applied = apply(profileId, coins, xp, take = false)

    /** 바꾸지도 저장하지도 않고 지금 상태만 읽는다 */
    fun current(profileId: String): Applied {
        val profile = profiles.state(profileId)
        return Applied(profile, profile.selected?.let { profiles.progress(profileId, it) }, 0, 0, null, null)
    }

    /** 깎는다. 코인·경험치 모두 0 밑으로는 내려가지 않는다 */
    fun take(profileId: String, coins: Int, xp: Int): Applied = apply(profileId, coins, xp, take = true)

    private fun apply(profileId: String, coins: Int, xp: Int, take: Boolean): Applied {
        val before = profiles.state(profileId)
        val profile = if (take) wallet.deduct(before, coins) else wallet.earn(before, coins)
        profiles.saveState(profile)
        val character = profile.selected?.let { profiles.progress(profileId, it) }
            ?: return Applied(profile, null, profile.coins - before.coins, 0, null, null)
        val r = if (take) growth.removeXp(character, xp) else growth.addXp(character, xp)
        profiles.saveProgress(r.state)
        return Applied(profile, r.state, profile.coins - before.coins, r.state.xp - character.xp, r.leveledUpTo, r.leveledDownTo)
    }
}

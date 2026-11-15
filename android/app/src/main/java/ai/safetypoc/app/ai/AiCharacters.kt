package ai.safetypoc.app.ai

import core.ai.AiCharacter
import core.character.ProfileStore
import core.content.ContentPack

/** AI 리포트에 넣는 캐릭터 정보: 별칭(없으면 차종 이름, 그것도 없으면 기본 이름), 단계, 말투 설명. 개인정보 없음 */
object AiCharacters {
    /** [임시] 캐릭터 말투. 기획 문구가 정해지면 콘텐츠 텍스트로 옮긴다 */
    const val PERSONA = "차 안에 사는 작은 반려 캐릭터. 반말, 짧게, 운전자를 응원한다"
    const val DEFAULT_NAME = "친구"

    fun of(profiles: ProfileStore, profileId: String, pack: ContentPack): AiCharacter {
        val state = profiles.state(profileId)
        val selected = state.selected
        val def = selected?.let { id -> pack.characters.firstOrNull { it.id == id } }
        val progress = selected?.let { profiles.progress(profileId, it) }
        val name = progress?.alias?.ifBlank { null } ?: def?.name ?: DEFAULT_NAME
        return AiCharacter(name, progress?.stage ?: 1, PERSONA)
    }
}

package core.character

import kotlinx.serialization.Serializable

/**
 * 캐릭터 종류 번호 (#48). 차종 하나에 하나. 목록 순서와 무관하다.
 * 한 번 붙이면 바꾸지 않고, 지운 번호는 다시 쓰지 않는다. 사용자 저장 데이터(고른 캐릭터, 캐릭터별 성장)가 이 번호를 가리키기 때문이다.
 * JSON 에는 정수 하나로 쓴다.
 */
@JvmInline
@Serializable
value class CharacterId(val value: Int) {
    override fun toString() = value.toString()
}

/**
 * 캐릭터 종류 하나. 목록은 콘텐츠 묶음(ContentPack.characters)에 있다.
 * [name] 은 기획이 정한 이름이다. 사용자가 짓는 별칭은 [CharacterProgress.alias].
 */
@Serializable
data class CharacterDef(
    val id: CharacterId,
    val name: String,
    /** 그림 폴더 이름. assets/character/<assetKey>/ (06 "폴더 구조"). 영문 소문자·숫자·밑줄 */
    val assetKey: String,
)

/**
 * 캐릭터 고르기 (#48). 캐릭터 목록은 부를 때마다 [characters] 에서 읽는다. :app 은 { ContentState.active.characters } 를 넘긴다.
 */
class CharacterSelector(private val characters: () -> List<CharacterDef>) {
    fun find(id: CharacterId): CharacterDef? = characters().firstOrNull { it.id == id }

    /** 목록에 없는 캐릭터면 null */
    fun select(state: ProfileState, id: CharacterId): ProfileState? =
        if (find(id) == null) null else state.copy(selected = id)
}

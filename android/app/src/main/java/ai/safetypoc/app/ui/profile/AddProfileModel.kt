package ai.safetypoc.app.ui.profile

import core.character.CharacterDef
import core.character.CharacterId
import core.character.Profile
import core.character.ProfileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/** 프로필 이름 규칙 (영역사양_프로필추가화면 4): 최대 12자, 띄어쓰기·이모지 포함, 특수문자 가능 */
object ProfileName {
    const val MAX_LENGTH = 12

    /** 글자 하나(확장 자소 묶음). JDK 17 의 BreakIterator 는 가족 이모지(ZWJ)를 여러 자로 세서 정규식 \X 를 쓴다 (11/15) */
    private val GRAPHEME = Regex("\\X")

    /** 사람이 보는 글자 수. 이모지 하나(여러 코드로 된 것 포함)를 1자로 센다 */
    fun length(text: String): Int = GRAPHEME.findAll(text).count()

    /** 앞에서 [MAX_LENGTH]자까지만 남긴다 */
    fun limit(text: String): String =
        GRAPHEME.findAll(text).elementAtOrNull(MAX_LENGTH - 1)?.let { text.substring(0, it.range.last + 1) } ?: text
}

/** car_picker에 놓는 차 한 대 */
data class CarOption(val id: CharacterId, val name: String, val assetKey: String)

/** 프로필 추가·수정 화면이 보여 줄 값. 수정 화면은 추가 화면과 같은 배치에 삭제 버튼이 더 있다 */
data class AddProfileState(
    /** 고를 수 있는 차. 콘텐츠의 캐릭터 순서 */
    val cars: List<CarOption> = emptyList(),
    /** 가운데(선택된) 차의 [cars] 번호 */
    val selected: Int = 0,
    /** 입력한 이름 */
    val name: String = "",
    /** 고치는 프로필. null 이면 새 프로필을 만든다 */
    val editingId: String? = null,
    /** 삭제 확인 팝업을 띄웠는지 */
    val confirmingDelete: Boolean = false,
) {
    /** 수정 화면인지. 삭제 버튼을 보인다 */
    val editing: Boolean get() = editingId != null
    /** 가운데 차. 고를 차가 없으면 null */
    val centerCar: CarOption? get() = carAt(0)
    /** 왼쪽 차(이전). 차가 3대 미만이면 null */
    val leftCar: CarOption? get() = if (cars.size < SIDE_CARS_MIN) null else carAt(-1)
    /** 오른쪽 차(다음). 차가 3대 미만이면 null */
    val rightCar: CarOption? get() = if (cars.size < SIDE_CARS_MIN) null else carAt(1)
    /** 저장할 수 있는지. 이름이 비지 않았고 고른 차가 있을 때 */
    val canSave: Boolean get() = name.isNotBlank() && cars.isNotEmpty()

    private fun carAt(offset: Int): CarOption? = if (cars.isEmpty()) null else cars[(selected + offset).mod(cars.size)]

    private companion object {
        /** 양옆 차를 보이는 최소 대수. 2대면 양옆이 같은 차가 된다 */
        const val SIDE_CARS_MIN = 3
    }
}

/** 프로필 추가·수정 화면에서 하는 동작 */
interface AddProfileActions {
    /** next_button: 다음 차를 가운데로. 마지막 차 다음은 처음 차 */
    fun nextCar()
    /** 좌우로 넘겨 고른 차를 가운데로 (11/15) */
    fun selectCar(index: Int)
    /** 이름 입력. [ProfileName.MAX_LENGTH]자를 넘는 부분은 버린다 */
    fun changeName(text: String)
    /** 지우기 아이콘 */
    fun clearName()
    /** 저장: 프로필을 만들거나(추가) 이름·차를 바꾸고(수정) 프로필 화면으로 */
    fun save()
    /** 취소: 저장하지 않고 프로필 화면으로 */
    fun cancel()
    /** 삭제 버튼: 확인 팝업을 띄운다. 수정 화면에서만 */
    fun delete()
    /** 삭제 팝업의 삭제: 프로필을 지우고 프로필 화면으로 */
    fun confirmDelete()
    /** 삭제 팝업 닫기 */
    fun dismissDelete()
}

/** 프로필 추가·수정 화면을 닫는 길. 화면 이동을 맡은 쪽이 구현한다 */
fun interface AddProfileNavigation {
    /** 프로필 화면으로 돌아간다 */
    fun close()
}

/**
 * 차 고르기와 이름 입력을 갖고 있다가 [save]에서 프로필을 만들거나 고친다.
 * 추가 화면에 들어올 때 [enter], 수정 화면에 들어올 때 [enterEdit]를 부른다.
 * [deleteProfile]은 그 프로필의 데이터(주행 기록·미션 포함)를 지우는 쪽이다.
 */
class AddProfileModel(
    private val profiles: ProfileStore,
    private val characters: () -> List<CharacterDef>,
    private val navigation: AddProfileNavigation,
    private val deleteProfile: (profileId: String) -> Unit = profiles::delete,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** 처음 보여 줄 차종(그림 폴더 이름). 없으면 첫 차 */
    private val defaultAssetKey: String? = null,
) : AddProfileActions {
    private val _state = MutableStateFlow(AddProfileState())
    val state: StateFlow<AddProfileState> = _state.asStateFlow()

    /** 기본 차종을 목록 가운데에 두고 나머지를 양옆에 (좌우로 넘기는 차 고르기에서 처음에 가운데로 뜨게, 11/15) */
    private fun cars(): List<CarOption> {
        val all = characters().map { CarOption(it.id, it.name, it.assetKey) }
        val main = all.firstOrNull { it.assetKey == defaultAssetKey } ?: return all
        val rest = all - main
        return rest.take(rest.size / 2) + main + rest.drop(rest.size / 2)
    }

    /** 기본 차종(없으면 첫 차), 빈 이름으로 시작한다 */
    fun enter() {
        val cars = cars()
        _state.value = AddProfileState(cars = cars, selected = cars.indexOfFirst { it.assetKey == defaultAssetKey }.coerceAtLeast(0))
    }

    /** 그 프로필의 이름과 고른 차로 시작한다. 고른 차가 목록에 없으면 첫 차. 프로필이 없으면 false */
    fun enterEdit(profileId: String): Boolean {
        val profile = profiles.profiles().firstOrNull { it.id == profileId } ?: return false
        val cars = cars()
        val selected = profiles.state(profileId).selected
        _state.value = AddProfileState(
            cars = cars,
            selected = cars.indexOfFirst { it.id == selected }.coerceAtLeast(0),
            name = profile.name,
            editingId = profileId,
        )
        return true
    }

    override fun nextCar() {
        _state.update { if (it.cars.isEmpty()) it else it.copy(selected = (it.selected + 1) % it.cars.size) }
    }

    override fun selectCar(index: Int) {
        _state.update { if (index !in it.cars.indices) it else it.copy(selected = index) }
    }

    override fun changeName(text: String) {
        _state.update { it.copy(name = ProfileName.limit(text)) }
    }

    override fun clearName() {
        _state.update { it.copy(name = "") }
    }

    override fun save() {
        val s = _state.value
        val car = s.centerCar ?: return
        if (!s.canSave) return
        val profile = if (s.editingId != null) {
            profiles.profiles().firstOrNull { it.id == s.editingId }?.copy(name = s.name.trim()) ?: return
        } else {
            val existing = profiles.profiles()
            if (existing.size >= MAX_PROFILES) return
            // 첫 프로필은 기본 프로필 id 로 만든다. 프로필을 만들기 전에 쌓인 주행 기록·미션·캐릭터가 이 프로필 것이 된다 (#105)
            Profile(id = if (existing.isEmpty()) Profile.DEFAULT else newId(), name = s.name.trim(), createdMs = nowMs())
        }
        profiles.upsert(profile)
        profiles.saveState(profiles.state(profile.id).copy(selected = car.id))
        navigation.close()
    }

    override fun cancel() {
        navigation.close()
    }

    override fun delete() {
        _state.update { if (it.editing) it.copy(confirmingDelete = true) else it }
    }

    override fun confirmDelete() {
        val id = _state.value.editingId ?: return
        _state.update { it.copy(confirmingDelete = false) }
        deleteProfile(id)
        navigation.close()
    }

    override fun dismissDelete() {
        _state.update { it.copy(confirmingDelete = false) }
    }
}

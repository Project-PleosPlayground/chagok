package ai.safetypoc.app.ui.profile

import core.character.CharacterDef
import core.character.CharacterId
import core.character.InMemoryProfileStore
import core.character.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddProfileModelTest {
    private val cars = listOf(
        CharacterDef(CharacterId(1), "아반떼", "avante"),
        CharacterDef(CharacterId(2), "캐스퍼 일렉트릭", "casper"),
        CharacterDef(CharacterId(3), "그랜저", "grandeur"),
    )
    private val store = InMemoryProfileStore()
    private var closed = 0
    private val model = AddProfileModel(store, { cars }, { closed++ }, newId = { "new" }, nowMs = { 42L })

    @Test fun `처음 차가 가운데, 양옆은 이전·다음 차`() {
        model.enter()
        val s = model.state.value
        assertEquals("아반떼", s.centerCar?.name); assertEquals("그랜저", s.leftCar?.name); assertEquals("캐스퍼 일렉트릭", s.rightCar?.name)
    }

    @Test fun `기본 차종을 주면 그 차가 가운데`() {
        val m = AddProfileModel(store, { cars }, { closed++ }, defaultAssetKey = "grandeur")
        m.enter()
        assertEquals("그랜저", m.state.value.centerCar?.name)
    }

    @Test fun `다음 버튼은 다음 차를 가운데로, 마지막 다음은 처음`() {
        model.enter()
        repeat(2) { model.nextCar() }
        assertEquals("그랜저", model.state.value.centerCar?.name)
        model.nextCar()
        assertEquals("아반떼", model.state.value.centerCar?.name)
    }

    @Test fun `차가 2대 이하면 양옆을 비운다`() {
        val two = AddProfileState(cars.take(2).map { CarOption(it.id, it.name, it.assetKey) })
        assertNull(two.leftCar); assertNull(two.rightCar)
        assertNull(AddProfileState().centerCar)
    }

    @Test fun `이름은 12자까지, 이모지 하나를 1자로 센다`() {
        model.enter()
        model.changeName("가나다라마바사아자차카타파하")
        assertEquals("가나다라마바사아자차카타", model.state.value.name)
        val emoji = "👨‍👩‍👧"
        assertEquals(1, ProfileName.length(emoji))
        model.changeName("$emoji 12345678901")
        assertEquals("$emoji 1234567890", model.state.value.name)
        assertEquals(ProfileName.MAX_LENGTH, ProfileName.length(model.state.value.name))
    }

    @Test fun `지우기는 이름을 비운다`() {
        model.enter(); model.changeName("붕붕"); model.clearName()
        assertEquals("", model.state.value.name)
    }

    @Test fun `이름이 비면 저장하지 않는다`() {
        model.enter(); model.changeName("   "); model.save()
        assertTrue(store.profiles().isEmpty()); assertEquals(0, closed)
        assertFalse(model.state.value.canSave)
    }

    @Test fun `저장하면 가운데 차를 고른 프로필을 만들고 닫는다`() {
        store.upsert(Profile("a", "가", 1L))
        model.enter(); model.nextCar(); model.changeName(" 붕붕 "); model.save()
        assertEquals(Profile("new", "붕붕", 42L), store.profiles().last())
        assertEquals(CharacterId(2), store.state("new").selected)
        assertEquals(1, closed)
    }

    @Test fun `첫 프로필은 기본 프로필 id 로 만들어 그 전에 쌓인 데이터를 이어받는다`() {
        store.saveState(store.state(Profile.DEFAULT).copy(coins = 70))
        model.enter(); model.changeName("첫"); model.save()
        assertEquals(listOf(Profile(Profile.DEFAULT, "첫", 42L)), store.profiles())
        assertEquals(70, store.state(Profile.DEFAULT).coins)
        assertEquals(CharacterId(1), store.state(Profile.DEFAULT).selected)
    }

    @Test fun `프로필이 이미 3개면 저장하지 않는다`() {
        repeat(MAX_PROFILES) { store.upsert(Profile("p$it", "p", it.toLong())) }
        model.enter(); model.changeName("붕붕"); model.save()
        assertEquals(MAX_PROFILES, store.profiles().size); assertEquals(0, closed)
    }

    @Test fun `취소는 저장하지 않고 닫는다`() {
        model.enter(); model.changeName("붕붕"); model.cancel()
        assertTrue(store.profiles().isEmpty()); assertEquals(1, closed)
    }

    @Test fun `다시 들어오면 첫 차, 빈 이름`() {
        model.enter(); model.nextCar(); model.changeName("붕붕"); model.enter()
        assertEquals(0, model.state.value.selected); assertEquals("", model.state.value.name)
    }

    // 수정 (#111)

    private fun saved(id: String, name: String, car: CharacterId?) {
        store.upsert(Profile(id, name, 7L))
        store.saveState(store.state(id).copy(selected = car, coins = 50))
    }

    @Test fun `수정은 그 프로필의 이름과 고른 차로 시작한다`() {
        saved("p1", "우리집", CharacterId(3))
        assertTrue(model.enterEdit("p1"))
        val s = model.state.value
        assertEquals("우리집", s.name); assertEquals("그랜저", s.centerCar?.name)
        assertTrue(s.editing); assertFalse(s.confirmingDelete)
    }

    @Test fun `고른 차가 목록에 없으면 첫 차로 시작한다`() {
        saved("p1", "우리집", CharacterId(99))
        model.enterEdit("p1")
        assertEquals("아반떼", model.state.value.centerCar?.name)
    }

    @Test fun `없는 프로필은 수정을 시작하지 않는다`() {
        model.enter()
        assertFalse(model.enterEdit("none"))
        assertFalse(model.state.value.editing)
    }

    @Test fun `수정 저장은 이름과 차만 바꾸고 만든 시각·코인은 그대로`() {
        saved("p1", "우리집", CharacterId(1))
        model.enterEdit("p1"); model.nextCar(); model.changeName(" 출근차 "); model.save()
        assertEquals(listOf(Profile("p1", "출근차", 7L)), store.profiles())
        assertEquals(CharacterId(2), store.state("p1").selected); assertEquals(50, store.state("p1").coins)
        assertEquals(1, closed)
    }

    @Test fun `프로필이 3개여도 수정은 저장한다`() {
        repeat(MAX_PROFILES) { saved("p$it", "p", CharacterId(1)) }
        model.enterEdit("p0"); model.changeName("새 이름"); model.save()
        assertEquals("새 이름", store.profiles().first { it.id == "p0" }.name); assertEquals(MAX_PROFILES, store.profiles().size)
    }

    @Test fun `수정 화면에서 이름을 비우면 저장하지 않는다`() {
        saved("p1", "우리집", CharacterId(1))
        model.enterEdit("p1"); model.clearName(); model.save()
        assertEquals("우리집", store.profiles().single().name); assertEquals(0, closed)
    }

    @Test fun `수정 취소는 바꾸지 않고 닫는다`() {
        saved("p1", "우리집", CharacterId(1))
        model.enterEdit("p1"); model.nextCar(); model.changeName("딴이름"); model.cancel()
        assertEquals("우리집", store.profiles().single().name); assertEquals(CharacterId(1), store.state("p1").selected)
        assertEquals(1, closed)
    }

    @Test fun `삭제는 팝업을 띄우고, 닫으면 지우지 않는다`() {
        saved("p1", "우리집", CharacterId(1))
        model.enterEdit("p1"); model.delete()
        assertTrue(model.state.value.confirmingDelete)
        model.dismissDelete()
        assertFalse(model.state.value.confirmingDelete); assertEquals(1, store.profiles().size); assertEquals(0, closed)
    }

    @Test fun `삭제 확인은 그 프로필을 지우고 닫는다`() {
        val deleted = mutableListOf<String>()
        val m = AddProfileModel(store, { cars }, { closed++ }, deleteProfile = { deleted += it; store.delete(it) })
        saved("p1", "우리집", CharacterId(1)); saved("p2", "출근차", CharacterId(2))
        m.enterEdit("p1"); m.delete(); m.confirmDelete()
        assertEquals(listOf("p1"), deleted); assertEquals(listOf("p2"), store.profiles().map { it.id })
        assertFalse(m.state.value.confirmingDelete); assertEquals(1, closed)
    }

    @Test fun `추가 화면에서는 삭제 팝업이 뜨지 않는다`() {
        model.enter(); model.delete(); model.confirmDelete()
        assertFalse(model.state.value.confirmingDelete); assertEquals(0, closed)
    }

    @Test fun `수정 뒤 추가로 들어오면 수정 상태가 남지 않는다`() {
        saved("p1", "우리집", CharacterId(3))
        model.enterEdit("p1"); model.enter()
        assertFalse(model.state.value.editing); assertEquals("", model.state.value.name)
    }
}

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

class ProfileModelTest {
    private val cars = listOf(CharacterDef(CharacterId(1), "아반떼", "avante"), CharacterDef(CharacterId(3), "그랜저", "grandeur"))
    private val store = InMemoryProfileStore()
    private val calls = mutableListOf<String>()
    private val nav = object : ProfileNavigation {
        override fun selectProfile(profileId: String) { calls += "select:$profileId" }
        override fun addProfile() { calls += "add" }
        override fun editProfile(profileId: String) { calls += "edit:$profileId" }
    }
    private val model = ProfileModel(store, { cars }, nav)

    private fun add(id: String, createdMs: Long, car: CharacterId? = null) {
        store.upsert(Profile(id, "이름$id", createdMs))
        if (car != null) store.saveState(store.state(id).copy(selected = car))
    }

    @Test fun `프로필 0개면 추가만 되고 프로필 관리는 없다`() {
        model.refresh()
        val s = model.state.value
        assertTrue(s.profiles.isEmpty()); assertTrue(s.canAdd); assertFalse(s.showEditButton)
        model.startEdit()
        assertFalse(model.state.value.editing)
    }

    @Test fun `카드는 만든 순서로, 고른 캐릭터의 그림과 단계를 보인다`() {
        add("b", 20, CharacterId(3))
        add("a", 10)
        store.saveProgress(store.progress("b", CharacterId(3)).copy(stage = 4))
        model.refresh()
        val s = model.state.value
        assertEquals(listOf("a", "b"), s.profiles.map { it.id })
        assertNull(s.profiles[0].characterAssetKey); assertEquals(1, s.profiles[0].stage)
        assertEquals("grandeur", s.profiles[1].characterAssetKey); assertEquals(4, s.profiles[1].stage)
        assertTrue(s.showEditButton)
    }

    @Test fun `2개면 카드 2장과 추가 버튼`() {
        add("a", 1); add("b", 2)
        model.refresh()
        assertEquals(2, model.state.value.profiles.size); assertTrue(model.state.value.canAdd)
        model.tapAdd()
        assertEquals(listOf("add"), calls)
    }

    @Test fun `3개면 추가 버튼이 없고 눌러도 가지 않는다`() {
        repeat(MAX_PROFILES) { add("p$it", it.toLong()) }
        model.refresh()
        assertFalse(model.state.value.canAdd)
        model.tapAdd()
        assertTrue(calls.isEmpty())
    }

    @Test fun `수정 모드에서는 카드를 누르면 수정으로, 취소하면 선택으로 돌아온다`() {
        add("a", 1)
        model.refresh()
        model.tapProfile("a")
        model.startEdit()
        assertTrue(model.state.value.editing); assertFalse(model.state.value.showEditButton)
        model.tapProfile("a")
        model.endEdit()
        model.tapProfile("a")
        assertEquals(listOf("select:a", "edit:a", "select:a"), calls)
    }

    @Test fun `다시 들어오면 수정 모드가 끝나 있다`() {
        add("a", 1)
        model.refresh(); model.startEdit(); model.refresh()
        assertFalse(model.state.value.editing)
    }
}

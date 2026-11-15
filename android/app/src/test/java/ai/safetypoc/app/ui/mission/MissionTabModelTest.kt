package ai.safetypoc.app.ui.mission

import ai.safetypoc.app.AppGraph
import core.mission.ConditionType
import core.mission.MissionKind
import core.mission.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionTabModelTest {
    private val graph = AppGraph()
    private val now = 1_793_000_000_000L
    private var driving = false
    private var changed = 0
    private var live = emptyMap<String, Float>()
    private val model = MissionTabModel(
        { graph.profileId }, graph.missions, graph.missionActions, slotCount = { 3 }, driving = { driving }, now = { now }, onChanged = { changed++ },
        live = { live },
    )

    @Test fun `제공된 개인·주간 미션을 나눠 보여 주고 도전하면 슬롯으로 간다`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        model.refresh()
        val s = model.state.value
        assertEquals(core.content.BundledContent.pack.missions.rules.personalPerDay, s.personal.size); assertEquals(10, s.weekly.size); assertTrue(s.slot.isEmpty())
        val first = s.personal.first()
        assertTrue(first.canChallenge)
        model.challenge(first.uid)
        assertEquals(listOf(first.uid), model.state.value.slot.map { it.uid })
        assertEquals(MissionStatus.IN_PROGRESS, model.state.value.slot.single().status)
        assertEquals(1, changed)
    }

    @Test fun `주행 중에는 버튼을 막고 눌러도 주행 중이라고 알린다`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        driving = true
        model.refresh()
        val card = model.state.value.personal.first()
        assertFalse(card.canChallenge)
        model.challenge(card.uid)
        assertEquals(MissionNotice.DRIVING, model.state.value.notice)
        assertTrue(model.state.value.slot.isEmpty())
    }

    @Test fun `진행도는 센 값과 목표치, 한 번이라도 조건은 1번으로 보인다`() {
        val brake = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        val belt = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.SEATBELT_BEFORE_START, 1, now)!!
        model.refresh()
        val cards = model.state.value.personal.associateBy { it.uid }
        assertEquals("0/3", cards.getValue(brake.uid).progress)
        assertEquals("0/1", cards.getValue(belt.uid).progress)
    }

    @Test fun `이상 조건은 아직 센 값이 없으면 0 대신 비어 있음으로 보인다`() {
        val signal = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.SIGNALED_RATE_MIN, 1, now)!!
        model.refresh()
        val card = model.state.value.personal.single { it.uid == signal.uid }
        assertTrue(card.progress, card.progress.startsWith("-/"))
    }

    @Test fun `취소하면 패널티 알림을 보이고 슬롯이 빈다`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        model.challenge(m.uid)
        model.cancel(m.uid)
        assertEquals(MissionNotice.CANCELLED, model.state.value.notice)
        assertTrue(model.state.value.slot.isEmpty())
    }

    @Test fun `주행 중 진행도가 있으면 슬롯 카드에 그 값을 보이고, 판정 상태는 바꾸지 않는다`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!   // 3회 이하
        model.challenge(m.uid)
        assertEquals("0/3", model.state.value.slot.single().progress)
        live = mapOf(m.uid to 5f)
        model.refresh()
        val card = model.state.value.slot.single()
        assertEquals("5/3", card.progress)
        assertEquals(MissionStatus.IN_PROGRESS, card.status)
    }

    @Test fun `제공 카드를 누르면 도전 팝업, 확인하면 도전하고 팝업이 닫힌다`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        model.refresh()
        val card = model.state.value.personal.first()
        model.tapCard(card.uid)
        assertEquals(MissionDialog.Challenge(card), model.state.value.dialog)
        model.confirmDialog()
        assertEquals(null, model.state.value.dialog)
        assertEquals(listOf(card.uid), model.state.value.slot.map { it.uid })
    }

    @Test fun `슬롯이 가득 차면 제공 카드를 눌러도 팝업 대신 알림`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        model.refresh()
        model.state.value.weekly.take(3).forEach { model.challenge(it.uid) }
        assertTrue(model.state.value.slotFull)
        model.tapCard(model.state.value.weekly.first().uid)
        assertEquals(null, model.state.value.dialog)
        assertEquals(MissionNotice.SLOT_FULL, model.state.value.notice)
        model.dismissNotice()
        assertEquals(null, model.state.value.notice)
    }

    @Test fun `도전 중 카드를 길게 누르면 취소 팝업, 닫으면 아무 것도 바뀌지 않는다`() {
        val m = graph.missions.offer(graph.profileId, MissionKind.PERSONAL, ConditionType.HARSH_BRAKE_MAX, 1, now)!!
        model.challenge(m.uid)
        val card = model.state.value.slot.single()
        model.longPressCard(card.uid)
        assertEquals(MissionDialog.Cancel(card), model.state.value.dialog)
        model.dismissDialog()
        assertEquals(null, model.state.value.dialog)
        assertEquals(MissionStatus.IN_PROGRESS, model.state.value.slot.single().status)
        model.tapCard(card.uid)
        assertEquals("슬롯 카드는 눌러도 반응이 없다", null, model.state.value.dialog)
    }

    @Test fun `개인·주간 목록은 제목 가나다 순`() {
        graph.missionActions.refresh(graph.profileId, now, null)
        model.refresh()
        val weekly = model.state.value.weekly.map { it.title }
        assertEquals(weekly.sorted(), weekly)
    }
}

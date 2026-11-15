package ai.safetypoc.app.ui.home

import ai.safetypoc.app.ui.mission.MissionCard
import ai.safetypoc.app.ui.mission.MissionTabState
import androidx.compose.ui.unit.dp
import core.mission.MissionKind
import core.mission.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class StageScreenTest {
    private fun card(uid: String, status: MissionStatus) = MissionCard(
        uid = uid, title = uid, description = "", kind = MissionKind.PERSONAL, status = status, tier = 1,
        progress = "0/1", rewardCoins = 0, rewardXp = 0, remainingMs = 0L,
        canChallenge = false, canCancel = false, canClear = false,
    )

    @Test fun `2칸 창 1184 의 스테이지 높이는 exp_bar 아래 a 부터 주행 버튼 위 a 까지 832`() {
        // 1184 - (48 + 64) - 48 - 48 - (96 + 48): exp_bar 캡슐은 coin_bar 와 같은 높이 64 라 아래 끝이 48 + 64
        assertEquals(832.dp, StageScreenSizes.stageHeight(1184.dp))
    }

    @Test fun `mission_panel 은 도전 슬롯을 순서대로 최대 3장 보이고 성공 수를 센다`() {
        val slot = listOf(
            card("a", MissionStatus.IN_PROGRESS), card("b", MissionStatus.SUCCEEDED),
            card("c", MissionStatus.FAILED), card("d", MissionStatus.SUCCEEDED),
        )
        val panel = MissionTabState(slot = slot).toPanel()
        assertEquals(listOf("a", "b", "c"), panel.cards.map { it.uid })
        assertEquals(1, panel.succeeded)
    }

    @Test fun `도전 슬롯이 비면 mission_panel 도 빈다`() {
        assertEquals(MissionPanelState(), MissionTabState().toPanel())
    }
}

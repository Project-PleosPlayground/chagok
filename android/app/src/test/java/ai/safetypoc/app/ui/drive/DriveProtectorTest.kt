package ai.safetypoc.app.ui.drive

import ai.safetypoc.app.ui.nav.Destination
import ai.safetypoc.app.ui.nav.Phase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveProtectorTest {
    @Test fun `주행 중이고 진입 전 화면이면 덮는다`() {
        for (d in Destination.entries.filter { it.phase == Phase.ENTRY }) {
            assertTrue("$d", protectorShown(driving = true, current = d))
        }
    }

    @Test fun `진입 후 화면에서는 주행 중이어도 덮지 않는다`() {
        for (d in Destination.entries.filter { it.phase == Phase.MAIN }) {
            assertFalse("$d", protectorShown(driving = true, current = d))
        }
    }

    @Test fun `주행 중이 아니면 어느 화면에서도 덮지 않는다`() {
        for (d in Destination.entries) assertFalse("$d", protectorShown(driving = false, current = d))
    }
}

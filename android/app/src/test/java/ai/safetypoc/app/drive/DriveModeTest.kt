package ai.safetypoc.app.drive

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DriveModeTest {
    private val session = MutableStateFlow<Long?>(null)
    private val restricted = MutableStateFlow(false)
    private val mode = DriveMode(session, restricted, TestScope(UnconfinedTestDispatcher()))

    @Test fun `버튼을 누르면 출발 전에도 주행 중이고, 출발해 세션이 열려도 그대로 주행 중`() {
        assertFalse(mode.driving.value)
        mode.start()
        assertTrue(mode.driving.value)
        session.value = START_MS
        assertTrue(mode.driving.value)
    }

    @Test fun `세션이 열려 있으면 버튼 모드를 끝내도 세션이 닫힐 때까지 주행 중`() {
        mode.start(); session.value = START_MS
        mode.clear()
        assertTrue(mode.driving.value)
        session.value = null
        assertFalse(mode.driving.value)
    }

    @Test fun `버튼만 누르고 출발하지 않았으면 끝내면 바로 정차 모드`() {
        mode.start()
        mode.clear()
        assertFalse(mode.driving.value)
    }

    @Test fun `버튼을 누르지 않고 출발해도 세션이 열리면 주행 중`() {
        session.value = START_MS
        assertTrue(mode.driving.value)
    }

    @Test fun `출발 전이라도 주행 규제가 걸리면(D 단 정차) 주행 중, 풀리면(P 단) 정차`() {
        restricted.value = true
        assertTrue(mode.driving.value)
        restricted.value = false
        assertFalse(mode.driving.value)
    }

    @Test fun `세션이 끝나도 주행 규제가 남아 있으면 주행 중, 규제가 풀려야 정차`() {
        restricted.value = true; session.value = START_MS
        session.value = null
        assertTrue(mode.driving.value)
        restricted.value = false
        assertFalse(mode.driving.value)
    }

    @Test fun `주행 규제가 풀려도 세션이 열려 있으면 주행 중`() {
        session.value = START_MS; restricted.value = true
        restricted.value = false
        assertTrue(mode.driving.value)
    }

    @Test fun `처음 값은 만들 때의 주행 규제를 따른다`() {
        val r = MutableStateFlow(true)
        assertTrue(DriveMode(MutableStateFlow<Long?>(null), r, TestScope(UnconfinedTestDispatcher())).driving.value)
    }

    private companion object {
        const val START_MS = 1_793_000_000_000L
    }
}

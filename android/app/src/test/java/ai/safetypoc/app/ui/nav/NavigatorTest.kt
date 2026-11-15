package ai.safetypoc.app.ui.nav

import ai.safetypoc.app.ui.onboarding.OnboardingTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    private val entered = ArrayList<Destination>()
    private val nav = Navigator(onTopLevelEntered = { entered += it })

    /** 온보딩을 마치고 프로필을 골라 홈에 온다 */
    private fun Navigator.toHome() { finishOnboarding(); go(Destination.HOME) }

    @Test fun `온보딩으로 시작하고 끝나면 홈으로 간다`() {
        assertEquals(Destination.ONBOARDING, nav.current.value)
        assertFalse("온보딩에서는 뒤로 갈 곳이 없다", nav.back())
        nav.go(Destination.MISSION)
        assertEquals("온보딩 중에도 go 는 그 화면으로 간다", Destination.MISSION, nav.current.value)
    }

    @Test fun `온보딩이 끝나면 프로필 선택, 홈에서 탭으로 갔다가 뒤로 가면 홈`() {
        nav.finishOnboarding()
        assertEquals(Destination.PROFILE, nav.current.value)
        assertFalse("프로필 선택에서는 뒤로 갈 곳이 없다", nav.back())
        nav.go(Destination.HOME)
        assertEquals(Destination.HOME, nav.current.value)
        for (tab in listOf(Destination.MISSION, Destination.SHOP, Destination.REPORT)) {
            nav.go(tab); assertEquals(tab, nav.current.value)
            assertTrue(nav.back()); assertEquals(Destination.HOME, nav.current.value)
        }
        assertFalse("홈에서는 뒤로 갈 곳이 없다", nav.back())
    }

    @Test fun `탭에서 다른 탭으로 가도 뒤로 가면 홈`() {
        nav.toHome()
        nav.go(Destination.MISSION); nav.go(Destination.SHOP); nav.go(Destination.REPORT)
        assertTrue(nav.back()); assertEquals(Destination.HOME, nav.current.value)
    }

    @Test fun `주행 종료 화면은 지금 화면 위에 쌓이고 뒤로 가면 돌아온다`() {
        nav.toHome(); nav.go(Destination.SHOP)
        nav.go(Destination.DRIVE_END)
        assertTrue(nav.back()); assertEquals(Destination.SHOP, nav.current.value)
    }

    @Test fun `탭에 들어갈 때마다 알린다(받아 둔 콘텐츠 적용용)`() {
        nav.toHome(); nav.go(Destination.MISSION); nav.go(Destination.DEBUG); nav.back(); nav.back()
        assertEquals(listOf(Destination.HOME, Destination.MISSION, Destination.MISSION, Destination.HOME), entered)
    }

    @Test fun `같은 화면으로 가면 아무 일도 없다`() {
        nav.toHome(); nav.go(Destination.HOME)
        assertEquals(listOf(Destination.HOME), entered)
        assertFalse(nav.back())
    }

    @Test fun `뒤로 가기 키 - 온보딩, 프로필 선택, 홈에서는 앱을 숨기고 화면은 그대로다`() {
        var hidden = 0
        nav.onBackKey { hidden++ }
        assertEquals(1, hidden); assertEquals(Destination.ONBOARDING, nav.current.value)
        nav.finishOnboarding()
        nav.onBackKey { hidden++ }
        assertEquals(2, hidden); assertEquals(Destination.PROFILE, nav.current.value)
        nav.go(Destination.HOME)
        nav.onBackKey { hidden++ }
        assertEquals(3, hidden); assertEquals(Destination.HOME, nav.current.value)
    }

    @Test fun `뒤로 가기 키 - 하위 화면에서는 숨기지 않고 이전 화면으로 간다`() {
        var hidden = 0
        nav.toHome(); nav.go(Destination.SHOP); nav.go(Destination.DRIVE_END)
        nav.onBackKey { hidden++ }
        assertEquals(Destination.SHOP, nav.current.value)
        nav.onBackKey { hidden++ }
        assertEquals(Destination.HOME, nav.current.value)
        assertEquals(0, hidden)
    }

    @Test fun `온보딩에 다시 들어와도 처음 들어온 시각부터 센다`() {
        assertEquals(OnboardingTiming(first = true, remainingMs = 1_500), nav.enterOnboarding(10_000, 1_500))
        assertEquals("화면이 다시 만들어지면 소리는 다시 내지 않고 남은 시간만", OnboardingTiming(first = false, remainingMs = 900), nav.enterOnboarding(10_600, 1_500))
        assertEquals(OnboardingTiming(first = false, remainingMs = 0), nav.enterOnboarding(12_000, 1_500))
    }

    @Test fun `주행 규제가 남아 있으면 주행 종료 화면을 미루고 홈 고정을 둔다, 풀리면 그때 간다`() {
        nav.toHome(); nav.lockToHome()
        nav.showDriveEnd(Destination.DRIVE_END, restricted = true)
        assertEquals(Destination.HOME, nav.current.value)
        assertTrue(nav.lockedToHome)
        nav.unlock()
        assertEquals(Destination.DRIVE_END, nav.current.value)
        assertFalse(nav.lockedToHome)
        assertTrue("주행 종료에서 뒤로 가면 홈", nav.back()); assertEquals(Destination.HOME, nav.current.value)
    }

    @Test fun `미뤄 둔 주행 종료 화면은 한 번만 간다`() {
        nav.toHome(); nav.lockToHome()
        nav.showDriveEnd(Destination.DRIVE_END, restricted = true)
        nav.unlock(); nav.back()
        nav.lockToHome(); nav.unlock()
        assertEquals(Destination.HOME, nav.current.value)
    }

    @Test fun `미뤄 두는 동안 다음 주행이 시작돼도 홈 고정이 이어지고, 끝나면 주행 종료 화면으로 간다`() {
        nav.toHome(); nav.lockToHome()
        nav.showDriveEnd(Destination.DRIVE_END, restricted = true)
        nav.lockToHome()
        nav.go(Destination.SHOP)
        assertEquals(Destination.HOME, nav.current.value)
        nav.showDriveEnd(Destination.DRIVE_END, restricted = false)
        assertEquals(Destination.DRIVE_END, nav.current.value)
    }

    @Test fun `진입 전 화면에서는 미뤄 둔 주행 종료 화면으로 가지 않는다`() {
        nav.finishOnboarding()
        nav.showDriveEnd(Destination.DRIVE_END, restricted = true)
        nav.unlock()
        assertEquals(Destination.PROFILE, nav.current.value)
        nav.go(Destination.HOME); nav.lockToHome(); nav.unlock()
        assertEquals("버린 결과 화면이 나중에 뜨지 않는다", Destination.HOME, nav.current.value)
    }

    @Test fun `온보딩 중에 주행이 시작되면 주행이 끝난 뒤 온보딩을 처음부터 다시 센다`() {
        nav.enterOnboarding(10_000, 1_500)
        nav.lockToHome()
        assertEquals(Destination.ONBOARDING, nav.current.value)
        assertFalse(nav.lockedToHome)
        nav.unlock()
        assertEquals("시동음도 다시 내고 1.5초를 처음부터", OnboardingTiming(first = true, remainingMs = 1_500), nav.enterOnboarding(30_000, 1_500))
    }

    @Test fun `온보딩을 마친 뒤 시작된 주행은 온보딩 시각을 지우지 않는다`() {
        nav.enterOnboarding(10_000, 1_500)
        nav.finishOnboarding()
        nav.lockToHome()
        assertEquals(OnboardingTiming(first = false, remainingMs = 0), nav.enterOnboarding(30_000, 1_500))
    }

    @Test fun `탭에서 하위 화면에 갔다가 뒤로 오면 돌아온 것으로, 탭을 눌러 오면 새로 들어온 것으로 본다`() {
        val seen = ArrayList<Pair<Destination, Boolean>>()
        lateinit var n: Navigator
        n = Navigator(onTopLevelEntered = { seen += it to n.returned })
        n.toHome(); n.go(Destination.SHOP); n.go(Destination.DEBUG); n.back(); n.go(Destination.MISSION); n.go(Destination.SHOP)
        assertEquals(
            listOf(Destination.HOME to false, Destination.SHOP to false, Destination.SHOP to true, Destination.MISSION to false, Destination.SHOP to false),
            seen,
        )
    }

    @Test fun `주행이 시작되면 어느 화면에 있든 홈으로 가고, 풀릴 때까지 다른 화면으로도 뒤로도 가지 않는다`() {
        nav.toHome(); nav.go(Destination.MISSION); nav.go(Destination.DEBUG)
        entered.clear()
        nav.lockToHome()
        assertEquals(Destination.HOME, nav.current.value)
        assertEquals(listOf(Destination.HOME), entered)
        nav.go(Destination.SHOP); nav.go(Destination.DRIVE_END)
        assertEquals(Destination.HOME, nav.current.value)
        assertFalse(nav.back())
        var hidden = false
        nav.onBackKey { hidden = true }
        assertTrue("고정된 홈에서 뒤로 가기 키는 앱을 숨긴다", hidden)

        nav.unlock()
        assertFalse("고정 중 뒤로 가기 목록을 비웠다", nav.back())
        nav.go(Destination.DRIVE_END)
        assertEquals(Destination.DRIVE_END, nav.current.value)
        assertTrue(nav.back()); assertEquals(Destination.HOME, nav.current.value)
    }

    @Test fun `이미 홈이면 고정해도 다시 들어간 것으로 치지 않는다`() {
        nav.toHome()
        entered.clear()
        nav.lockToHome(); nav.lockToHome()
        assertEquals(emptyList<Destination>(), entered)
    }

    @Test fun `진입 전은 온보딩과 프로필 선택·추가·수정, 나머지 화면은 모두 진입 후다`() {
        val entry = Destination.entries.filter { it.phase == Phase.ENTRY }
        assertEquals(listOf(Destination.ONBOARDING, Destination.PROFILE, Destination.ADD_PROFILE, Destination.EDIT_PROFILE), entry)
    }

    @Test fun `진입 전 화면에서는 주행이 시작돼도 홈으로 가지도 고정하지도 않는다`() {
        nav.lockToHome()
        assertEquals(Destination.ONBOARDING, nav.current.value)
        assertFalse(nav.lockedToHome)

        nav.finishOnboarding(); nav.go(Destination.ADD_PROFILE)
        entered.clear()
        nav.lockToHome()
        assertEquals(Destination.ADD_PROFILE, nav.current.value)
        assertFalse(nav.lockedToHome)
        assertEquals(emptyList<Destination>(), entered)
        assertTrue("프로필 추가의 뒤로 가기(취소)는 그대로 된다", nav.back()); assertEquals(Destination.PROFILE, nav.current.value)
    }

    @Test fun `진입 전 화면에서 주행이 끝나면 주행 종료 화면으로 가지 않고, 이어지는 주행도 홈으로 가지 않는다`() {
        nav.finishOnboarding()
        nav.lockToHome()
        nav.showDriveEnd(Destination.DRIVE_END)
        assertEquals(Destination.PROFILE, nav.current.value)
        nav.lockToHome()
        assertEquals("다시 달려도 진입 전이라 홈에 고정하지 않는다", Destination.PROFILE, nav.current.value)
        assertFalse(nav.lockedToHome)
    }

    @Test fun `진입 후 화면에서 주행이 끝나면 홈 고정을 풀고 주행 종료 화면으로 간다`() {
        nav.toHome(); nav.lockToHome()
        nav.showDriveEnd(Destination.DRIVE_END)
        assertEquals(Destination.DRIVE_END, nav.current.value)
        assertFalse(nav.lockedToHome)
    }

    @Test fun `진입 후 화면에서는 진입 전과 달리 주행이 시작되면 홈에 고정한다`() {
        nav.toHome(); nav.go(Destination.SHOP)
        nav.lockToHome()
        assertEquals(Destination.HOME, nav.current.value)
        assertTrue(nav.lockedToHome)
    }

    @Test fun `프로필 추가는 프로필 선택 위에 쌓이고, 프로필을 고르면 홈으로 가며 뒤로 가기 목록을 비운다`() {
        nav.finishOnboarding(); nav.go(Destination.ADD_PROFILE)
        assertEquals(Destination.ADD_PROFILE, nav.current.value)
        assertTrue(nav.back()); assertEquals(Destination.PROFILE, nav.current.value)
        nav.go(Destination.HOME)
        assertEquals(listOf(Destination.HOME), entered)
        assertFalse("프로필 선택으로 돌아가지 않는다", nav.back())
    }
}

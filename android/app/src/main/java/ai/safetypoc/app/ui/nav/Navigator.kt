package ai.safetypoc.app.ui.nav

import ai.safetypoc.app.ui.onboarding.OnboardingTiming
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 화면이 속한 구간. 진입 전(온보딩, 프로필 선택·추가·수정)과 진입 후(홈과 그 밖의 모든 화면)로 나눈다 */
enum class Phase { ENTRY, MAIN }

/** 앱의 화면. [topLevel] 은 탭으로 바로 가는 top-level destination 이고, [phase] 는 화면이 속한 구간이다 */
enum class Destination(val topLevel: Boolean, val phase: Phase = Phase.MAIN) {
    ONBOARDING(false, Phase.ENTRY),
    PROFILE(false, Phase.ENTRY),
    ADD_PROFILE(false, Phase.ENTRY),
    EDIT_PROFILE(false, Phase.ENTRY),
    HOME(true),
    MISSION(true),
    SHOP(true),
    REPORT(true),
    SETTINGS(true),
    DRIVE_END(false),
    DEBUG(false),
}

/**
 * 화면 이동. 지금 화면과 뒤로 가기 목록을 갖는다.
 * - top-level destination 으로 가면 뒤로 가기 목록을 비운다. 홈이 아니면 홈 하나만 남겨, 뒤로 가기로 홈에 온다.
 * - 그 밖의 화면(프로필 추가·수정, 주행 종료, 디버그)은 지금 화면 위에 쌓는다. 온보딩에서 가는 화면(프로필 선택)은 온보딩을 쌓지 않는다.
 * [onTopLevelEntered] 는 top-level destination 에 들어갈 때마다 부른다(받아 둔 콘텐츠 적용, #31).
 */
class Navigator(
    start: Destination = Destination.ONBOARDING,
    private val onTopLevelEntered: (Destination) -> Unit = {},
) {
    private val _current = MutableStateFlow(start)
    val current: StateFlow<Destination> = _current.asStateFlow()
    private val backStack = ArrayDeque<Destination>()
    /** 온보딩에 처음 들어온 시각. 화면이 다시 만들어져도(주야간 모드, 창 크기) 처음부터 다시 세지 않게 여기 둔다 */
    private var onboardingEnteredMs: Long? = null
    /** 주행 규제가 남아 있어 아직 가지 않은 결과 화면 ([showDriveEnd]) */
    private var pendingDriveEnd: Destination? = null

    /** 마지막 이동이 뒤로 가기로 돌아온 것인지. [onTopLevelEntered] 안에서 읽으면 새로 들어온 것과 돌아온 것을 나눈다 */
    var returned: Boolean = false
        private set

    /** 주행 중이라 홈(주행 화면)에 고정됐는지. 고정되면 [go] 와 [back] 이 아무 데도 가지 않는다 */
    var lockedToHome: Boolean = false
        private set

    /**
     * 주행이 시작되면 부른다. 홈으로 가고 뒤로 가기 목록을 비운 뒤 고정한다.
     * 진입 전 화면([Phase.ENTRY])에 있으면 화면을 옮기지 않는다. 그 화면은 주행 프로텍터가 덮는다.
     * 온보딩 중이면 진행을 버려, 주행이 끝난 뒤 온보딩을 처음부터 다시 한다(#76).
     */
    fun lockToHome() {
        if (_current.value == Destination.ONBOARDING) onboardingEnteredMs = null
        if (lockedToHome || _current.value.phase == Phase.ENTRY) return
        backStack.clear()
        returned = false
        if (_current.value != Destination.HOME) {
            _current.value = Destination.HOME
            onTopLevelEntered(Destination.HOME)
        }
        lockedToHome = true
    }

    /** 주행이 끝나면 부른다. 미뤄 둔 결과 화면([showDriveEnd])이 있으면 그 화면으로 간다 */
    fun unlock() {
        lockedToHome = false
        val to = pendingDriveEnd ?: return
        pendingDriveEnd = null
        if (_current.value.phase == Phase.MAIN) go(to)
    }

    /**
     * 끝난 주행의 결과 화면 [to] 로 간다. 진입 전 화면([Phase.ENTRY])에 있었으면 그 화면에 그대로 둔다(프로필을 고르지 않고 앱 안으로 들어가지 않게).
     * 세션은 끝났지만 주행 규제가 남아 있으면([restricted], D 단 정차) 홈 고정을 그대로 두고, 주행이 끝나 [unlock] 할 때 간다.
     */
    fun showDriveEnd(to: Destination, restricted: Boolean = false) {
        pendingDriveEnd = to
        if (!restricted) unlock()
    }

    fun go(to: Destination) {
        val from = _current.value
        if (lockedToHome || to == from || to == Destination.ONBOARDING) return
        if (to.topLevel) {
            backStack.clear()
            if (to != Destination.HOME) backStack.addLast(Destination.HOME)
        } else if (from != Destination.ONBOARDING) {
            backStack.addLast(from)
        }
        returned = false
        _current.value = to
        if (to.topLevel) onTopLevelEntered(to)
    }

    /** 뒤로 가기 목록을 비우고 [to] 에서 다시 시작한다(디버그 탭의 프로필 초기화). 홈에 고정됐으면 아무 일도 없다 */
    fun startOver(to: Destination) {
        if (lockedToHome || to == Destination.ONBOARDING) return
        backStack.clear()
        returned = false
        _current.value = to
        if (to.topLevel) onTopLevelEntered(to)
    }

    /** 온보딩 화면에 들어올 때 부른다. 처음이면 지금을 시작 시각으로 삼는다. [nowMs] 는 단조 시계 */
    fun enterOnboarding(nowMs: Long, durationMs: Long): OnboardingTiming {
        val first = onboardingEnteredMs == null
        val start = onboardingEnteredMs ?: nowMs.also { onboardingEnteredMs = it }
        return OnboardingTiming(first, (start + durationMs - nowMs).coerceAtLeast(0))
    }

    /** 온보딩이 끝나면 프로필 선택으로 */
    fun finishOnboarding() {
        if (_current.value == Destination.ONBOARDING) go(Destination.PROFILE)
    }

    /** 뒤로 간다. 더 갈 곳이 없거나(홈, 온보딩, 프로필 선택) 홈에 고정됐으면 false */
    fun back(): Boolean {
        if (lockedToHome) return false
        val prev = backStack.removeLastOrNull() ?: return false
        returned = true
        _current.value = prev
        if (prev.topLevel) onTopLevelEntered(prev)
        return true
    }

    /**
     * 뒤로 가기 키. 갈 화면이 있으면 가고, 맨 위 화면(홈, 온보딩, 프로필 선택)이면 [hide] 를 부른다.
     * Pleos CCP Back 정책: 맨 위 화면에서는 앱을 끄지 않고 숨긴다.
     */
    fun onBackKey(hide: () -> Unit) {
        if (!back()) hide()
    }
}

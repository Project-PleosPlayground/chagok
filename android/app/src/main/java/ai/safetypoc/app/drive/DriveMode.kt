package ai.safetypoc.app.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 주행 모드. 주행 시작 버튼을 눌렀거나, 엔진 세션이 열려 있거나, 시스템 주행 규제([restricted])가 걸려 있으면 주행 중이다.
 * 버튼은 정차 중에 누르고 출발하는 것을 전제로 한다. 엔진 세션은 출발하면 열린다(스펙_안전점수 3 "시작").
 * 주행 규제는 기어가 P 가 아니면 정차 중에도 걸리므로, 출발 전 D 단 정차와 세션이 끝난 뒤의 D 단 정차도 주행 중이다.
 */
class DriveMode(sessionStartMs: StateFlow<Long?>, restricted: StateFlow<Boolean>, scope: CoroutineScope) {
    private val pressed = MutableStateFlow(false)

    val driving: StateFlow<Boolean> = combine(pressed, sessionStartMs, restricted) { p, s, r -> p || s != null || r }
        .stateIn(scope, SharingStarted.Eagerly, sessionStartMs.value != null || restricted.value)

    /** 주행 시작 버튼 */
    fun start() {
        pressed.value = true
    }

    /** 버튼으로 들어간 주행 모드를 끝낸다. 엔진 세션이 열려 있거나 주행 규제가 걸려 있으면 주행 중으로 남는다 */
    fun clear() {
        pressed.value = false
    }
}

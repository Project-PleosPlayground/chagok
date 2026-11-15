package ai.safetypoc.app

import ai.safetypoc.app.voice.VoicePlayer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import engine.FeedbackCue
import engine.DefaultSafetyScoreEngine
import engine.DrivingEvent
import engine.EngineConfig
import engine.SafetyScoreEngine
import engine.SessionLifecycle
import engine.SessionResult
import engine.SignalSource
import engine.VehicleSignal
import kotlin.coroutines.CoroutineContext

/** 화면이 보는 상태. 시연·디버그용. */
data class DriveUiState(
    val running: Boolean = false,
    val sessionState: String = "대기",
    val partialScore: Int = 100,
    val recentEvents: List<DrivingEvent> = emptyList(),
    val lastCue: String = "",
    val lastResult: SessionResult? = null,
    val lastSignal: String = "",
    /**
     * 정차 중인지. 마지막 속도 신호가 [EngineConfig.sessionIdleSpeedMps] 이하면 정차다.
     * 속도 신호를 받기 전에는 정차로 본다. 신호가 끊기면 마지막 속도로 판단한다(움직이던 중 끊기면 주행 중으로 남는다).
     */
    val stopped: Boolean = true,
    /** 마지막 캐릭터 반응(효과음과 같은 cue). 주행 화면이 말풍선·몸짓을 보인다. 효과음을 꺼도 들어온다 */
    val reaction: FeedbackCue? = null,
)

/**
 * 엔진을 소유하고 신호 입력, 1초 틱, 출력 구독을 묶는다.
 *
 * - 엔진은 스레드 안전하지 않다고 보고, 신호·틱·수동 종료를 한 줄짜리 디스패처([engineDispatcher])로 모아 넣는다.
 * - 출력 Flow 는 엔진을 만든 직후 구독한다. 늦게 구독하면 그 전 이벤트를 놓친다 (엔진 리뷰 ":app 쪽에서 쓸 때").
 * - 끝난 주행은 자동 종료(정차)든 수동 종료([stop])든 같은 길로 [resultContext] 에서 [onSessionEnded] 에 한 번 넘긴다.
 *   [driveStartMs] 는 그 처리가 끝난 뒤 지운다. 처리 전에 미션 틱이 돌아도 주행 보전이 빠지지 않는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineHost(
    private val sources: List<SignalSource>,
    private val voice: VoicePlayer,
    private val config: EngineConfig = AppConfig.engine,
    /** 주행(세션)이 끝날 때마다 한 번. [resultContext] 에서 부른다 */
    private val onSessionEnded: (SessionResult) -> Unit = {},
    /** 세션 시작·인정·끝. [resultContext] 에서 엔진이 낸 순서대로 부른다(주행 중 미션 진행도, 07 "판정 시점") */
    private val onLifecycle: (SessionLifecycle) -> Unit = {},
    /** 엔진 이벤트(급제동 등). [resultContext] 에서 엔진이 낸 순서대로 부른다 */
    private val onEvent: (DrivingEvent) -> Unit = {},
    private val engineDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** 끝난 주행을 처리하는 곳. 앱에서는 메인 스레드 */
    resultContext: CoroutineContext = Dispatchers.Main,
    /** 틱과 수동 종료에 쓰는 시각(epoch ms) */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** 메인에서 바꾸고 엔진 스레드에서도 읽는다 */
    @Volatile private var scope: CoroutineScope? = null
    private var engine: SafetyScoreEngine? = null
    private val results = CoroutineScope(SupervisorJob() + resultContext)
    /** [results] 에서만 읽고 쓴다. 같은 주행이 수동 종료 반환값과 sessions Flow 로 두 번 올 수 있어 시작 시각으로 거른다 */
    private var deliveredStartMs: Long? = null

    private val _state = MutableStateFlow(DriveUiState())
    val state: StateFlow<DriveUiState> = _state.asStateFlow()

    private val _driveStartMs = MutableStateFlow<Long?>(null)
    /** 지금 주행 중이면 그 주행의 시작 시각(wall-clock, epoch ms), 아니면 null. 미션 주행 보전에 쓴다 */
    val driveStartMs: StateFlow<Long?> = _driveStartMs.asStateFlow()

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + engineDispatcher)
        val e = DefaultSafetyScoreEngine(config)
        scope = s
        engine = e

        subscribe(s, e)
        voice.start()
        sources.forEach { it.start { signal -> onSignal(s, e, signal) } }
        startTicker(s, e)
        _state.update { it.copy(running = true) }
    }

    /** 신호 입력을 멈추고 진행 중 세션을 닫는다. 닫은 세션은 자동 종료와 같이 [onSessionEnded] 로 간다 */
    /** 엔진 밖에서 정한 캐릭터 반응(배터리 꼬르륵 등): 효과음을 내고 화면 반응을 띄운다 */
    fun react(cue: FeedbackCue) {
        voice.play(cue)
        _state.update { it.copy(lastCue = "${cue.type} #${cue.variant}", reaction = cue) }
    }

    fun stop() {
        val s = scope ?: return
        val e = engine ?: return
        scope = null
        engine = null
        sources.forEach { it.stop() }
        // 음성은 메인에서 바로 놓는다. 엔진 작업 뒤로 미루면 곧바로 start() 한 새 주행의 음성까지 놓을 수 있다
        voice.release()
        _state.update { it.copy(running = false, sessionState = "대기", stopped = true) }
        s.launch {
            e.endSessionNow(clock())?.let(::deliver)
            s.cancel()
        }
    }

    /** 신호 입력은 그대로 두고 진행 중 세션만 닫는다. 닫은 세션은 자동 종료와 같이 [onSessionEnded] 로 간다 */
    fun endSession() {
        val s = scope ?: return
        val e = engine ?: return
        s.launch { e.endSessionNow(clock())?.let(::deliver) }
    }

    private fun onSignal(s: CoroutineScope, e: SafetyScoreEngine, signal: VehicleSignal) {
        s.launch {
            e.onSignal(signal)
            // stop() 뒤에 처리되는 남은 신호는 화면 상태를 바꾸지 않는다
            if (scope !== s) return@launch
            _state.update {
                it.copy(
                    lastSignal = signal.toString(), partialScore = e.currentPartialScore(),
                    stopped = if (signal is VehicleSignal.Speed) signal.mps <= config.sessionIdleSpeedMps else it.stopped,
                )
            }
        }
    }

    private fun startTicker(s: CoroutineScope, e: SafetyScoreEngine): Job = s.launch {
        while (isActive) {
            e.onTick(clock())
            _state.update { it.copy(partialScore = e.currentPartialScore()) }
            delay(AppConfig.TICK_INTERVAL_MS)
        }
    }

    private fun deliver(r: SessionResult) {
        results.launch {
            if (r.startMs == deliveredStartMs) return@launch
            deliveredStartMs = r.startMs
            _state.update { it.copy(lastResult = r) }
            onSessionEnded(r)
            _driveStartMs.compareAndSet(r.startMs, null)
        }
    }

    private fun subscribe(s: CoroutineScope, e: SafetyScoreEngine) {
        s.launch {
            e.events.collect { ev ->
                _state.update { it.copy(recentEvents = (listOf(ev) + it.recentEvents).take(AppConfig.RECENT_EVENT_LIMIT)) }
                results.launch { onEvent(ev) }
            }
        }
        s.launch {
            e.feedback.collect { cue ->
                voice.play(cue)
                _state.update { it.copy(lastCue = "${cue.type} #${cue.variant}", reaction = cue) }
            }
        }
        s.launch {
            e.lifecycle.collect { lc ->
                // 새 주행이 시작되면 지난 주행의 마지막 반응이 다시 뜨지 않게 지운다 (11/15)
                if (lc is SessionLifecycle.Started) { _driveStartMs.value = lc.startMs; _state.update { it.copy(reaction = null) } }
                results.launch { onLifecycle(lc) }
                val label = when (lc) {
                    is SessionLifecycle.Started -> "주행 중 (인정 전)"
                    is SessionLifecycle.Validated -> "주행 중 (인정됨)"
                    is SessionLifecycle.Ended -> "종료"
                }
                _state.update { it.copy(sessionState = label) }
            }
        }
        s.launch { e.sessions.collect(::deliver) }
    }
}

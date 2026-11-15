package engine

import kotlinx.coroutines.flow.Flow

/**
 * 스펙 6절. 엔진은 시스템 시계를 읽지 않는다. 시간은 VehicleSignal.timestampMs 와 onTick(nowMs) 로만 들어온다.
 * Android 에서는 1초마다 onTick, JUnit 에서는 JSON 시나리오의 타임스탬프를 그대로 넣는다.
 */
interface SafetyScoreEngine {
    fun onSignal(signal: VehicleSignal)
    fun onTick(nowMs: Long)

    /** 이벤트 확정 즉시. severity 는 잠정값. 세션 인정 전에도 방출. */
    val events: Flow<DrivingEvent>
    /** Started / Validated(인정 전 이벤트 전부 포함) / Ended */
    val lifecycle: Flow<SessionLifecycle>
    /** FeedbackDecider 가 4-5 규칙을 통과시킨 발화만 */
    val feedback: Flow<FeedbackCue>
    /** 정차로 자동 종료된 세션 */
    val sessions: Flow<SessionResult>

    /** 강제 종료 (앱 종료 직전 등). 진행 중 세션이 없으면 null. */
    fun endSessionNow(nowMs: Long): SessionResult?
    /** 주행 중 표시용. 세션이 없으면 100. */
    fun currentPartialScore(): Int

    /**
     * 다음 세션부터 쓸 설정 (원격 조정 수치, 스펙 6-1). 진행 중인 세션에는 적용하지 않는다(세션 고정).
     * 세션이 시작되기 전에 여러 번 부르면 마지막 것만 쓴다. 다른 스레드에서 불러도 된다.
     * 재표본화 간격 등 신호 처리 구조 수치가 다르면 IllegalArgumentException.
     */
    fun updateConfig(next: EngineConfig)
}

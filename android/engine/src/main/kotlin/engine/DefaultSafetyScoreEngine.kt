package engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max

/**
 * 스펙 2·3·4·5절 구현. 순수 Kotlin.
 *
 * 시계: 신호의 timestampMs 와 onTick(nowMs) 만 쓴다. 신호가 오면 그 시각 직전까지의 100ms 격자를 먼저 처리하고(ZOH),
 * 그 다음 값을 반영한다. 틱이 오면 틱 시각까지의 격자를 처리한다. 모든 판정은 격자 샘플에서만 일어난다.
 */
class DefaultSafetyScoreEngine(
    initialConfig: EngineConfig,
    /** 설정의 발화 규칙으로 FeedbackDecider 를 만든다. 세션 시작 때 설정이 바뀌면 다시 만든다 */
    private val deciderFactory: (FeedbackConfig) -> FeedbackDecider = ::DefaultFeedbackDecider,
) : SafetyScoreEngine {

    /** 지금 쓰는 설정. 세션 시작 때만 바뀐다(세션 고정, 스펙 6-1) */
    private var cfg: EngineConfig = initialConfig
    /** [updateConfig] 로 받아 다음 세션 시작 때 쓸 설정. 다른 스레드에서 넣을 수 있다 */
    private val pendingConfig = AtomicReference<EngineConfig?>(null)

    private val _events = MutableSharedFlow<DrivingEvent>(extraBufferCapacity = 1 shl 14)
    private val _lifecycle = MutableSharedFlow<SessionLifecycle>(extraBufferCapacity = 1 shl 10)
    private val _feedback = MutableSharedFlow<FeedbackCue>(extraBufferCapacity = 1 shl 12)
    private val _sessions = MutableSharedFlow<SessionResult>(extraBufferCapacity = 1 shl 8)
    override val events: Flow<DrivingEvent> get() = _events
    override val lifecycle: Flow<SessionLifecycle> get() = _lifecycle
    override val feedback: Flow<FeedbackCue> get() = _feedback
    override val sessions: Flow<SessionResult> get() = _sessions

    // ── 최신 원시값 (ZOH) ─────────────────────────────────────────────
    private var speedRaw: Float? = null
    private var lastSpeedCbMs: Long? = null
    private var resumeExcludeUntil = Long.MIN_VALUE
    private var brakePressed = false
    private var steering: Float? = null
    private var turn = TurnSignalState.OFF
    private var gear = GearState.UNKNOWN
    private var gearKnown = false
    private var tripMeters: Float? = null
    private var tripMode = false

    // ── 격자·버퍼 ────────────────────────────────────────────────────
    private var nextGrid: Long? = null
    private val rawSpeeds = ArrayDeque<Float>()
    private val ring = ArrayDeque<Sample>()
    private val ringCap = (cfg.ringBufferMs / cfg.resampleMs).toInt()

    // ── 기어·후진 ────────────────────────────────────────────────────
    private var inReverse = false
    private var detectionOffUntil = Long.MIN_VALUE

    // ── 세션 ─────────────────────────────────────────────────────────
    private var session: SessionState? = null
    private var aboveSince: Long? = null

    // 설정에 묶인 검출기와 발화 결정기. 처음과 세션 시작 때 모두 useConfig 한 곳에서 만든다
    private lateinit var accelDet: EventDetector
    private lateinit var brakeDet: EventDetector
    private lateinit var lane: LaneChangeTracker
    private lateinit var decider: FeedbackDecider
    private var pendingBrakeCue: DrivingEvent? = null
    private var brakeStableSince: Long? = null

    init { useConfig(initialConfig) }

    private class DetStep(val opened: DrivingEvent? = null, val closed: DrivingEvent? = null, val finalized: DrivingEvent? = null)
    private enum class St { IDLE, STAGE1, STAGE2 }

    private class Sample(
        val t: Long, val vRaw: Float, val vSmooth: Float, val brake: Boolean,
        val steering: Float?, val turn: TurnSignalState, val excluded: Boolean,
    )

    private class SessionState(val startMs: Long) {
        var distanceM = 0f
        var idleSince: Long? = null
        var validated = false
        val events = ArrayList<DrivingEvent>()
    }

    // ── 입력 ─────────────────────────────────────────────────────────
    override fun onSignal(signal: VehicleSignal) {
        advanceTo(signal.timestampMs - 1)
        when (signal) {
            is VehicleSignal.Speed -> {
                val prev = lastSpeedCbMs; val prevV = speedRaw
                if (prev != null && prevV != null && signal.timestampMs - prev >= cfg.staleAfterMs && prevV > cfg.staleMinSpeedMps) {
                    resumeExcludeUntil = signal.timestampMs + cfg.resumeExcludeMs
                }
                speedRaw = signal.mps; lastSpeedCbMs = signal.timestampMs
            }
            is VehicleSignal.BrakePedal -> brakePressed = signal.pressed
            is VehicleSignal.SteeringAngle -> steering = signal.degrees
            is VehicleSignal.TurnSignal -> turn = signal.state
            is VehicleSignal.Gear -> { gear = signal.gear; gearKnown = true }
            is VehicleSignal.TripDistance -> {
                tripMode = true
                val prev = tripMeters
                val s = session
                if (prev != null && s != null && !inReverse) s.distanceM += max(0f, signal.meters - prev)
                tripMeters = signal.meters
            }
            is VehicleSignal.SpeedLimit -> Unit // SPEEDING 은 POC 제외
        }
    }

    override fun onTick(nowMs: Long) {
        advanceTo(nowMs)
        decider.onTick(nowMs)?.let { _feedback.tryEmit(it) }
    }

    override fun endSessionNow(nowMs: Long): SessionResult? {
        advanceTo(nowMs)
        val s = session ?: return null
        return endSession(s, s.idleSince ?: nowMs)
    }

    override fun updateConfig(next: EngineConfig) {
        require(sameStructure(next, cfg)) {
            "신호 처리 구조 수치(재표본화·평활·가속도 창·링 버퍼)는 실행 중에 바꿀 수 없다"
        }
        pendingConfig.set(next)
    }

    /** 격자·버퍼 크기를 정하는 수치가 같은지. 이 값들은 원격 조정 대상이 아니다(스펙 6-1) */
    private fun sameStructure(a: EngineConfig, b: EngineConfig) =
        a.resampleMs == b.resampleMs && a.smoothingSamples == b.smoothingSamples &&
            a.accelWindowMs == b.accelWindowMs && a.ringBufferMs == b.ringBufferMs

    /** 세션 시작 직전에만 부른다. 검출기와 발화 결정기를 새 설정으로 다시 만든다 */
    private fun useConfig(next: EngineConfig) {
        cfg = next
        accelDet = EventDetector(EventType.HARSH_ACCEL, cfg.thresholds.accel1, cfg.thresholds.accel2)
        brakeDet = EventDetector(EventType.HARSH_BRAKE, cfg.thresholds.brake1, cfg.thresholds.brake2)
        lane = LaneChangeTracker()
        decider = deciderFactory(cfg.feedback)
    }

    override fun currentPartialScore(): Int {
        val s = session ?: return 100
        val all = s.events + listOfNotNull(accelDet.openEvent(), brakeDet.openEvent())
        return Scoring.sessionScore(Scoring.breakdown(all, s.distanceM / 1000f, cfg))
    }

    // ── 격자 처리 ───────────────────────────────────────────────────
    private fun advanceTo(limitMs: Long) {
        var g = nextGrid ?: run {
            // 첫 시각을 격자 원점으로. 그 이전은 처리할 게 없다.
            val first = (limitMs + 1) / cfg.resampleMs * cfg.resampleMs
            nextGrid = first; first
        }
        while (g <= limitMs) {
            processGrid(g)
            g += cfg.resampleMs
        }
        nextGrid = g
    }

    private fun processGrid(g: Long) {
        val v = speedRaw ?: 0f
        val stale = lastSpeedCbMs?.let { g - it >= cfg.staleAfterMs && v > cfg.staleMinSpeedMps } ?: false
        val excluded = stale || g < resumeExcludeUntil

        rawSpeeds.addLast(v); while (rawSpeeds.size > cfg.smoothingSamples) rawSpeeds.removeFirst()
        val vS = rawSpeeds.sum() / rawSpeeds.size

        val reverseNow = gear == GearState.R || (gear == GearState.UNKNOWN && v < 0f)
        if (inReverse && !reverseNow) detectionOffUntil = g + cfg.reverseExitHoldMs
        inReverse = reverseNow
        val detectionOn = gear != GearState.P && !reverseNow && g >= detectionOffUntil

        ring.addLast(Sample(g, v, vS, brakePressed, steering, turn, excluded))
        while (ring.size > ringCap) ring.removeFirst()

        val s = session
        if (s == null) { maybeStart(g, v); return }

        if (!tripMode && !reverseNow && !stale) s.distanceM += max(v, 0f) * cfg.resampleMs / 1000f

        if (abs(v) < cfg.sessionIdleSpeedMps) { if (s.idleSince == null) s.idleSince = g } else s.idleSince = null
        s.idleSince?.let { if (g - it >= cfg.sessionEndIdleMs) { endSession(s, it); return } }

        val a = accelAt()
        val startV = speedAtWindowStart()
        val brakeInWindow = ring.takeLast(cfg.accelWindowSamples + 1).any { it.brake }

        val accelCond = detectionOn && a != null && a >= cfg.thresholds.accel1
        val brakeCond = detectionOn && a != null && a <= -cfg.thresholds.brake1 && brakeInWindow && v >= cfg.brakeMinEndSpeedMps

        accelDet.step(g, accelCond, a ?: 0f, startV, "pedal=$brakePressed").apply {
            opened?.let { _events.tryEmit(it) }
            closed?.let { ev -> decider.onEvent(ev, g)?.let { _feedback.tryEmit(it) } }
            finalized?.let { s.events += it }
        }
        brakeDet.step(g, brakeCond, -(a ?: 0f), startV, "pedal=$brakePressed").apply {
            opened?.let { _events.tryEmit(it); pendingBrakeCue = null; brakeStableSince = null }
            closed?.let { pendingBrakeCue = it; brakeStableSince = null }
            finalized?.let { s.events += it; if (pendingBrakeCue != null) pendingBrakeCue = it }
        }
        // 급제동 발화: 종료 후 a > -1.0 m/s² 가 brakeStableMs 유지된 뒤
        pendingBrakeCue?.let { ev ->
            if (a != null && a > cfg.feedback.brakeStableAccel) { if (brakeStableSince == null) brakeStableSince = g } else brakeStableSince = null
            brakeStableSince?.let { since ->
                if (g - since >= cfg.feedback.brakeStableMs) {
                    decider.onEvent(ev, g)?.let { _feedback.tryEmit(it) }
                    pendingBrakeCue = null; brakeStableSince = null
                }
            }
        }

        val th = steering
        if (detectionOn && v >= cfg.laneChange.minSpeedMps && th != null) {
            lane.step(g, v, th)?.let { ev ->
                s.events += ev
                _events.tryEmit(ev)
                decider.onEvent(ev, g)?.let { _feedback.tryEmit(it) }
            }
        } else lane.cancel()

        if (!s.validated && s.distanceM / 1000f >= cfg.minValidKm && g - s.startMs >= cfg.minValidMs) {
            s.validated = true
            val soFar = (s.events + listOfNotNull(accelDet.openEvent(), brakeDet.openEvent())).sortedBy { it.startMs }
            _lifecycle.tryEmit(SessionLifecycle.Validated(g, soFar))
        }
    }

    private fun maybeStart(g: Long, v: Float) {
        if (speedRaw == null || v <= cfg.sessionStartSpeedMps) { aboveSince = null; return }
        val start = if (gearKnown) gear != GearState.P else {
            val since = aboveSince ?: g.also { aboveSince = g }
            g - since >= cfg.sessionStartHoldNoGearMs
        }
        if (!start) return
        aboveSince = null
        pendingConfig.getAndSet(null)?.let { useConfig(it) }
        val s = SessionState(g)
        session = s
        accelDet.reset(); brakeDet.reset(); lane.reset(); pendingBrakeCue = null; brakeStableSince = null
        decider.onSessionStarted(g)
        _lifecycle.tryEmit(SessionLifecycle.Started(g))
    }

    private fun endSession(s: SessionState, endMs: Long): SessionResult {
        accelDet.forceFinalize()?.let { s.events += it }
        brakeDet.forceFinalize()?.let { s.events += it }
        lane.cancel()
        val events = s.events.sortedBy { it.startMs }
        val km = s.distanceM / 1000f
        val b = Scoring.breakdown(events, km, cfg)
        val result = SessionResult(
            startMs = s.startMs, endMs = endMs, distanceKm = km, events = events,
            score = if (s.validated) Scoring.sessionScore(b) else 0,
            accelScore = if (s.validated) Scoring.accelScore(b) else 0,
            brakeScore = if (s.validated) Scoring.brakeScore(b) else 0,
            mannerScore = if (s.validated) Scoring.mannerScore(b) else 0,
            valid = s.validated,
            paramsVersion = cfg.paramsVersion,
        )
        session = null; pendingBrakeCue = null; brakeStableSince = null
        decider.onSessionEnded()
        _lifecycle.tryEmit(SessionLifecycle.Ended(result))
        _sessions.tryEmit(result)
        return result
    }

    /** a(t) = (v(t) - v(t-1s)) / 1s. 창이 stale·재개 제외 구간을 가로지르면 null. */
    private fun accelAt(): Float? {
        val n = cfg.accelWindowSamples
        if (ring.size <= n) return null
        val cur = ring.last(); val prev = ring.elementAt(ring.size - 1 - n)
        for (i in (ring.size - 1 - n) until ring.size) if (ring.elementAt(i).excluded) return null
        return (cur.vSmooth - prev.vSmooth) * 1000f / cfg.accelWindowMs
    }

    private fun speedAtWindowStart(): Float {
        val n = cfg.accelWindowSamples
        return if (ring.size > n) ring.elementAt(ring.size - 1 - n).vSmooth else ring.lastOrNull()?.vSmooth ?: 0f
    }

    // ── 급가속·급제동 공통 검출기 (4-0) ────────────────────────────────
    private inner class EventDetector(val type: EventType, val t1: Float, val t2: Float) {
        private var candStart: Long? = null
        private var candCount = 0
        private var candMax = 0f
        private var candStartSpeed = 0f
        private var open: Open? = null

        private inner class Open(val startMs: Long, val startSpeed: Float, var maxMag: Float, var lastTrue: Long, var closedAt: Long?, var detail: String)

        fun reset() { candStart = null; candCount = 0; open = null }

        fun step(g: Long, cond: Boolean, mag: Float, startSpeed: Float, detail: String): DetStep {
            val o = open
            if (cond) {
                if (o != null) {
                    o.maxMag = max(o.maxMag, mag); o.lastTrue = g; o.closedAt = null
                    return DetStep()
                }
                if (candStart == null) { candStart = g; candCount = 0; candMax = 0f; candStartSpeed = startSpeed }
                candCount++; candMax = max(candMax, mag)
                if (candCount >= cfg.persistSamples) {
                    val n = Open(candStart!!, candStartSpeed, candMax, g, null, detail)
                    open = n; candStart = null
                    return DetStep(opened = toEvent(n, g))
                }
                return DetStep()
            }
            candStart = null
            if (o == null) return DetStep()
            val closedAt = o.closedAt
            if (closedAt == null) { o.closedAt = g; return DetStep(closed = toEvent(o, o.lastTrue)) }
            if (g - closedAt >= cfg.cooldownMs) { open = null; return DetStep(finalized = toEvent(o, o.lastTrue)) }
            return DetStep()
        }

        fun openEvent(): DrivingEvent? = open?.let { toEvent(it, it.lastTrue) }
        fun forceFinalize(): DrivingEvent? = open?.let { toEvent(it, it.lastTrue) }.also { open = null; candStart = null }

        private fun toEvent(o: Open, endMs: Long): DrivingEvent {
            val sev = if (cfg.severityMode == SeverityMode.NONE) 1 else if (o.maxMag >= t2) 2 else 1
            val signed = if (type == EventType.HARSH_BRAKE) -o.maxMag else o.maxMag
            return DrivingEvent(
                type = type, severity = sev, startMs = o.startMs, endMs = endMs, speedMps = o.startSpeed,
                fromStop = type == EventType.HARSH_ACCEL && o.startSpeed < cfg.sessionStartSpeedMps,
                detail = "max a=%.2fm/s², %s".format(signed, o.detail),
            )
        }
    }

    // ── 차선변경 추정 + 깜빡이 매칭 (4-3 a·b) ─────────────────────────
    private inner class LaneChangeTracker {
        private val lc = cfg.laneChange
        private var st = St.IDLE
        private var t0 = 0L; private var t2 = 0L
        private var d = 0; private var base = 0f; private var theta1 = 0f
        private var sameDirSinceT0 = true
        private var v0 = 0f
        private var cooldownUntil = Long.MIN_VALUE

        fun reset() { st = St.IDLE; cooldownUntil = Long.MIN_VALUE }
        fun cancel() { st = St.IDLE }

        fun step(g: Long, v: Float, theta: Float): DrivingEvent? {
            when (st) {
                St.IDLE -> {
                    if (g < cooldownUntil) return null
                    val th1 = lc.theta1For(v) ?: return null
                    val b = baseline(g, theta)
                    val dev = theta - b
                    if (abs(dev) >= th1) {
                        t0 = g; d = if (dev < 0) -1 else 1; base = b; theta1 = th1; v0 = v
                        sameDirSinceT0 = true; st = St.STAGE1
                    }
                }
                St.STAGE1 -> {
                    val dev = theta - base
                    val sameDir = abs(dev) >= theta1 && sign(dev) == d
                    if (sameDirSinceT0 && sameDir && g - t0 >= lc.curveHoldMs) { st = St.IDLE; return null }   // 커브 확정 → 취소
                    if (!sameDir) sameDirSinceT0 = false
                    if (abs(dev) >= theta1 && sign(dev) == -d) { t2 = g; st = St.STAGE2; return null }
                    if (g - t0 > lc.stage2WithinMs) st = St.IDLE
                }
                St.STAGE2 -> {
                    if (abs(theta - base) < lc.theta0Input) {
                        st = St.IDLE; cooldownUntil = g + lc.cooldownMs
                        return DrivingEvent(matchSignal(), 1, t0, g, v0, detail = "d=${if (d < 0) "L" else "R"} base=%.1f θ1=%.1f".format(base, theta1))
                    }
                    if (g - t2 > lc.returnWithinMs) st = St.IDLE
                }
            }
            return null
        }

        private fun sign(x: Float) = if (x < 0) -1 else 1

        /** t0 직전 baselineWindowMs 의 조향각 중앙값. 없으면 현재 값 (이탈 0). */
        private fun baseline(g: Long, theta: Float): Float {
            val vals = ring.filter { it.t >= g - lc.baselineWindowMs && it.t < g && it.steering != null }.map { it.steering!! }.sorted()
            if (vals.isEmpty()) return theta
            val m = vals.size / 2
            return if (vals.size % 2 == 1) vals[m] else (vals[m - 1] + vals[m]) / 2f
        }

        /** 4-3(b). t0-3s ~ t0+0.5s 안에 d 방향 깜빡이가 켜지고(이미 켜져 있던 것 포함) 1초 이상 유지되면 SIGNALED. */
        private fun matchSignal(): EventType {
            val left = (d < 0) != lc.steeringInverted
            val want = if (left) TurnSignalState.LEFT else TurnSignalState.RIGHT
            val opposite = if (left) TurnSignalState.RIGHT else TurnSignalState.LEFT
            val from = t0 - lc.signalWindowBeforeMs; val to = t0 + lc.signalWindowAfterMs
            fun longestRunStartingIn(state: TurnSignalState): Long? {
                var best: Long? = null
                var runStart: Long? = null
                var prev: Sample? = null
                for (s in ring) {
                    val on = s.turn == state
                    if (on && (prev == null || prev.turn != state)) runStart = s.t
                    if (!on && runStart != null) {
                        val rs = runStart; val len = s.t - rs
                        if (rs <= to && s.t > from) best = max(best ?: 0L, len)
                        runStart = null
                    }
                    prev = s
                }
                runStart?.let { rs -> val len = ring.last().t + cfg.resampleMs - rs; if (rs <= to) best = max(best ?: 0L, len) }
                return best
            }
            val same = longestRunStartingIn(want)
            if (same != null && same >= lc.signalMinOnMs) return EventType.LANE_CHANGE_SIGNALED
            if (same != null) return EventType.LANE_CHANGE_NO_SIGNAL
            val opp = longestRunStartingIn(opposite)
            if (opp != null && opp >= lc.signalMinOnMs) return EventType.LANE_CHANGE_WRONG_SIGNAL
            return EventType.LANE_CHANGE_NO_SIGNAL
        }
    }
}

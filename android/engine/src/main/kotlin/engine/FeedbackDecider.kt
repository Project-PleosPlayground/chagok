package engine

/**
 * 4-5 발화 결정. 순수 로직. "지금 말할지, 무엇을 말할지"만 정하고 재생은 :app 의 VoicePlayer 가 한다.
 * 시간은 엔진이 넘겨주는 nowMs 만 쓴다.
 */
interface FeedbackDecider {
    /** 세션 시작. 인사 cue 는 onTick 에서 startMs + sessionStartDelayMs 에 나온다. */
    fun onSessionStarted(startMs: Long)
    fun onSessionEnded()
    /**
     * 이벤트 확정 시 엔진이 부른다. nowMs 는 "발화 가능 시점" 이다:
     * 급제동은 엔진이 안정(a > -1.0, 1.5초)을 확인한 뒤, 급가속은 종료 시점, 차선변경은 확정 즉시.
     * 급가속은 여기서 null 을 돌려주고, 종료 + accelDelayMs 시각이 지난 첫 onTick 에서 cue 가 나온다(atMs 는 종료 + 지연).
     */
    fun onEvent(event: DrivingEvent, nowMs: Long): FeedbackCue?
    fun onTick(nowMs: Long): FeedbackCue?
}

class DefaultFeedbackDecider(private val cfg: FeedbackConfig) : FeedbackDecider {
    private var sessionStartMs: Long? = null
    private var greeted = false
    private var signaledPraised = false
    private val lastAtByType = HashMap<CueType, Long>()
    private val countByType = HashMap<CueType, Int>()
    private val variantByType = HashMap<CueType, Int>()
    private var lastAnyAt: Long? = null
    /**
     * 말할 시각이 아직 오지 않은 급가속 이벤트. 시각이 되면 onTick 에서 빈도 제한을 거친다.
     * 미리 자리를 잡으면 그보다 먼저 말할 급제동이 전체 간격(F_all)에 걸려 버려진다(지연이 원격으로 길어질 때, 스펙 6-1).
     */
    private val pendingAccel = ArrayDeque<Pair<Long, DrivingEvent>>()

    private val priority = mapOf(
        CueType.HARSH_BRAKE to 4, CueType.LANE_NO_SIGNAL to 3, CueType.HARSH_ACCEL to 2,
        CueType.LANE_SIGNALED_FIRST to 1, CueType.MILESTONE to 1, CueType.SESSION_START to 0,
    )

    override fun onSessionStarted(startMs: Long) {
        sessionStartMs = startMs; greeted = false; signaledPraised = false
        lastAtByType.clear(); countByType.clear(); variantByType.clear(); lastAnyAt = null
        pendingAccel.clear()
    }

    override fun onSessionEnded() { sessionStartMs = null; pendingAccel.clear() }

    override fun onEvent(event: DrivingEvent, nowMs: Long): FeedbackCue? {
        if (sessionStartMs == null) return null
        val (type, atMs) = when (event.type) {
            EventType.HARSH_BRAKE -> CueType.HARSH_BRAKE to nowMs                       // 엔진이 안정 확인 후 호출
            EventType.HARSH_ACCEL -> { pendingAccel.addLast(event.endMs + cfg.accelDelayMs to event); return null }
            EventType.LANE_CHANGE_NO_SIGNAL, EventType.LANE_CHANGE_WRONG_SIGNAL -> CueType.LANE_NO_SIGNAL to nowMs
            EventType.LANE_CHANGE_SIGNALED -> {
                if (!cfg.praiseFirstSignaled || signaledPraised) return null
                CueType.LANE_SIGNALED_FIRST to nowMs
            }
            else -> return null
        }
        val cue = admit(type, atMs, event) ?: return null
        if (type == CueType.LANE_SIGNALED_FIRST) signaledPraised = true
        return cue
    }

    override fun onTick(nowMs: Long): FeedbackCue? {
        val start = sessionStartMs ?: return null
        while (pendingAccel.isNotEmpty() && pendingAccel.first().first <= nowMs) {
            val (dueAt, ev) = pendingAccel.removeFirst()
            admit(CueType.HARSH_ACCEL, dueAt, ev)?.let { return it }
        }
        if (!greeted && nowMs >= start + cfg.sessionStartDelayMs) {
            greeted = true
            return admit(CueType.SESSION_START, nowMs, null)
        }
        return null
    }

    /** 빈도 제한. 같은 타입 F_same, 전체 F_all, 세션당 N_max. 겹치면 나중 것을 버린다(우선순위가 더 높아도 이미 나간 cue 는 못 되돌린다). */
    private fun admit(type: CueType, atMs: Long, src: DrivingEvent?): FeedbackCue? {
        val count = countByType[type] ?: 0
        if (type != CueType.SESSION_START && count >= cfg.maxPerTypePerSession) return null
        lastAtByType[type]?.let { if (atMs - it < cfg.sameTypeMinMs) return null }
        lastAnyAt?.let { if (atMs - it < cfg.anyTypeMinMs) return null }
        val variant = variantByType[type] ?: 0
        variantByType[type] = (variant + 1) % cfg.variantsPerType
        countByType[type] = count + 1
        lastAtByType[type] = atMs
        lastAnyAt = atMs
        return FeedbackCue(type, atMs, variant, src)
    }

    @Suppress("unused") private fun priorityOf(t: CueType) = priority[t] ?: 0
}

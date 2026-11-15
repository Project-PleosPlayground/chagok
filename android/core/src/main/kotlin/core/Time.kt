package core

/** wall-clock 날짜 경계. 기본 KST(+9). 엔진 시계와 섞지 않는다. */
object Time {
    const val DAY_MS = 86_400_000L
    const val KST_OFFSET_MS = 9 * 3_600_000L
    fun dayStart(ms: Long, tzOffsetMs: Long = KST_OFFSET_MS): Long = Math.floorDiv(ms + tzOffsetMs, DAY_MS) * DAY_MS - tzOffsetMs
    fun dayIndex(ms: Long, tzOffsetMs: Long = KST_OFFSET_MS): Long = Math.floorDiv(ms + tzOffsetMs, DAY_MS)
    /** 월요일 00:00 시작 */
    fun weekStart(ms: Long, tzOffsetMs: Long = KST_OFFSET_MS): Long {
        val d = dayIndex(ms, tzOffsetMs)
        val dow = Math.floorMod(d + 3, 7L)   // 1970-01-01 은 목요일 → 월요일 기준 0
        return (d - dow) * DAY_MS - tzOffsetMs
    }
}

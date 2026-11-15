package ai.safetypoc.app.drive

import ai.safetypoc.app.AppConfig
import core.session.InMemorySessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DemoHistoryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val now = LocalDate.of(2026, 11, 14).atTime(19, 0).atZone(zone).toInstant().toEpochMilli()
    private fun date(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    @Test fun `이번 달 오늘 전 날짜에만 넣고, 다시 눌러도 늘지 않는다`() {
        val store = InMemorySessionStore()
        val n = DemoHistory.seed(store, "p", AppConfig.engine, now, zone)
        val all = store.list("p")
        assertEquals(n, all.size)
        val days = all.map { date(it.startWallMs) }.toSet().size
        assertTrue("주행한 날 $days, 기록 $n", days in 9..12 && n >= days)
        assertTrue(all.all { date(it.startWallMs).let { d -> d.monthValue == 11 && d.dayOfMonth < 14 } })
        assertTrue(all.all { it.valid && it.events.all { e -> e.lat != null && e.roadName != null } })
        DemoHistory.seed(store, "p", AppConfig.engine, now, zone)
        assertEquals(n, store.list("p").size)
    }

    @Test fun `이번 주가 그 전보다 점수가 높다`() {
        val store = InMemorySessionStore()
        DemoHistory.seed(store, "p", AppConfig.engine, now, zone)
        val monday = LocalDate.of(2026, 11, 9)
        val (thisWeek, before) = store.list("p").partition { !date(it.startWallMs).isBefore(monday) }
        assertTrue(thisWeek.isNotEmpty() && before.isNotEmpty())
        assertTrue(thisWeek.map { it.score }.average() > before.map { it.score }.average())
    }
}

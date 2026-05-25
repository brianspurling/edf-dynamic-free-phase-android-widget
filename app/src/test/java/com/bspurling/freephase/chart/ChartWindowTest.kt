package com.bspurling.freephase.chart

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ChartWindowTest {

    private val london = ZoneId.of("Europe/London")

    private fun london(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), london).toInstant()

    @Test fun `standard BST day - window spans 48 hours`() {
        val now = london("2026-05-15", "12:00")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(london("2026-05-14", "23:00"), start)
        assertEquals(london("2026-05-16", "23:00"), end)
        assertEquals(Duration.ofHours(48), Duration.between(start, end))
    }

    @Test fun `standard GMT day in winter - window spans 48 hours`() {
        val now = london("2026-01-15", "12:00")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(london("2026-01-14", "23:00"), start)
        assertEquals(london("2026-01-16", "23:00"), end)
        assertEquals(Duration.ofHours(48), Duration.between(start, end))
    }

    @Test fun `now at exactly 23-00 local - window start is now minus 24h`() {
        val now = london("2026-05-15", "23:00")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(now.minus(Duration.ofHours(24)), start)
        assertEquals(now.plus(Duration.ofHours(24)), end)
    }

    @Test fun `now at 00-30 local - window start is 1h 30m earlier`() {
        val now = london("2026-05-15", "00:30")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(now.minus(Duration.ofMinutes(90)), start)
        assertEquals(now.plus(Duration.ofMinutes(60 * 46 + 30)), end)
    }

    @Test fun `spring-forward day - window spans 47 wall-clock hours`() {
        // 2026-03-29: BST starts. Clocks jump 01:00 GMT -> 02:00 BST.
        val now = london("2026-03-29", "12:00")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(london("2026-03-28", "23:00"), start)
        assertEquals(london("2026-03-30", "23:00"), end)
        assertEquals(Duration.ofHours(47), Duration.between(start, end))
    }

    @Test fun `fall-back day - window spans 49 wall-clock hours`() {
        // 2026-10-25: BST ends. Clocks fall 02:00 BST -> 01:00 GMT.
        val now = london("2026-10-25", "12:00")
        val (start, end) = ChartWindow.fixedWindow(now, london)
        assertEquals(london("2026-10-24", "23:00"), start)
        assertEquals(london("2026-10-26", "23:00"), end)
        assertEquals(Duration.ofHours(49), Duration.between(start, end))
    }
}

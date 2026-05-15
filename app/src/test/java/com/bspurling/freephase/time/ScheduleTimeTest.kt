package com.bspurling.freephase.time

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ScheduleTimeTest {

    private val london = ZoneId.of("Europe/London")

    private fun london(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), london).toInstant()

    @Test fun `next 12-30 from morning is today at 12-30`() {
        val now = london("2026-05-15", "10:00")
        val next = nextLocalTime(now, LocalTime.of(12, 30), london)
        assertEquals(london("2026-05-15", "12:30"), next)
    }

    @Test fun `next 12-30 from afternoon is tomorrow at 12-30`() {
        val now = london("2026-05-15", "14:00")
        val next = nextLocalTime(now, LocalTime.of(12, 30), london)
        assertEquals(london("2026-05-16", "12:30"), next)
    }

    @Test fun `delay to next 12-30 is positive`() {
        val now = london("2026-05-15", "12:31")
        val delay = delayToNextLocalTime(now, LocalTime.of(12, 30), london)
        assertEquals(Duration.between(now, london("2026-05-16", "12:30")), delay)
    }

    @Test fun `spring-forward day handled correctly`() {
        // 2026-03-29: BST starts (01:00 GMT = 02:00 BST). Clocks jump 01:00 -> 02:00.
        val now = ZonedDateTime.of(LocalDate.parse("2026-03-28"), LocalTime.parse("23:00"), london).toInstant()
        val next = nextLocalTime(now, LocalTime.of(12, 30), london)
        assertEquals(london("2026-03-29", "12:30"), next)
    }

    @Test fun `fall-back day handled correctly`() {
        // 2026-10-25: BST ends (02:00 BST -> 01:00 GMT). Clocks roll back.
        val now = ZonedDateTime.of(LocalDate.parse("2026-10-25"), LocalTime.parse("00:00"), london).toInstant()
        val next = nextLocalTime(now, LocalTime.of(12, 30), london)
        assertEquals(london("2026-10-25", "12:30"), next)
    }

    @Test fun `endOfTomorrow returns midnight after tomorrow in London`() {
        val now = london("2026-05-15", "10:00")
        val end = endOfTomorrowLocal(now, london)
        assertEquals(london("2026-05-17", "00:00"), end)
    }
}

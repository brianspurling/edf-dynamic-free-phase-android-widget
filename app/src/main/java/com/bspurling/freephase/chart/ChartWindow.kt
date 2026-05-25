package com.bspurling.freephase.chart

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object ChartWindow {

    private val DEFAULT_ZONE = ZoneId.of("Europe/London")
    private val CUTOFF: LocalTime = LocalTime.of(23, 0)

    /**
     * Fixed 48-hour-ish window for the chart x-axis, anchored to the local 23:00
     * boundary on either side of `now`'s local date.
     *
     * Returns (start, end) where:
     *   start = 23:00 [zone] on (now's local date − 1)
     *   end   = 23:00 [zone] on (now's local date + 1)
     *
     * Around DST transitions the window's millisecond length is 47 or 49 hours;
     * callers project in millis so this is transparent.
     */
    fun fixedWindow(now: Instant, zone: ZoneId = DEFAULT_ZONE): Pair<Instant, Instant> {
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays(1).atTime(CUTOFF).atZone(zone).toInstant()
        val end = today.plusDays(1).atTime(CUTOFF).atZone(zone).toInstant()
        return start to end
    }
}

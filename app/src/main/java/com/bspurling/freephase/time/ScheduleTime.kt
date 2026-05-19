package com.bspurling.freephase.time

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** First Instant on or after [now] whose local time in [zone] equals [target]. */
fun nextLocalTime(now: Instant, target: LocalTime, zone: ZoneId): Instant {
    val nowZ = now.atZone(zone)
    val todayCandidate = nowZ.toLocalDate().atTime(target).atZone(zone)
    return if (!todayCandidate.toInstant().isBefore(now)) {
        todayCandidate.toInstant()
    } else {
        todayCandidate.plusDays(1).toInstant()
    }
}

/** Duration from [now] to the next instance of [target] in [zone]. Always ≥ 0. */
fun delayToNextLocalTime(now: Instant, target: LocalTime, zone: ZoneId): Duration =
    Duration.between(now, nextLocalTime(now, target, zone))

/** Local midnight at the end of "tomorrow" (i.e. start of day-after-tomorrow), as a UTC Instant. */
fun endOfTomorrowLocal(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().plusDays(2).atStartOfDay(zone).toInstant()

/** Next half-hour boundary (HH:00 or HH:30) strictly after [now]. */
fun nextHalfHour(now: Instant): Instant {
    val halfHourMs = 30L * 60L * 1000L
    val nowMs = now.toEpochMilli()
    return Instant.ofEpochMilli((nowMs / halfHourMs + 1) * halfHourMs)
}

package com.bspurling.freephase.worker

import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.WorkerDiagnostic
import com.bspurling.freephase.time.endOfTomorrowLocal
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

enum class RefreshTrigger { None, Bootstrap, SafetyNet }

/**
 * Pure function: given the cache, the worker's last diagnostic, and the current time,
 * should `provideGlance` kick off a fetch — and if so, how?
 *
 * - [Bootstrap]: cache is missing entirely or every slot is in the past. Force-replace any
 *   in-flight bootstrap (the placeholder is showing; the user expects a recovery attempt).
 * - [SafetyNet]: cache has current slots but doesn't cover tomorrow yet, we're inside the
 *   EDF publication window, and we haven't attempted within the last [minRetryInterval].
 *   Fired by the half-hour redraw alarm as a backstop against drifted `PeriodicWorkRequest`
 *   firings; the caller force-replaces any stuck bootstrap (see `provideGlance`) so a
 *   failed earlier attempt sitting in WorkManager backoff can't block recovery.
 * - [None]: cache is good, it's not yet time, or we attempted within the last
 *   [minRetryInterval] (so an in-flight fetch isn't hammered).
 *
 * The publication window is [12:30, 18:00) local — matches `decideRefresh`'s 18:00 cutoff
 * and `scheduleRetryAroundPublication`'s 12:30 target.
 *
 * NOTE: we deliberately rate-limit rather than fire once per day. An earlier version
 * suppressed the safety net on *any* attempt recorded since 12:30 — but `recordAttempt`
 * also fires on the Exception path, so a single failed fetch (e.g. the UnknownHostException
 * that's common when the worker wakes from Doze) muted the safety net for the rest of the
 * window, leaving recovery to WorkManager's drifted backoff. Reaching the rate-limit check
 * means the cache *still* lacks tomorrow's slots, so retrying is exactly what we want; we
 * only avoid re-enqueuing on top of a fetch that's still in flight (and the worker's own
 * completion `updateAll` re-entering `provideGlance`).
 */
fun decideRefreshTrigger(
    cached: RateData?,
    diagnostic: WorkerDiagnostic?,
    now: Instant,
    zone: ZoneId,
    minRetryInterval: Duration = Duration.ofMinutes(15),
): RefreshTrigger {
    val hasCurrentSlots = cached != null && cached.slotsFrom(now).isNotEmpty()
    if (!hasCurrentSlots) return RefreshTrigger.Bootstrap

    val coversTomorrow = cached!!.coversThroughTomorrow(endOfTomorrowLocal(now, zone))
    if (coversTomorrow) return RefreshTrigger.None

    val nowLocal = now.atZone(zone)
    val today1230 = nowLocal.toLocalDate().atTime(LocalTime.of(12, 30)).atZone(zone).toInstant()
    val today1800 = nowLocal.toLocalDate().atTime(LocalTime.of(18, 0)).atZone(zone).toInstant()

    val inWindow = !now.isBefore(today1230) && now.isBefore(today1800)
    if (!inWindow) return RefreshTrigger.None

    val attemptedRecently = diagnostic != null &&
        Duration.between(diagnostic.attemptedAt, now) < minRetryInterval
    if (attemptedRecently) return RefreshTrigger.None

    return RefreshTrigger.SafetyNet
}

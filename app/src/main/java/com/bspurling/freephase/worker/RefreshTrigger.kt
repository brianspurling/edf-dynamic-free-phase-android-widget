package com.bspurling.freephase.worker

import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.WorkerDiagnostic
import com.bspurling.freephase.time.endOfTomorrowLocal
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
 * - [SafetyNet]: cache has current slots but doesn't cover tomorrow yet, and we're past
 *   the EDF publication time without the worker having recorded an attempt today.
 *   Fired by the half-hour redraw alarm as a backstop against drifted `PeriodicWorkRequest`
 *   firings — KEEP policy is enough since we don't want to stomp on the periodic worker
 *   if it's already running.
 * - [None]: cache is good, or it's not yet time, or the worker has already attempted today.
 *
 * The publication window is [12:30, 18:00) local — matches `decideRefresh`'s 18:00 cutoff
 * and `scheduleRetryAroundPublication`'s 12:30 target.
 */
fun decideRefreshTrigger(
    cached: RateData?,
    diagnostic: WorkerDiagnostic?,
    now: Instant,
    zone: ZoneId,
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

    val attemptedSinceToday1230 =
        diagnostic != null && !diagnostic.attemptedAt.isBefore(today1230)
    if (attemptedSinceToday1230) return RefreshTrigger.None

    return RefreshTrigger.SafetyNet
}

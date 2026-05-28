package com.bspurling.freephase.worker

import com.bspurling.freephase.data.Slot
import com.bspurling.freephase.time.endOfTomorrowLocal
import java.time.Instant
import java.time.ZoneId

enum class RefreshOutcome { Success, Retry, GiveUp }

/**
 * Pure function: given the slots we just fetched and the current time,
 * should the worker report success, retry, or give up until tomorrow?
 *
 * Retry: tomorrow's slots not yet published, still before [retryUntilLocalHour] local.
 * GiveUp: tomorrow's slots still missing past the cutoff — wait for the next daily run.
 */
fun decideRefresh(
    slots: List<Slot>,
    now: Instant,
    zone: ZoneId,
    retryUntilLocalHour: Int,
): RefreshOutcome {
    val endTomorrow = endOfTomorrowLocal(now, zone)
    val covered = slots.any { it.validTo >= endTomorrow }
    if (covered) return RefreshOutcome.Success
    val nowLocalHour = now.atZone(zone).hour
    return if (nowLocalHour < retryUntilLocalHour) RefreshOutcome.Retry else RefreshOutcome.GiveUp
}

/**
 * Side effects the worker should perform given the fetched [tariff], the [refreshOutcome]
 * from [decideRefresh], and whether the existing cache still has slots covering "now".
 *
 * [persist]            — write `tariff` to the cache?
 * [scheduleRetry]      — schedule a one-shot retry around the next publication window?
 * [workerResultRetry]  — return `Result.retry()` (true) vs `Result.success()` (false)?
 * [label]              — diagnostic outcome string surfaced on the placeholder.
 */
data class WorkPlan(
    val persist: Boolean,
    val scheduleRetry: Boolean,
    val workerResultRetry: Boolean,
    val label: String,
)

fun planWork(
    tariff: List<Slot>,
    refreshOutcome: RefreshOutcome,
    existingHasCurrentSlots: Boolean,
): WorkPlan = when (refreshOutcome) {
    RefreshOutcome.Success -> WorkPlan(
        persist = true, scheduleRetry = false, workerResultRetry = false, label = "Wrote",
    )
    RefreshOutcome.Retry -> if (tariff.isEmpty()) {
        WorkPlan(persist = false, scheduleRetry = true, workerResultRetry = false, label = "Retry")
    } else {
        // Partial publish: write what we have so the user sees most of tomorrow rather
        // than yesterday's stale cache, and still schedule a retry to pick up any
        // straggling slots.
        WorkPlan(persist = true, scheduleRetry = true, workerResultRetry = false, label = "Retry:wrote")
    }
    RefreshOutcome.GiveUp -> when {
        existingHasCurrentSlots -> WorkPlan(
            persist = false, scheduleRetry = false, workerResultRetry = false, label = "GiveUp:kept",
        )
        tariff.isEmpty() -> WorkPlan(
            persist = false, scheduleRetry = false, workerResultRetry = true, label = "Retry",
        )
        else -> WorkPlan(
            persist = true, scheduleRetry = false, workerResultRetry = false, label = "Wrote",
        )
    }
}

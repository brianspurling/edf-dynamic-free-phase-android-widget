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

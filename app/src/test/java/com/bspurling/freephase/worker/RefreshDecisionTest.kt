package com.bspurling.freephase.worker

import com.bspurling.freephase.data.Slot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RefreshDecisionTest {

    private val london = ZoneId.of("Europe/London")

    private fun slot(fromIso: String, toIso: String, pence: Double = 20.0) =
        Slot(Instant.parse(fromIso), Instant.parse(toIso), pence)

    @Test fun `success when slots reach end-of-tomorrow`() {
        val now = Instant.parse("2026-05-15T11:30:00Z") // 12:30 BST
        val slots = listOf(
            slot("2026-05-15T00:00:00Z", "2026-05-15T00:30:00Z"),
            slot("2026-05-16T22:30:00Z", "2026-05-16T23:00:00Z"),
        )
        assertEquals(RefreshOutcome.Success, decideRefresh(slots, now, london, retryUntilLocalHour = 18))
    }

    @Test fun `retry when tomorrow's data missing and still before 18-00 local`() {
        val now = Instant.parse("2026-05-15T11:30:00Z") // 12:30 BST
        val slots = listOf(slot("2026-05-15T00:00:00Z", "2026-05-15T23:30:00Z"))
        assertEquals(RefreshOutcome.Retry, decideRefresh(slots, now, london, retryUntilLocalHour = 18))
    }

    @Test fun `give up when tomorrow's data missing after retry cutoff`() {
        val now = Instant.parse("2026-05-15T17:30:00Z") // 18:30 BST
        val slots = listOf(slot("2026-05-15T00:00:00Z", "2026-05-15T23:30:00Z"))
        assertEquals(RefreshOutcome.GiveUp, decideRefresh(slots, now, london, retryUntilLocalHour = 18))
    }

    @Test fun `retry when results are empty`() {
        val now = Instant.parse("2026-05-15T11:30:00Z")
        assertEquals(RefreshOutcome.Retry, decideRefresh(emptyList(), now, london, retryUntilLocalHour = 18))
    }

    // --- planWork tests ---

    private val anySlot = slot("2026-05-15T00:00:00Z", "2026-05-15T00:30:00Z")

    @Test fun `planWork - Success persists with Wrote label`() {
        val plan = planWork(
            tariff = listOf(anySlot),
            refreshOutcome = RefreshOutcome.Success,
            existingHasCurrentSlots = true,
        )
        assertEquals(WorkPlan(persist = true, scheduleRetry = false, workerResultRetry = false, label = "Wrote"), plan)
    }

    @Test fun `planWork - Retry with empty tariff schedules retry, does not persist`() {
        val plan = planWork(
            tariff = emptyList(),
            refreshOutcome = RefreshOutcome.Retry,
            existingHasCurrentSlots = true,
        )
        assertEquals(WorkPlan(persist = false, scheduleRetry = true, workerResultRetry = false, label = "Retry"), plan)
    }

    @Test fun `planWork - Retry with non-empty tariff persists partial data and schedules retry`() {
        // The new behaviour: if EDF returned 94 of 96 slots, write those 94 anyway so the user sees
        // most of tomorrow's data, then schedule a retry to fetch the last few.
        val plan = planWork(
            tariff = listOf(anySlot),
            refreshOutcome = RefreshOutcome.Retry,
            existingHasCurrentSlots = true,
        )
        assertEquals(WorkPlan(persist = true, scheduleRetry = true, workerResultRetry = false, label = "Retry:wrote"), plan)
    }

    @Test fun `planWork - GiveUp with existing current slots keeps cache`() {
        val plan = planWork(
            tariff = listOf(anySlot),
            refreshOutcome = RefreshOutcome.GiveUp,
            existingHasCurrentSlots = true,
        )
        assertEquals(WorkPlan(persist = false, scheduleRetry = false, workerResultRetry = false, label = "GiveUp:kept"), plan)
    }

    @Test fun `planWork - GiveUp with dead cache and non-empty tariff rescues by writing`() {
        val plan = planWork(
            tariff = listOf(anySlot),
            refreshOutcome = RefreshOutcome.GiveUp,
            existingHasCurrentSlots = false,
        )
        assertEquals(WorkPlan(persist = true, scheduleRetry = false, workerResultRetry = false, label = "Wrote"), plan)
    }

    @Test fun `planWork - GiveUp with dead cache and empty tariff asks WorkManager to retry`() {
        val plan = planWork(
            tariff = emptyList(),
            refreshOutcome = RefreshOutcome.GiveUp,
            existingHasCurrentSlots = false,
        )
        assertEquals(WorkPlan(persist = false, scheduleRetry = false, workerResultRetry = true, label = "Retry"), plan)
    }
}

package com.bspurling.freephase.worker

import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.Slot
import com.bspurling.freephase.data.WorkerDiagnostic
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RefreshTriggerTest {

    private val london = ZoneId.of("Europe/London")

    private fun london(date: String, time: String): Instant =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), london).toInstant()

    private fun slot(fromIso: String, toIso: String, pence: Double = 20.0) =
        Slot(Instant.parse(fromIso), Instant.parse(toIso), pence)

    private fun rateData(slots: List<Slot>, fetchedAt: Instant) =
        RateData(tariffSlots = slots, svtPence = 24.9, fetchedAt = fetchedAt)

    private fun diag(at: Instant, outcome: String = "Wrote") =
        WorkerDiagnostic(attemptedAt = at, outcome = outcome, detail = null)

    @Test fun `null cache - bootstrap`() {
        val now = london("2026-05-28", "13:00")
        assertEquals(
            RefreshTrigger.Bootstrap,
            decideRefreshTrigger(cached = null, diagnostic = null, now = now, zone = london),
        )
    }

    @Test fun `cache with only past slots - bootstrap`() {
        val now = london("2026-05-28", "13:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-27T00:00:00Z", "2026-05-27T23:30:00Z")),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.Bootstrap,
            decideRefreshTrigger(cached = cached, diagnostic = null, now = now, zone = london),
        )
    }

    @Test fun `cache covers tomorrow - no trigger even in window`() {
        // At 13:00 BST 2026-05-28, end-of-tomorrow = 00:00 BST 2026-05-30 = 23:00 UTC 2026-05-29.
        val now = london("2026-05-28", "13:00")
        val cached = rateData(
            slots = listOf(
                slot("2026-05-28T00:00:00Z", "2026-05-28T23:30:00Z"),
                slot("2026-05-29T22:30:00Z", "2026-05-29T23:00:00Z"),
            ),
            fetchedAt = london("2026-05-28", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-28", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache before 12-30 BST - no trigger`() {
        val now = london("2026-05-28", "11:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 12-30 BST with no attempt today - safety net`() {
        val now = london("2026-05-28", "12:30")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.SafetyNet,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 13-00 BST with no attempt today - safety net`() {
        val now = london("2026-05-28", "13:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.SafetyNet,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 12-30 BST with no diagnostic at all - safety net`() {
        val now = london("2026-05-28", "12:30")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.SafetyNet,
            decideRefreshTrigger(cached = cached, diagnostic = null, now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 13-00 BST with attempt at 12-30 BST today - no trigger`() {
        val now = london("2026-05-28", "13:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-28", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 12-30 BST exactly with attempt at 12-30 BST exactly - no trigger`() {
        // Diagnostic.attemptedAt == today's 12:30 BST: counts as "attempted since today 12:30".
        val now = london("2026-05-28", "12:30")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-28", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-28", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 18-00 BST with no attempt today - no trigger (past window)`() {
        val now = london("2026-05-28", "18:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 17-59 BST with no attempt today - safety net (just inside window)`() {
        val now = london("2026-05-28", "17:59")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.SafetyNet,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `today-only cache at 12-29 BST - no trigger (just before window)`() {
        val now = london("2026-05-28", "12:29")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-27", "12:30"),
        )
        assertEquals(
            RefreshTrigger.None,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-27", "12:30")), now = now, zone = london),
        )
    }

    @Test fun `attempt earlier today before 12-30 still counts as not-yet-attempted-since-12-30`() {
        // E.g. a bootstrap kicked off by FreePhaseApp.onCreate at 09:00 today doesn't count
        // as the daily publication-window attempt — we still want to fetch after 12:30.
        val now = london("2026-05-28", "13:00")
        val cached = rateData(
            slots = listOf(slot("2026-05-28T00:00:00Z", "2026-05-28T22:30:00Z", 18.0)),
            fetchedAt = london("2026-05-28", "09:00"),
        )
        assertEquals(
            RefreshTrigger.SafetyNet,
            decideRefreshTrigger(cached = cached, diagnostic = diag(london("2026-05-28", "09:00")), now = now, zone = london),
        )
    }
}

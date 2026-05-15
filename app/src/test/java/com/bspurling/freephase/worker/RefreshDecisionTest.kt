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
}

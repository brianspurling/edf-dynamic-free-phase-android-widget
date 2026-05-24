# Chart fixed time-axis window

## Problem

`ChartCanvas` currently projects the x-axis (time) from the first available
slot's `validFrom` to the last slot's `validTo`. That means the chart's
horizontal scale and the position of the "now" vertical line both depend on
how much tariff data we happen to have cached:

- Right after a refresh the chart spans ~32 hours; in the small hours of the
  morning before next-day rates are published it spans ~12 hours.
- "Now" always sits flush against the left edge.
- Day-to-day visual comparison is hard — the same time of day lands in a
  different horizontal position each refresh.

## Goal

Lock the time axis to a fixed 48-hour window — `23:00` the previous local day
to `23:00` the next local day, both in `Europe/London`. The "now" vertical line
slides through that window as the day progresses. Tariff data is drawn only
where slots exist, so the area left of "now" is typically empty and the area
right of "now" fills out to wherever the cache ends.

The `23:00` cutoffs align with the FreePhase tariff's amber→green phase
boundary, so the visible window is a clean green-to-green cycle.

## Non-goals

- No change to caching, refresh, or worker behavior.
- No change to the fixed y-axis range (`-5p..40p`).
- No retention of past slots — the plotted data stays "from now into the
  future, as much as we have."

## Design

### `ChartWindow.kt` (new)

A small helper in `app/src/main/java/com/bspurling/freephase/chart/`:

```kotlin
object ChartWindow {
    /**
     * Returns the fixed time window for the chart, in millis-precise Instants:
     *   start = 23:00 on the day before `now`'s local date
     *   end   = 23:00 on the day after `now`'s local date
     * Zone defaults to Europe/London. Around DST transitions the returned
     * window may be 47 or 49 wall-clock hours; callers use millis-based
     * projection so this is transparent.
     */
    fun fixedWindow(
        now: Instant,
        zone: ZoneId = ZoneId.of("Europe/London"),
    ): Pair<Instant, Instant>
}
```

Implementation: take `now.atZone(zone).toLocalDate()`, derive
`yesterday.atTime(23, 0).atZone(zone)` and `tomorrow.atTime(23, 0).atZone(zone)`,
return their `Instant`s.

### `ChartCanvas.render` changes

1. Replace the existing `xStart`/`xEnd` derivation (currently
   `slots.first().validFrom` and `slots.last().validTo`) with the window
   from `ChartWindow.fixedWindow(now)`.
2. `slots = data.slotsFrom(now)` is unchanged — it already filters to "now and
   later", which is exactly the data we want to draw.
3. The free-phase band rectangles and the tariff `Path` already use `x(t)` for
   each slot's endpoints. With `xStart`/`xEnd` set to the window, slots draw at
   the correct horizontal position automatically. The last slot may extend
   slightly past `windowEnd` — clamp the right endpoint to `plot.right` so it
   doesn't escape the plot rect.
4. The "now" vertical line guard `if (nowMillis in xStart..xEnd)` continues to
   hold by construction (now is between yesterday-23:00 and tomorrow-23:00).
5. The "now" orange dot logic is unchanged.

### `drawAxes` changes

1. Hour-tick iterator currently walks
   `slots.first().validFrom → xEnd` hour-by-hour. Change to walk
   `windowStart → windowEnd`. With the fixed window this will produce up to
   eight ticks (`06`, `16`, `19`, `23` on each of the two visible days). The
   existing `xp in plot.left..plot.right` clip keeps everything within bounds.
2. Day-boundary label currently labels only the next-day boundary it finds in
   `slots`. With the fixed window both `today 00:00` and `tomorrow 00:00` are
   in view. Label both with `EEE d MMM` near the top of the plot, anchored a
   few dp right of the boundary x position.

### Small-bucket behavior

Per brainstorming decision (2026-05-24): the small bucket also uses the fixed
window for visual consistency across sizes. It still hides axes and the "now"
vertical line; only the bands, tariff line, and "now" dot are drawn — at the
fixed window's horizontal scale.

## Edge cases

- **DST spring-forward / fall-back**: the window's millisecond length changes
  (47h or 49h). All projection arithmetic is in millis, so layout is correct.
  `ChartWindowTest` covers both.
- **"Now" position is always in the left half of the window**: because
  yesterday-23:00 is at most 24h before `now` (when `now` is just before
  midnight) and at least ~1h before `now` (just after midnight). The right
  half is the "future" region that fills out as Octopus publishes next-day
  rates around 16:00. This is intended behavior, not a bug.
- **Empty cache**: `data.slotsFrom(now)` returns empty, `render` returns early
  as today. No regression.
- **Cache has only today's tail**: bands and tariff line cover a small region
  near the middle of the plot; everything else is empty. This is the
  user-visible improvement we want.
- **Cache extends past `windowEnd`** (rare — would need next-next-day data):
  trailing slots clip to `plot.right` via the clamp.

## Testing

### New tests

`ChartWindowTest` (JVM, no Robolectric needed):

- Standard day: `now = 2026-05-15T12:00:00 BST` →
  start = `2026-05-14T22:00:00Z` (23:00 BST = 22:00 UTC),
  end   = `2026-05-16T22:00:00Z`.
- Spring-forward: a `now` on the day clocks go forward — verify the window
  spans 47 wall-clock hours but is a contiguous Instant range.
- Fall-back: a `now` on the day clocks go back — verify 49 wall-clock hours.
- `now` at exactly 23:00 local — the window's `start` equals
  `now - 24h`. Verify the implementation picks "yesterday 23:00" rather than
  collapsing the window.
- `now` at 00:30 local — verify the window's `start` is 1.5 hours earlier
  (yesterday 23:00 → 00:30 today is 1h 30m) and `end` is 46.5 hours ahead.

### Updated tests

`ChartCanvasSnapshotTest`: the three existing PNG fixtures
(`chart_small.png`, `chart_medium.png`, `chart_large.png`) need to be
regenerated because the chart geometry changes. Delete the old fixtures, run
the tests once to write fresh fixtures, review visually, and commit.

## Files touched

- `app/src/main/java/com/bspurling/freephase/chart/ChartWindow.kt` (new)
- `app/src/main/java/com/bspurling/freephase/chart/ChartCanvas.kt` (modified)
- `app/src/test/java/com/bspurling/freephase/chart/ChartWindowTest.kt` (new)
- `app/src/test/resources/fixtures/chart_small.png` (regenerated)
- `app/src/test/resources/fixtures/chart_medium.png` (regenerated)
- `app/src/test/resources/fixtures/chart_large.png` (regenerated)

## Out of scope

- Showing yesterday's actuals on the left half of the window (would require
  retaining past slots in the cache).
- Allowing different window widths per bucket.
- Highlighting the "today" portion of the plot differently from "tomorrow."

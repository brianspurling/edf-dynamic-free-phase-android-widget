# FreePhase Android Widget — Design

**Status:** approved 2026-05-15
**Owner:** brianspurling

## Context

Brian is on EDF Energy's *FreePhase Dynamic 12-month HH* tariff (a half-hourly variable tariff with occasional zero-priced slots). EDF publishes today's and tomorrow's rates on https://www.edfenergy.com/tariff-information-labels/freePhase after he submits his postcode. Tomorrow's rates appear shortly after midday UK time.

He wants a homescreen widget on Android that shows the price curve over time, refreshing automatically just after midday each day so the chart always covers as much of "now → end of tomorrow" as the API has published.

## Data source

The EDF page is a React SPA backed by EDF's Kraken (Octopus) API. The relevant endpoints are unauthenticated and publicly readable.

**FreePhase rates** (verified working 2026-05-15, HTTP 200):

```
GET https://api.edfgb-kraken.energy/v1/products/EDF_FREEPHASE_DYNAMIC_12M_HH
        /electricity-tariffs/E-1R-EDF_FREEPHASE_DYNAMIC_12M_HH-{GSP}
        /standard-unit-rates/
    ?period_from={ISO8601 UTC}&period_to={ISO8601 UTC}&page_size=200
```

Returns `{count, next, previous, results: [{value_exc_vat, value_inc_vat, valid_from, valid_to, payment_method}]}`. Slots are half-hourly. The widget filters to `payment_method == "DIRECT_DEBIT"`.

**Free hours encoding**: when EDF allocates a free window, those slots appear in the same response with `value_inc_vat == 0`. In an April–May 2026 sample, this happened on 2 of ~45 days (e.g. 2026-04-30 had 10 free half-hours from 10:00–14:30 BST).

**SVT comparison rate**:

```
GET https://api.edfgb-kraken.energy/v1/products/EDF_STANDARD_VARIABLE
        /electricity-tariffs/E-1R-EDF_STANDARD_VARIABLE-{GSP}
        /standard-unit-rates/
```

Returns at most 2 rows (one per payment method). For `DIRECT_DEBIT`, current value is 24.8955p inc VAT. SVT rarely changes, so we cache it for a week.

**Postcode → GSP region**:

```
GET https://api.edfgb-kraken.energy/v1/industry/grid-supply-points/?postcode={pc}
```

Returns `{results: [{group_id: "_X"}]}` where `X` is the GSP letter. For SW19 6AY this returned `_C`. We don't call this at runtime — Brian's GSP is baked in.

## Architecture (Section 1)

**Project**: a single-module Android app, package `com.bspurling.freephase`. No external backend.

**Stack**:

- Kotlin
- Jetpack Glance for the App Widget (Compose-style declarative widget API, replaces `RemoteViews` boilerplate)
- WorkManager for the scheduled refresh
- OkHttp + kotlinx.serialization for HTTP
- DataStore (Preferences) for caching rates and last-fetched timestamp
- Min SDK 31 (Android 12 — required by Glance), target SDK current

**Configuration baked into `BuildConfig`** (single user, no in-app settings):

- `POSTCODE = "SW19 6AY"` (used for WebView autofill only)
- `GSP_REGION = "C"` (used to construct API tariff codes)

Changing either requires a rebuild.

**Components** — each a single Kotlin file (~50–200 lines):

| Component | Responsibility |
|---|---|
| `KrakenClient` | Pure HTTP: GET rates for a product/tariff/region/period range. Handles pagination. No state. |
| `RateRepository` | Owns the DataStore cache. Exposes `getRates()` (cached, optionally force-refresh). Persists `last_fetched_at`. |
| `RefreshWorker` | `CoroutineWorker`. Fetches FreePhase + SVT, writes to repo, asks Glance to update all widget instances. Encapsulates retry logic. |
| `RefreshScheduler` | Computes next 12:30 `Europe/London`, enqueues the `PeriodicWorkRequest` with the right initial delay. Also enqueues the every-30-min redraw alarm. |
| `FreePhaseWidget` | The `GlanceAppWidget`. Reads cached data, calls `ChartCanvas` to produce a bitmap, displays it. No I/O. |
| `ChartCanvas` | Pure function: `(rates, svtRate, size, isDark) -> Bitmap`. Most of the visual complexity lives here. |
| `MainActivity` | Single-screen Compose activity hosting the EDF WebView with postcode autofill. Launched on widget tap. |

## Data flow & refresh strategy (Section 2)

**Daily refresh job** — `PeriodicWorkRequest`:

- Scheduled for **12:30 Europe/London**, repeat every 24h, with a 30-min flex window.
- Initial delay calculated from current local time to next 12:30.
- Constraint: `NetworkType.CONNECTED`.

**What the worker does**:

1. Computes period range: `period_from = today 00:00 Europe/London (as UTC)`, `period_to = day-after-tomorrow 00:00 Europe/London (as UTC)` — i.e. up to ~48h forward.
2. Calls `KrakenClient` for FreePhase rates.
3. If the SVT cache is older than 7 days, calls `KrakenClient` for SVT too.
4. Checks whether the response covers ≥ end-of-tomorrow local. If not, returns `Result.retry()`.
5. On success: writes `RateData(slots, svtRate, fetchedAt)` to DataStore, calls `FreePhaseWidget.updateAll(context)` to trigger redraw.

**Retry behaviour**: WorkManager exponential backoff (10 min → 20 → 40 → cap 1 h). If retries are still failing by 18:00 local, the worker stops trying until the next scheduled run.

**Bootstrap** — `OneTimeWorkRequest` enqueued from `Application.onCreate()` (first-run install). Also enqueued on `BOOT_COMPLETED` (no-op if data is fresh).

**Redraw without re-fetching** — `AlarmManager` `setExactAndAllowWhileIdle` fires every 30 min on the half-hour to call `FreePhaseWidget.updateAll(context)`, which re-renders the bitmap from cached data with the "now" indicator advanced. No network.

**Time handling**: all internal storage is UTC `Instant`. Display, scheduling, and chart axis use `ZoneId.of("Europe/London")` so DST is handled implicitly.

**Manual refresh**: not exposed. Tap opens the EDF page (Section 4).

## Chart rendering & layout (Section 3)

Glance widgets cannot render arbitrary `Canvas` content directly, and `RemoteViews` cannot horizontally scroll. The widget is therefore a **non-scrollable, fixed-fit snapshot** of the available time range, drawn into a bitmap.

**Render pipeline**:

1. Glance composable reads cached `RateData` and `LocalSize.current`.
2. Calls `ChartCanvas.render(rates, svtRate, size, isDark)` off-main-thread (Dispatchers.Default).
3. `ChartCanvas` creates a `Bitmap` at the right pixel size, draws via `android.graphics.Canvas`, returns it.
4. Glance shows it via `Image(provider = ImageProvider(bitmap), …)`.
5. Bitmap is cached in-memory keyed by `(dataHash, sizeBucket, isDark)`.

**Chart specification**:

- **X-axis**: time, from `now` floored to the previous half-hour, to `valid_to` of the last cached slot (typically ~36 h on a fresh post-12:30 day, ~12 h late evening).
- **Y-axis**: pence/kWh inc VAT. Auto-scaled `0 → ceil(max_rate / 5) * 5`. Origin pinned at zero so free slots are visually distinct.
- **Tariff line**: orange `#E0501E` (EDF `deepOrange`), 2 dp stroke, **stepped** (rates are constant within each half-hour).
- **Free-phase bands**: vertical light-grey rectangles (`#EEEEEE` light / `#3A3A3A` dark) behind the line wherever `value_inc_vat == 0`.
- **SVT line**: blue `#003B5C` (EDF `deepBlue`), 1 dp dashed horizontal line at the SVT rate. Only drawn at the large size bucket.
- **"Now" marker**: thin vertical line (1 dp, mid-grey) at current `Instant`, with a small filled orange dot where it crosses the tariff line.
- **Axis labels**: hour ticks at 00/06/12/18 along the bottom; bold "Fri 16 May" label at day boundaries. Y-axis: 2–3 labels along the left ("10p", "20p", "30p"). Hidden at the small size bucket.
- **Last-updated timestamp**: tiny grey text top-right ("12:34"). If cache > 26 h old, prefix with "⚠ stale" in amber.

**Adaptive size buckets**:

| Bucket | Approx width × height | What's drawn |
|---|---|---|
| Small | ≤ 150 dp wide | Curve + free-phase bands only |
| Medium | 150–280 dp wide | + hour ticks + Y-labels + now marker + timestamp |
| Large | ≥ 280 dp wide AND ≥ 160 dp tall | + SVT line + legend ("FreePhase / SVT") |

Default placement size: 4 × 2 cells. Widget declared `resizeMode="horizontal|vertical"` so it adapts on the homescreen.

**Theme**:

- Follows system dark/light mode.
- Light: white background, EDF orange/blue, light-grey free bands, dark-grey text.
- Dark: `#1A1A1A` background, brighter orange `#FF7A4A`, lighter blue, `#3A3A3A` free bands, light text.

## Tap-to-open behaviour (Section 4)

Widget tap launches `MainActivity` (single-screen Compose activity, full-screen `WebView`).

**App bar** (thin, above the WebView):

- Title: "FreePhase prices"
- Postcode chip: shows `SW19 6AY`, tap-to-copy (fallback when autofill fails)
- Overflow menu: "Open in Chrome" (delegates to system browser via `ACTION_VIEW`)

**WebView load sequence**:

1. Loads `https://www.edfenergy.com/tariff-information-labels/freePhase`.
2. JavaScript enabled. Cookies + DOM storage enabled (EDF's bundle needs them). Default user agent.
3. On `onPageFinished`, injects a JavaScript snippet that:
   - Polls up to ~5 s for `document.querySelector('input[name="postcode"]')`.
   - Sets the value via the native setter so React's controlled-input observer picks it up:
     ```js
     const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
     setter.call(input, 'SW19 6AY');
     input.dispatchEvent(new Event('input', { bubbles: true }));
     ```
   - Polls for the submit button (`button[type="submit"]` matching text "View unit prices") and clicks it.
4. If selectors don't match (EDF ships a new bundle and breaks our autofill), the script silently no-ops. The postcode chip in the app bar lets Brian copy-paste manually.

We accept this fragility — the failure mode is graceful and Brian will notice and tell me.

**Permissions**: only `INTERNET` in the manifest.

## Error handling (Section 5)

| Failure | Behaviour |
|---|---|
| No network on scheduled fetch | `Worker.Result.retry()`; widget keeps last cached chart. |
| API 5xx / timeout | Same as above. |
| API returns empty `results` | Treated as transient — `retry()`. |
| Tomorrow's slots not yet published at 12:30 | Detected by checking response coverage; `retry()` with backoff until 18:00 local, then stop until next day. |
| Cache empty on cold start | Widget renders "Fetching prices…" placeholder; bootstrap worker runs immediately. |
| Cache > 26 h old | Widget still renders, but shows "⚠ stale" badge. |
| DST transition | UTC `Instant`s internally + `Europe/London` only at display + reschedule against local clock = handled implicitly. |
| WebView autofill JS selector miss | Silent no-op; copy-postcode chip handles fallback. |
| No network on widget tap | Standard Android WebView error page. |

## Testing approach (Section 5 cont.)

- **Unit tests (JVM)** — ~10 tests:
  - `KrakenClient`: parses captured API fixtures (committed JSON from the curl probes done 2026-05-15). Verifies pagination, payment-method filtering.
  - Time math: "next 12:30 Europe/London from this `Instant`" — covered for spring-forward + fall-back days.
  - `isFreeSlot(rate)` predicate fed real zero-priced fixtures from 2026-04-30 + 2026-05-07.
  - `RefreshWorker.shouldRetry(response, now)` decision logic.
- **Chart snapshot tests (Robolectric)** — ~3 tests rendering `ChartCanvas` to bitmap at small/medium/large bucket sizes against committed reference PNGs.
- **No instrumentation tests** — widget hosting is awkward in CI; manual test on Brian's phone instead.

**Manual test checklist** (run on Brian's device after each significant change):

1. Fresh install: widget shows "Fetching prices…" then chart within ~30 s.
2. Resize widget through small → medium → large; bucket transitions feel right.
3. Toggle system dark mode; chart re-themes.
4. Set phone clock back to 11:50; trigger refresh; observe `Worker.retry()` path; advance to 12:30.
5. Tap widget; WebView opens; postcode autofills; chart appears.
6. Disable network; tap widget; verify graceful WebView error page.
7. Long idle: verify 30-min redraw advances "now" indicator without network.

## Out of scope

- In-app settings UI (region / postcode hardcoded; rebuild to change).
- Sharing the APK / Play Store distribution (sideload only).
- Historical chart (last month / 3 months) — EDF page has it; widget doesn't need it.
- Push notifications for free-phase windows.
- Gas tariff (FreePhase is electric only).
- Multi-region support.

## Open implementation questions (deferred to implementation plan)

- Exact `Glance` size-bucket thresholds in dp — refine empirically during dev.
- Whether to use `Glance`'s built-in `LocalSize` size mode (`SizeMode.Responsive` vs `Exact`).
- Robolectric vs Paparazzi for snapshot tests — pick when wiring tests.

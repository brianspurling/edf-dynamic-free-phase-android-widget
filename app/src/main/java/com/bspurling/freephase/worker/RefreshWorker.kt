package com.bspurling.freephase.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bspurling.freephase.BuildConfig
import com.bspurling.freephase.api.KrakenClient
import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.RateRepository
import com.bspurling.freephase.data.toSlots
import com.bspurling.freephase.time.delayToNextLocalTime
import com.bspurling.freephase.time.endOfTomorrowLocal
import com.bspurling.freephase.time.nextHalfHour
import com.bspurling.freephase.widget.FreePhaseWidget
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val zone = ZoneId.of("Europe/London")
    private val client = KrakenClient()
    private val repo = RateRepository(appContext)

    override suspend fun doWork(): Result {
        val now = Instant.now()
        // Note: we deliberately don't record "Running" at entry. Doing so would clobber
        // the previous attempt's diagnostic the moment a retry kicks off, leaving the
        // user no time to read failure details on the widget. The diagnostic only
        // updates when an attempt completes, so a previous Exception stays visible
        // until the next attempt produces a new outcome.
        return try {
            val (result, outcome, detail) = doWorkInner(now)
            repo.recordAttempt(now, outcome, detail)
            // Make sure the widget redraws so the user sees the updated diagnostic line
            // (and the chart, if we just wrote fresh data).
            FreePhaseWidget().updateAll(applicationContext)
            result
        } catch (t: Throwable) {
            repo.recordAttempt(now, "Exception", "${t.javaClass.simpleName}: ${t.message}")
            runCatching { FreePhaseWidget().updateAll(applicationContext) }
            Result.retry()
        }
    }

    private suspend fun doWorkInner(now: Instant): Triple<Result, String, String?> {
        val from = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val to = endOfTomorrowLocal(now, zone)

        val tariff = client.fetchTariffRates(
            productCode = FREEPHASE_PRODUCT,
            gspRegion = BuildConfig.GSP_REGION,
            from = from,
            to = to,
        ).toSlots()

        val outcome = decideRefresh(tariff, now, zone, retryUntilLocalHour = 18)
        val existing = repo.read()
        val plan = planWork(
            tariff = tariff,
            refreshOutcome = outcome,
            existingHasCurrentSlots = existing?.slotsFrom(now)?.isNotEmpty() == true,
        )

        if (plan.scheduleRetry) {
            // Don't use WorkManager's exponential backoff — it caps at 1h and can easily
            // skate past EDF's ~12:30 publication window for tomorrow's rates, leaving us
            // stuck for the rest of the day. Instead schedule an explicit one-shot retry
            // at 12:30 BST (or, if we're already past 12:30, at the next half-hour boundary).
            scheduleRetryAroundPublication(applicationContext, now, zone)
        }

        val workerResult = if (plan.workerResultRetry) Result.retry() else Result.success()

        if (!plan.persist) {
            val detail = when (plan.label) {
                "Retry" -> if (tariff.isEmpty() && outcome == RefreshOutcome.GiveUp) {
                    "past 18:00 but got 0 slots"
                } else {
                    "got ${tariff.size}, retry scheduled"
                }
                "GiveUp:kept" -> "fetched ${tariff.size}, kept cache"
                else -> "got ${tariff.size}"
            }
            return Triple(workerResult, plan.label, detail)
        }

        val cachedSvt = existing?.svtPence
        val svt = if (repo.svtCacheStale()) {
            val svtDtos = client.fetchTariffRates(
                productCode = SVT_PRODUCT,
                gspRegion = BuildConfig.GSP_REGION,
                from = from,
                to = to,
            )
            // SVT endpoint returns historical bands; pick the currently-active one
            // (validTo == null, i.e. open-ended), falling back to the latest by validFrom
            // if all bands are closed.
            val active = svtDtos.firstOrNull { it.validTo == null }
                ?: svtDtos.maxByOrNull { it.validFrom }
            active?.valueIncVat ?: cachedSvt
        } else cachedSvt

        repo.write(RateData(tariffSlots = tariff, svtPence = svt, fetchedAt = now))
        return Triple(workerResult, plan.label, "${tariff.size} slots, svt=${svt}")
    }

    companion object {
        private const val FREEPHASE_PRODUCT = "EDF_FREEPHASE_DYNAMIC_12M_HH"
        private const val SVT_PRODUCT = "EDF_STANDARD_VARIABLE"
        const val PERIODIC_NAME = "freephase-refresh"
        const val ONE_TIME_NAME = "freephase-bootstrap"
        const val RETRY_NAME = "freephase-retry-around-publication"

        /** Schedules a fresh fetch attempt timed against EDF's ~12:30 BST publication
         *  window. Before 12:30 today → target = today 12:30. After 12:30 → target =
         *  the next half-hour boundary. REPLACE policy so we always reset to the
         *  latest target, even if a previous retry is still pending. */
        private fun scheduleRetryAroundPublication(
            context: Context,
            now: Instant,
            zone: ZoneId,
        ) {
            val nowZ = now.atZone(zone)
            val today1230 = nowZ.toLocalDate().atTime(12, 30).atZone(zone)
            val targetInstant =
                if (nowZ.isBefore(today1230)) today1230.toInstant() else nextHalfHour(now)
            val delay = Duration.between(now, targetInstant).coerceAtLeast(Duration.ofMinutes(1))
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(RETRY_NAME, androidx.work.ExistingWorkPolicy.REPLACE, req)
        }

        fun schedulePeriodic(context: Context, replace: Boolean = false) {
            val now = Instant.now()
            val delay = delayToNextLocalTime(now, LocalTime.of(12, 30), ZoneId.of("Europe/London"))
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(Duration.ofDays(1))
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            val policy = if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, policy, req)
        }

        fun enqueueBootstrap(context: Context, force: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            val policy = if (force) androidx.work.ExistingWorkPolicy.REPLACE
                         else androidx.work.ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_NAME, policy, req)
        }
    }
}

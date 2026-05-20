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
        // Record entry so we can see "worker started but never finished" cases.
        repo.recordAttempt(now, "Running", null)
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

        when (decideRefresh(tariff, now, zone, retryUntilLocalHour = 18)) {
            RefreshOutcome.Retry ->
                return Triple(Result.retry(), "Retry", "got ${tariff.size} slots, tomorrow not yet")
            RefreshOutcome.GiveUp -> {
                val existing = repo.read()
                if (existing != null && existing.slotsFrom(now).isNotEmpty()) {
                    return Triple(Result.success(), "GiveUp:kept", "fetched ${tariff.size}, kept cache")
                }
                if (tariff.isEmpty()) {
                    return Triple(Result.retry(), "Retry", "past 18:00 but got 0 slots")
                }
                // Fall through: dead cache + non-empty fetched data → write what we have.
            }
            RefreshOutcome.Success -> Unit
        }

        val cachedSvt = repo.read()?.svtPence
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
        return Triple(Result.success(), "Wrote", "${tariff.size} slots, svt=${svt}")
    }

    companion object {
        private const val FREEPHASE_PRODUCT = "EDF_FREEPHASE_DYNAMIC_12M_HH"
        private const val SVT_PRODUCT = "EDF_STANDARD_VARIABLE"
        const val PERIODIC_NAME = "freephase-refresh"
        const val ONE_TIME_NAME = "freephase-bootstrap"

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

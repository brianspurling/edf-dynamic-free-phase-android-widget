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

    override suspend fun doWork(): Result = runCatching {
        val now = Instant.now()
        val from = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        val to = endOfTomorrowLocal(now, zone)

        val tariff = client.fetchTariffRates(
            productCode = FREEPHASE_PRODUCT,
            gspRegion = BuildConfig.GSP_REGION,
            from = from,
            to = to,
        ).toSlots()

        when (decideRefresh(tariff, now, zone, retryUntilLocalHour = 18)) {
            RefreshOutcome.Retry -> return@runCatching Result.retry()
            RefreshOutcome.GiveUp, RefreshOutcome.Success -> Unit
        }

        // Deviation from original plan: EDF's SVT endpoint returns the full history of rate
        // bands, not just the current rate. Using toSlots().firstOrNull() would return the
        // OLDEST historical rate (sorted by validFrom ascending) — the wrong one. Instead we
        // operate on the DTOs directly and pick the currently-active open-ended band
        // (validTo == null), falling back to the latest by validFrom if all bands are closed.
        val cachedSvt = repo.read()?.svtPence
        val svt = if (repo.svtCacheStale()) {
            val svtDtos = client.fetchTariffRates(
                productCode = SVT_PRODUCT,
                gspRegion = BuildConfig.GSP_REGION,
                from = from,
                to = to,
            )
            // SVT endpoint returns historical bands; pick the currently-active one
            // (validTo == null, i.e. open-ended) and prefer DIRECT_DEBIT which the
            // KrakenClient already filtered to.
            val active = svtDtos.firstOrNull { it.validTo == null }
                ?: svtDtos.maxByOrNull { it.validFrom }   // fallback: latest by validFrom
            active?.valueIncVat ?: cachedSvt
        } else cachedSvt

        repo.write(RateData(tariffSlots = tariff, svtPence = svt, fetchedAt = now))
        FreePhaseWidget().updateAll(applicationContext)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val FREEPHASE_PRODUCT = "EDF_FREEPHASE_DYNAMIC_12M_HH"
        private const val SVT_PRODUCT = "EDF_STANDARD_VARIABLE"
        const val PERIODIC_NAME = "freephase-refresh"
        const val ONE_TIME_NAME = "freephase-bootstrap"

        fun schedulePeriodic(context: Context) {
            val now = Instant.now()
            val delay = delayToNextLocalTime(now, LocalTime.of(12, 30), ZoneId.of("Europe/London"))
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(Duration.ofDays(1))
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun enqueueBootstrap(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_TIME_NAME, androidx.work.ExistingWorkPolicy.KEEP, req)
        }
    }
}

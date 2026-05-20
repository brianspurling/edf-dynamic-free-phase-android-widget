package com.bspurling.freephase.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

private val Context.dataStore by preferencesDataStore(name = "freephase")

/** What the worker did on its most recent run — surfaced on the loading placeholder
 *  so we can tell at a glance whether the worker is failing and where. */
data class WorkerDiagnostic(
    val attemptedAt: Instant,
    val outcome: String,
    val detail: String?,
)

class RateRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val rateKey = stringPreferencesKey("rate_data")
    private val svtFetchedKey = stringPreferencesKey("svt_fetched_at")
    private val diagAtKey = stringPreferencesKey("diag_attempted_at")
    private val diagOutcomeKey = stringPreferencesKey("diag_outcome")
    private val diagDetailKey = stringPreferencesKey("diag_detail")

    suspend fun read(): RateData? {
        val raw = context.dataStore.data.first()[rateKey] ?: return null
        return runCatching { json.decodeFromString(RateData.serializer(), raw) }.getOrNull()
    }

    suspend fun write(data: RateData) {
        context.dataStore.edit { prefs ->
            prefs[rateKey] = json.encodeToString(RateData.serializer(), data)
            if (data.svtPence != null) prefs[svtFetchedKey] = data.fetchedAt.toString()
        }
    }

    suspend fun svtCacheStale(maxAge: Duration = Duration.ofDays(7)): Boolean {
        val ts = context.dataStore.data.first()[svtFetchedKey] ?: return true
        val fetched = runCatching { Instant.parse(ts) }.getOrNull() ?: return true
        return Duration.between(fetched, Instant.now()) > maxAge
    }

    suspend fun recordAttempt(at: Instant, outcome: String, detail: String? = null) {
        context.dataStore.edit { prefs ->
            prefs[diagAtKey] = at.toString()
            prefs[diagOutcomeKey] = outcome
            if (detail != null) prefs[diagDetailKey] = detail.take(220)
            else prefs.remove(diagDetailKey)
        }
    }

    suspend fun readDiagnostic(): WorkerDiagnostic? {
        val data = context.dataStore.data.first()
        val atRaw = data[diagAtKey] ?: return null
        val at = runCatching { Instant.parse(atRaw) }.getOrNull() ?: return null
        return WorkerDiagnostic(
            attemptedAt = at,
            outcome = data[diagOutcomeKey] ?: "?",
            detail = data[diagDetailKey],
        )
    }
}

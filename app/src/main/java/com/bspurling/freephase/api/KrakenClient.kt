package com.bspurling.freephase.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter

class KrakenClient(
    private val httpClient: OkHttpClient = defaultClient(),
    private val baseUrl: String = "https://api.edfgb-kraken.energy/v1/",
) {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /**
     * Fetches all DIRECT_DEBIT rate slots for [productCode] / [gspRegion] in [from, to).
     * Follows the `next` link if the API paginates.
     */
    suspend fun fetchTariffRates(
        productCode: String,
        gspRegion: String,
        from: Instant,
        to: Instant,
        pageSize: Int = 200,
    ): List<RateSlotDto> = withContext(Dispatchers.IO) {
        val tariffCode = "E-1R-$productCode-$gspRegion"
        var url: String? = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("products/$productCode/electricity-tariffs/$tariffCode/standard-unit-rates/")
            .addQueryParameter("period_from", from.toIsoString())
            .addQueryParameter("period_to", to.toIsoString())
            .addQueryParameter("page_size", pageSize.toString())
            .build()
            .toString()

        val collected = mutableListOf<RateSlotDto>()
        while (url != null) {
            val req = Request.Builder().url(url!!).get().build()
            val body = httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code} from $url — ${text.take(200)}")
                }
                text
            }
            val page = json.decodeFromString(RatePageDto.serializer(), body)
            collected += page.results
            url = page.next
        }
        collected.filter { it.paymentMethod == "DIRECT_DEBIT" }
    }
}

private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(java.time.Duration.ofSeconds(10))
    .readTimeout(java.time.Duration.ofSeconds(30))
    .callTimeout(java.time.Duration.ofSeconds(60))
    .build()

private fun Instant.toIsoString(): String =
    DateTimeFormatter.ISO_INSTANT.format(this.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

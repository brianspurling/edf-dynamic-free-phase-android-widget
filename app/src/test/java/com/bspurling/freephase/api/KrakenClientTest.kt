package com.bspurling.freephase.api

import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

class KrakenClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: KrakenClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = KrakenClient(
            httpClient = OkHttpClient(),
            baseUrl = server.url("/v1/").toString(),
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("fixtures/$name")!!.readText()

    @Test fun `parses FreePhase rates and filters to DIRECT_DEBIT`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("freephase_rates_2026-05-15.json")))

        val from = Instant.parse("2026-05-15T00:00:00Z")
        val to = Instant.parse("2026-05-17T00:00:00Z")
        val slots = client.fetchTariffRates(
            productCode = "EDF_FREEPHASE_DYNAMIC_12M_HH",
            gspRegion = "C",
            from = from,
            to = to,
        )

        assertTrue("expected ≥48 half-hour slots", slots.size >= 48)
        assertTrue("all slots are DIRECT_DEBIT", slots.all { it.paymentMethod == "DIRECT_DEBIT" })
        val req = server.takeRequest()
        assertEquals(
            "/v1/products/EDF_FREEPHASE_DYNAMIC_12M_HH" +
                "/electricity-tariffs/E-1R-EDF_FREEPHASE_DYNAMIC_12M_HH-C" +
                "/standard-unit-rates/",
            req.requestUrl!!.encodedPath,
        )
        assertEquals("2026-05-15T00:00:00Z", req.requestUrl!!.queryParameter("period_from"))
        assertEquals("2026-05-17T00:00:00Z", req.requestUrl!!.queryParameter("period_to"))
    }

    @Test fun `recognises zero-priced free-phase slots`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("freephase_with_zeros_2026-04-30.json")))

        val slots = client.fetchTariffRates(
            productCode = "EDF_FREEPHASE_DYNAMIC_12M_HH",
            gspRegion = "C",
            from = Instant.parse("2026-04-30T00:00:00Z"),
            to = Instant.parse("2026-05-01T00:00:00Z"),
        )

        val zeros = slots.filter { it.valueIncVat == 0.0 }
        assertTrue("expected ≥1 zero-priced slot", zeros.isNotEmpty())
    }

    @Test fun `follows next-page pagination`() = runTest {
        val page1 = """{"count":3,"next":"${server.url("/v1/page2")}","previous":null,"results":[
            {"value_exc_vat":10.0,"value_inc_vat":10.5,"valid_from":"2026-05-15T00:00:00Z","valid_to":"2026-05-15T00:30:00Z","payment_method":"DIRECT_DEBIT"}
        ]}""".trimIndent()
        val page2 = """{"count":3,"next":null,"previous":null,"results":[
            {"value_exc_vat":11.0,"value_inc_vat":11.5,"valid_from":"2026-05-15T00:30:00Z","valid_to":"2026-05-15T01:00:00Z","payment_method":"DIRECT_DEBIT"},
            {"value_exc_vat":12.0,"value_inc_vat":12.5,"valid_from":"2026-05-15T01:00:00Z","valid_to":"2026-05-15T01:30:00Z","payment_method":"DIRECT_DEBIT"}
        ]}""".trimIndent()
        server.enqueue(MockResponse().setBody(page1))
        server.enqueue(MockResponse().setBody(page2))

        val slots = client.fetchTariffRates(
            productCode = "EDF_FREEPHASE_DYNAMIC_12M_HH",
            gspRegion = "C",
            from = Instant.parse("2026-05-15T00:00:00Z"),
            to = Instant.parse("2026-05-15T02:00:00Z"),
        )
        assertEquals(3, slots.size)
    }

    @Test fun `SVT fixture parses and exposes single DD rate`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("svt_rate.json")))

        val slots = client.fetchTariffRates(
            productCode = "EDF_STANDARD_VARIABLE",
            gspRegion = "C",
            from = Instant.parse("2026-05-15T00:00:00Z"),
            to = Instant.parse("2026-05-17T00:00:00Z"),
        )
        val dd = slots.filter { it.paymentMethod == "DIRECT_DEBIT" }
        assertEquals(1, dd.size)
        assertTrue(dd[0].valueIncVat in 20.0..30.0)
    }
}

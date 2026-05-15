package com.bspurling.freephase.chart

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.Slot
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w480dp-h960dp")
class ChartCanvasSnapshotTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun sampleData(now: Instant): RateData {
        val slots = (0 until 48).map { i ->
            val start = now.plus(Duration.ofMinutes(30L * i))
            val pence = when {
                i in 10..14 -> 0.0                    // a 2.5h free band
                i in 30..35 -> 32.0                   // peak
                else -> 18.0 + (i % 3)
            }
            Slot(start, start.plus(Duration.ofMinutes(30)), pence)
        }
        return RateData(slots, svtPence = 24.9, fetchedAt = now)
    }

    private fun assertMatchesFixture(actual: Bitmap, name: String) {
        val actualBytes = ByteArrayOutputStream().also { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val resource = javaClass.classLoader?.getResource("fixtures/$name")
        if (resource == null) {
            // First run: write the fixture into the working tree so the engineer can commit it.
            val out = File("src/test/resources/fixtures/$name")
            out.parentFile.mkdirs()
            out.writeBytes(actualBytes)
            error("Fixture $name not found; wrote it to ${out.absolutePath} — review visually and commit.")
        }
        val expected = resource.readBytes()
        assertTrue("Snapshot mismatch for $name (size ${actualBytes.size} vs expected ${expected.size})",
            actualBytes.contentEquals(expected))
    }

    @Test fun `small bucket renders correctly`() {
        val now = Instant.parse("2026-05-15T11:30:00Z")
        val bmp = ChartCanvas.render(sampleData(now), 140, 80, ChartTheme.from(context), now)
        assertMatchesFixture(bmp, "chart_small.png")
    }

    @Test fun `medium bucket renders axes labels and now marker`() {
        val now = Instant.parse("2026-05-15T11:30:00Z")
        val bmp = ChartCanvas.render(sampleData(now), 240, 140, ChartTheme.from(context), now)
        assertMatchesFixture(bmp, "chart_medium.png")
    }

    @Test fun `large bucket renders SVT comparison line`() {
        val now = Instant.parse("2026-05-15T11:30:00Z")
        val bmp = ChartCanvas.render(sampleData(now), 360, 200, ChartTheme.from(context), now)
        assertMatchesFixture(bmp, "chart_large.png")
    }
}

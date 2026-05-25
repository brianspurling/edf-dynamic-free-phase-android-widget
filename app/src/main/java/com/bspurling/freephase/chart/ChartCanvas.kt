package com.bspurling.freephase.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import com.bspurling.freephase.data.RateData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object ChartCanvas {

    private val zone = ZoneId.of("Europe/London")
    private val timeFmt = DateTimeFormatter.ofPattern("HH").withZone(zone)
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM").withZone(zone)

    enum class Bucket { Small, Medium, Large }

    private fun bucketOf(widthDp: Int, heightDp: Int): Bucket = when {
        widthDp >= 280 && heightDp >= 160 -> Bucket.Large
        widthDp >= 150 -> Bucket.Medium
        else -> Bucket.Small
    }

    fun render(
        data: RateData,
        widthDp: Int,
        heightDp: Int,
        theme: ChartTheme,
        now: Instant,
        density: Float = 1f,
    ): Bitmap {
        val w = (widthDp * density).toInt().coerceAtLeast(1)
        val h = (heightDp * density).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(theme.background)

        val (windowStart, windowEnd) = ChartWindow.fixedWindow(now, zone)
        // Show any slot that intersects the window — including today's already-elapsed
        // rates the worker fetches from 00:00 local. Past slots before the window
        // (e.g. yesterday before 23:00 local) are not fetched, so the left edge of the
        // window may still be empty.
        val slots = data.tariffSlots.filter { it.validTo > windowStart && it.validFrom < windowEnd }
        if (slots.isEmpty()) return bmp

        val bucket = bucketOf(widthDp, heightDp)
        // Tight padding — plot should fill almost the full widget height.
        val padTop = if (bucket == Bucket.Small) 1f * density else 2f * density
        val padX = if (bucket == Bucket.Small) 4f * density else 8f * density
        val padBottom = if (bucket == Bucket.Small) 1f * density else 2f * density
        val plot = RectF(padX, padTop, w - padX, h - padBottom)
        if (bucket != Bucket.Small) {
            plot.bottom -= 13f * density  // room for hour labels under the plot
            plot.left += 32f * density    // room for y-axis labels left of the plot
        }

        val xStart = windowStart.toEpochMilli()
        val xEnd = windowEnd.toEpochMilli()
        val todayLocal = now.atZone(zone).toLocalDate()
        // Fixed y-axis: -5p..40p. Keeps the chart shape comparable day-to-day
        // and leaves visible space below 0p so free-phase slots aren't squashed
        // against the x-axis.
        val yMin = -5.0
        val yMax = 40.0
        val yRange = yMax - yMin

        fun x(t: Instant): Float =
            plot.left + (t.toEpochMilli() - xStart).toFloat() / (xEnd - xStart) * plot.width()
        fun y(p: Double): Float =
            plot.bottom - ((p - yMin) / yRange).toFloat() * plot.height()

        // free-phase bands
        val bandPaint = Paint().apply { color = theme.freeBand; style = Paint.Style.FILL }
        slots.filter { it.isFree }.forEach { s ->
            val left = x(s.validFrom)
            val right = x(s.validTo).coerceAtMost(plot.right)
            c.drawRect(left, plot.top, right, plot.bottom, bandPaint)
        }

        // SVT line (large bucket only)
        if (bucket == Bucket.Large && data.svtPence != null && data.svtPence in yMin..yMax) {
            val svtPaint = Paint().apply {
                color = theme.svt
                strokeWidth = 2f * density
                isAntiAlias = true
                pathEffect = DashPathEffect(floatArrayOf(8f * density, 5f * density), 0f)
                style = Paint.Style.STROKE
            }
            val ySvt = y(data.svtPence)
            c.drawLine(plot.left, ySvt, plot.right, ySvt, svtPaint)
        }

        // tariff stepped line
        val tariffPaint = Paint().apply {
            color = theme.tariff; strokeWidth = 3f * density
            style = Paint.Style.STROKE; isAntiAlias = true; strokeJoin = Paint.Join.MITER
        }
        val path = Path()
        slots.forEachIndexed { i, s ->
            val left = x(s.validFrom)
            val right = x(s.validTo).coerceAtMost(plot.right)
            val yp = y(s.pence)
            if (i == 0) path.moveTo(left, yp) else path.lineTo(left, yp)
            path.lineTo(right, yp)
        }
        c.drawPath(path, tariffPaint)

        // "now" indicator — filled orange dot on the tariff line at the current price
        val nowMillis = now.toEpochMilli()
        val currentSlot = slots.firstOrNull { s ->
            val fromMillis = s.validFrom.toEpochMilli()
            val toMillis = s.validTo.toEpochMilli()
            nowMillis in fromMillis until toMillis
        } ?: slots.first()
        val nowDotPaint = Paint().apply {
            color = theme.tariff
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val nowDotRadius = if (bucket == Bucket.Small) 3f * density else 4f * density
        c.drawCircle(x(now), y(currentSlot.pence), nowDotRadius, nowDotPaint)

        if (bucket != Bucket.Small) {
            // now marker — only draw if `now` is inside the data window
            val nowMillis = now.toEpochMilli()
            if (nowMillis in xStart..xEnd) {
                val nowPaint = Paint().apply { color = theme.now; strokeWidth = 2f * density; style = Paint.Style.STROKE; isAntiAlias = true }
                c.drawLine(x(now), plot.top, x(now), plot.bottom, nowPaint)
            }

            drawAxes(c, plot, windowStart, todayLocal, xStart, xEnd, yMin, yRange, theme, density)
        }

        // last-updated badge — only shown when the cache is stale (>26h old)
        if (bucket != Bucket.Small) {
            val isStale = Duration.between(data.fetchedAt, now) > Duration.ofHours(26)
            if (isStale) {
                val fetchedHour = data.fetchedAt.atZone(zone).hour
                val fetchedMin = data.fetchedAt.atZone(zone).minute
                val label = "⚠ stale ${"%02d".format(fetchedHour)}:${"%02d".format(fetchedMin)}"
                val tp = TextPaint().apply { color = theme.stale; textSize = 11f * density; isAntiAlias = true }
                c.drawText(label, plot.right - tp.measureText(label), plot.top + 11f * density, tp)
            }
        }

        return bmp
    }

    private fun drawAxes(
        c: Canvas, plot: RectF,
        windowStart: Instant, todayLocal: LocalDate,
        xStart: Long, xEnd: Long,
        yMin: Double, yRange: Double,
        theme: ChartTheme, density: Float,
    ) {
        val axis = Paint().apply { color = theme.axis; strokeWidth = 1.5f * density; isAntiAlias = true }
        val text = TextPaint().apply { color = theme.text; textSize = 11f * density; isAntiAlias = true }

        // y-axis labels at fixed positions matching the fixed yMin..yMax range
        listOf(0.0, 20.0, 40.0).forEach { v ->
            val yp = plot.bottom - ((v - yMin) / yRange).toFloat() * plot.height()
            c.drawText("${v.toInt()}p", plot.left - 28f * density, yp + 4f * density, text)
        }

        // hour ticks at FreePhase phase changes: 06 (green→amber), 16 (amber→red),
        // 19 (red→amber), 23 (amber→green).
        val phaseChangeHours = setOf(6, 16, 19, 23)
        var t = windowStart.atZone(zone).withMinute(0).withSecond(0).withNano(0)
        while (t.toInstant().toEpochMilli() < xEnd) {
            if (t.hour in phaseChangeHours) {
                val xp = plot.left + (t.toInstant().toEpochMilli() - xStart).toFloat() / (xEnd - xStart) * plot.width()
                if (xp in plot.left..plot.right) {
                    c.drawLine(xp, plot.bottom, xp, plot.bottom + 3f * density, axis)
                    c.drawText("%02d".format(t.hour), xp - 7f * density, plot.bottom + 11f * density, text)
                }
            }
            t = t.plusHours(1)
        }

        // Label both day boundaries that fall inside the fixed window:
        // today 00:00 and tomorrow 00:00, both local-zone midnights.
        val bold = TextPaint(text).apply { isFakeBoldText = true }
        listOf(todayLocal, todayLocal.plusDays(1)).forEach { date ->
            val midnight = date.atStartOfDay(zone).toInstant()
            val xp = plot.left + (midnight.toEpochMilli() - xStart).toFloat() / (xEnd - xStart) * plot.width()
            if (xp in plot.left..plot.right) {
                c.drawText(dateFmt.format(midnight), xp + 3f * density, plot.top + 12f * density, bold)
            }
        }
    }
}

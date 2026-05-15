package com.bspurling.freephase.chart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.Slot
import java.time.Duration
import java.time.Instant
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

        val slots = data.slotsFrom(now)
        if (slots.isEmpty()) return bmp

        val bucket = bucketOf(widthDp, heightDp)
        // Tight padding — plot should fill almost the full widget height.
        val padTop = if (bucket == Bucket.Small) 2f * density else 4f * density
        val padX = if (bucket == Bucket.Small) 4f * density else 8f * density
        val padBottom = if (bucket == Bucket.Small) 2f * density else 4f * density
        val plot = RectF(padX, padTop, w - padX, h - padBottom)
        if (bucket != Bucket.Small) {
            plot.bottom -= 16f * density  // room for hour labels under the plot
            plot.left += 32f * density    // room for y-axis labels left of the plot
        }

        val xStart = slots.first().validFrom.toEpochMilli()
        val xEnd = slots.last().validTo.toEpochMilli()
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
            c.drawRect(x(s.validFrom), plot.top, x(s.validTo), plot.bottom, bandPaint)
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
            val left = x(s.validFrom); val right = x(s.validTo); val yp = y(s.pence)
            if (i == 0) path.moveTo(left, yp) else path.lineTo(left, yp)
            path.lineTo(right, yp)
        }
        c.drawPath(path, tariffPaint)

        if (bucket != Bucket.Small) {
            // now marker — only draw if `now` is inside the data window
            val nowMillis = now.toEpochMilli()
            if (nowMillis in xStart..xEnd) {
                val nowPaint = Paint().apply { color = theme.now; strokeWidth = 2f * density; style = Paint.Style.STROKE; isAntiAlias = true }
                c.drawLine(x(now), plot.top, x(now), plot.bottom, nowPaint)
            }

            drawAxes(c, plot, slots, xStart, xEnd, yMin, yRange, theme, density)
        }

        // last-updated timestamp (top right)
        if (bucket != Bucket.Small) {
            val isStale = Duration.between(data.fetchedAt, now) > Duration.ofHours(26)
            val label = if (isStale) "⚠ stale ${timeFmt.format(data.fetchedAt)}:${"%02d".format(data.fetchedAt.atZone(zone).minute)}"
                        else "${data.fetchedAt.atZone(zone).hour}:${"%02d".format(data.fetchedAt.atZone(zone).minute)}"
            val tp = TextPaint().apply { color = if (isStale) theme.stale else theme.text; textSize = 11f * density; isAntiAlias = true }
            c.drawText(label, plot.right - tp.measureText(label), plot.top + 11f * density, tp)
        }

        return bmp
    }

    private fun drawAxes(
        c: Canvas, plot: RectF, slots: List<Slot>, xStart: Long, xEnd: Long,
        yMin: Double, yRange: Double,
        theme: ChartTheme, density: Float,
    ) {
        val axis = Paint().apply { color = theme.axis; strokeWidth = 1.5f * density; isAntiAlias = true }
        val text = TextPaint().apply { color = theme.text; textSize = 13f * density; isAntiAlias = true }

        // y-axis labels at fixed positions matching the fixed yMin..yMax range
        listOf(0.0, 20.0, 40.0).forEach { v ->
            val yp = plot.bottom - ((v - yMin) / yRange).toFloat() * plot.height()
            c.drawText("${v.toInt()}p", plot.left - 28f * density, yp + 4f * density, text)
        }

        // hour ticks at 00/06/12/18
        var t = slots.first().validFrom.atZone(zone).withMinute(0).withSecond(0).withNano(0)
        while (t.toInstant().toEpochMilli() < xEnd) {
            if (t.hour % 6 == 0) {
                val xp = plot.left + (t.toInstant().toEpochMilli() - xStart).toFloat() / (xEnd - xStart) * plot.width()
                if (xp in plot.left..plot.right) {
                    c.drawLine(xp, plot.bottom, xp, plot.bottom + 4f * density, axis)
                    c.drawText("%02d".format(t.hour), xp - 8f * density, plot.bottom + 14f * density, text)
                }
            }
            t = t.plusHours(1)
        }

        // day boundary label (start of new day)
        slots.zipWithNext().firstOrNull { (a, b) ->
            a.validFrom.atZone(zone).toLocalDate() != b.validFrom.atZone(zone).toLocalDate()
        }?.let { (_, b) ->
            val xp = plot.left + (b.validFrom.toEpochMilli() - xStart).toFloat() / (xEnd - xStart) * plot.width()
            val label = dateFmt.format(b.validFrom)
            val bold = TextPaint(text).apply { isFakeBoldText = true }
            c.drawText(label, xp + 3f * density, plot.top + 14f * density, bold)
        }
    }
}

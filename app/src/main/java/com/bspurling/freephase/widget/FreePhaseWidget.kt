package com.bspurling.freephase.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bspurling.freephase.R
import com.bspurling.freephase.chart.ChartCanvas
import com.bspurling.freephase.chart.ChartTheme
import com.bspurling.freephase.data.RateData
import com.bspurling.freephase.data.RateRepository
import com.bspurling.freephase.data.WorkerDiagnostic
import com.bspurling.freephase.ui.MainActivity
import com.bspurling.freephase.worker.RefreshTrigger
import com.bspurling.freephase.worker.RefreshWorker
import com.bspurling.freephase.worker.decideRefreshTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FreePhaseWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            DpSize(180.dp, 110.dp),  // small: 4x2 cells (target size)
            DpSize(240.dp, 180.dp),  // medium-tall
            DpSize(280.dp, 220.dp),  // medium-large
            DpSize(380.dp, 290.dp),  // large (Brian's current 4-cell-wide × ~3-cell-tall)
            DpSize(380.dp, 460.dp),  // extra-tall
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // All suspending I/O happens here, before provideContent runs.
        val repo = RateRepository(context)
        val cached: RateData? = repo.read()
        val diagnostic: WorkerDiagnostic? = repo.readDiagnostic()
        val theme: ChartTheme = ChartTheme.from(context)
        val now: Instant = Instant.now()
        val density: Float = context.resources.displayMetrics.density

        // Decide whether to kick off a fetch. Two cases beyond "cache is fresh":
        //   1) Bootstrap — cache has no slot covering `now` (empty, or every slot is in the
        //      past). The chart would render blank; force-replace any in-flight bootstrap
        //      so the user sees recovery.
        //   2) SafetyNet — cache has today's slots but no tomorrow's, we're inside the
        //      publication window, and we haven't attempted in the last ~15 min. Backstop
        //      for a drifted PeriodicWorkRequest. force=true (REPLACE) so a morning bootstrap
        //      that's stuck in WorkManager's exponential backoff doesn't make this a no-op —
        //      REPLACE only touches the bootstrap work-name, not the separate periodic one,
        //      so it can't stomp the periodic worker. The 15-min rate-limit in
        //      decideRefreshTrigger keeps this from cancelling a fetch that's still in flight.
        when (decideRefreshTrigger(cached, diagnostic, now, ZONE)) {
            RefreshTrigger.Bootstrap -> RefreshWorker.enqueueBootstrap(context, force = true)
            RefreshTrigger.SafetyNet -> RefreshWorker.enqueueBootstrap(context, force = true)
            RefreshTrigger.None -> Unit
        }

        val effective = cached?.takeIf { it.slotsFrom(now).isNotEmpty() }

        provideContent { Content(effective, cached?.fetchedAt, diagnostic, theme, now, density) }
    }

    @Composable
    private fun Content(
        data: RateData?,
        cachedFetchedAt: Instant?,
        diagnostic: WorkerDiagnostic?,
        theme: ChartTheme,
        now: Instant,
        density: Float,
    ) {
        val context = LocalContext.current
        val size = LocalSize.current
        val tapAction = actionStartActivity<MainActivity>()

        val bitmap: Bitmap? = remember(size, data) {
            if (data == null || data.isEmpty) null
            else ChartCanvas.render(
                data = data,
                widthDp = size.width.value.toInt(),
                heightDp = size.height.value.toInt(),
                theme = theme,
                now = now,
                density = density,
            )
        }

        val rootModifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.chart_bg)
            .clickable(tapAction)

        if (bitmap != null) {
            Box(modifier = rootModifier) {
                Image(
                    provider = ImageProvider(bitmap),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
        } else {
            Box(modifier = rootModifier, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = context.getString(R.string.state_loading),
                        style = TextStyle(color = ColorProvider(R.color.chart_axis)),
                    )
                    if (cachedFetchedAt != null) {
                        Text(
                            text = "last cached: ${placeholderFmt.format(cachedFetchedAt)}",
                            style = TextStyle(color = ColorProvider(R.color.chart_axis)),
                        )
                    }
                    if (diagnostic != null) {
                        Text(
                            text = "last try: ${placeholderFmt.format(diagnostic.attemptedAt)} — ${diagnostic.outcome}",
                            style = TextStyle(color = ColorProvider(R.color.chart_axis)),
                        )
                        if (diagnostic.detail != null) {
                            Text(
                                text = diagnostic.detail,
                                style = TextStyle(color = ColorProvider(R.color.chart_axis)),
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Europe/London")
        private val placeholderFmt: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZONE)
    }
}

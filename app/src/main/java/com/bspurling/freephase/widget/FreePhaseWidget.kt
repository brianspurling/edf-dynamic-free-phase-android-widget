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
import com.bspurling.freephase.ui.MainActivity
import java.time.Instant

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
        val data: RateData? = RateRepository(context).read()
        val theme: ChartTheme = ChartTheme.from(context)
        val now: Instant = Instant.now()
        val density: Float = context.resources.displayMetrics.density

        provideContent { Content(data, theme, now, density) }
    }

    @Composable
    private fun Content(data: RateData?, theme: ChartTheme, now: Instant, density: Float) {
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
                diagnostic = true,  // TEMPORARY — to be removed once sizing is understood
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
                Text(
                    text = context.getString(R.string.state_loading),
                    style = TextStyle(color = ColorProvider(R.color.chart_axis)),
                )
            }
        }
    }
}

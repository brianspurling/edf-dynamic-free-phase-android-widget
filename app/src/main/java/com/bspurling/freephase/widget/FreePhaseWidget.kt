package com.bspurling.freephase.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bspurling.freephase.R
import com.bspurling.freephase.chart.ChartCanvas
import com.bspurling.freephase.chart.ChartTheme
import com.bspurling.freephase.data.RateRepository
import com.bspurling.freephase.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class FreePhaseWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Content() }
    }

    @Composable
    private fun Content() {
        val context = LocalContext.current
        val size = LocalSize.current

        var bmp by remember { mutableStateOf<Bitmap?>(null) }
        var hasData by remember { mutableStateOf(true) }

        LaunchedEffect(size) {
            withContext(Dispatchers.Default) {
                val data = RateRepository(context).read()
                if (data == null || data.isEmpty) {
                    hasData = false
                } else {
                    hasData = true
                    bmp = ChartCanvas.render(
                        data = data,
                        widthDp = size.width.value.toInt(),
                        heightDp = size.height.value.toInt(),
                        theme = ChartTheme.from(context),
                        now = Instant.now(),
                        density = context.resources.displayMetrics.density,
                    )
                }
            }
        }

        val tapAction = actionStartActivity<MainActivity>()
        Box(
            modifier = GlanceModifier.fillMaxSize().clickable(tapAction),
        ) {
            val image = bmp
            if (image != null) {
                Image(provider = ImageProvider(image), contentDescription = null,
                      modifier = GlanceModifier.fillMaxSize())
            } else if (!hasData) {
                Text(
                    text = context.getString(R.string.state_loading),
                    style = TextStyle(color = ColorProvider(android.graphics.Color.GRAY)),
                )
            }
        }
    }
}

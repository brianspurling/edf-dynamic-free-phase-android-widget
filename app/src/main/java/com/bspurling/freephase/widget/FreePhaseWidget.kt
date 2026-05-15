package com.bspurling.freephase.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Stub — full implementation in Task 8.
 * Required here so RefreshWorker compiles while Task 8 is not yet implemented.
 */
class FreePhaseWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { Text("…") }
    }
}

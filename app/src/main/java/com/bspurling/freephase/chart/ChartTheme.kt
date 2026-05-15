package com.bspurling.freephase.chart

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.bspurling.freephase.R

data class ChartTheme(
    @ColorInt val background: Int,
    @ColorInt val tariff: Int,
    @ColorInt val svt: Int,
    @ColorInt val freeBand: Int,
    @ColorInt val axis: Int,
    @ColorInt val text: Int,
    @ColorInt val now: Int,
    @ColorInt val stale: Int,
) {
    companion object {
        fun from(context: Context): ChartTheme = ChartTheme(
            background = ContextCompat.getColor(context, R.color.chart_bg),
            tariff = ContextCompat.getColor(context, R.color.chart_tariff),
            svt = ContextCompat.getColor(context, R.color.chart_svt),
            freeBand = ContextCompat.getColor(context, R.color.chart_free_band),
            axis = ContextCompat.getColor(context, R.color.chart_axis),
            text = ContextCompat.getColor(context, R.color.chart_text),
            now = ContextCompat.getColor(context, R.color.chart_now),
            stale = ContextCompat.getColor(context, R.color.chart_stale),
        )
    }
}

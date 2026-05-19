package com.bspurling.freephase.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.updateAll
import com.bspurling.freephase.widget.FreePhaseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RedrawReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RedrawScheduler.ACTION_REDRAW) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FreePhaseWidget().updateAll(appContext)
            } finally {
                RedrawScheduler.scheduleNext(appContext)
                pending.finish()
            }
        }
    }
}

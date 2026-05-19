package com.bspurling.freephase.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            RefreshWorker.enqueueBootstrap(context)
            RefreshWorker.schedulePeriodic(context, replace = true)
            RedrawScheduler.scheduleNext(context)
        }
    }
}

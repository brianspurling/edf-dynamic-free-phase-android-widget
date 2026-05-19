package com.bspurling.freephase

import android.app.Application
import com.bspurling.freephase.worker.RedrawScheduler
import com.bspurling.freephase.worker.RefreshWorker

class FreePhaseApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshWorker.enqueueBootstrap(this)
        RefreshWorker.schedulePeriodic(this)
        RedrawScheduler.scheduleNext(this)
    }
}

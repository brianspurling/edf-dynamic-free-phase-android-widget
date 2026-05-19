package com.bspurling.freephase.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.bspurling.freephase.time.nextHalfHour
import java.time.Instant

/**
 * Schedules a self-rescheduling AlarmManager wakeup every half-hour to redraw the widget,
 * advancing the "now" indicator and giving provideGlance a chance to notice stale data and
 * kick off a fresh fetch. Independent of WorkManager so it survives Doze deferring the
 * periodic refresh job.
 */
object RedrawScheduler {

    const val ACTION_REDRAW = "com.bspurling.freephase.action.REDRAW"
    private const val REQUEST_CODE = 1001

    fun scheduleNext(context: Context, now: Instant = Instant.now()) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RedrawReceiver::class.java).apply {
            action = ACTION_REDRAW
            // Explicit package so the intent matches our manifest-declared receiver.
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAtMillis = nextHalfHour(now).toEpochMilli()
        // setAndAllowWhileIdle (inexact) is enough for 30-min cadence and doesn't require
        // the SCHEDULE_EXACT_ALARM permission, which users can revoke on Android 14+.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, RedrawReceiver::class.java).apply {
            action = ACTION_REDRAW
            setPackage(context.packageName)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (pi != null) {
            am.cancel(pi)
            pi.cancel()
        }
    }
}

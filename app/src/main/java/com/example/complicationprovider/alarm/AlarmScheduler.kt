package com.example.complicationprovider.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.complicationprovider.util.FileLogger

object AlarmScheduler {
    private const val REQ_CODE_MAIN = 1001
    private const val REQ_CODE_RETRY = 1002

    // Glavni interval (npr. 15 min)
    private const val INTERVAL_MS = 15 * 60 * 1000L
    // Retry kad fetch padne (npr. 2 min)
    private const val RETRY_MS = 2 * 60 * 1000L

    private fun pi(context: Context, reqCode: Int): PendingIntent {
        val intent = Intent(context, FetchAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context, reqCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleRepeating(context: Context, fireNow: Boolean = false) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAll(context)

        val whenMs = SystemClock.elapsedRealtime() + if (fireNow) 1000L else INTERVAL_MS
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenMs, pi(context, REQ_CODE_MAIN))
        FileLogger.writeLine("[ALARM] armed main at +${if (fireNow) 1 else INTERVAL_MS/1000}s")
    }

    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val whenMs = SystemClock.elapsedRealtime() + INTERVAL_MS
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenMs, pi(context, REQ_CODE_MAIN))
        FileLogger.writeLine("[ALARM] re-armed next +${INTERVAL_MS/1000}s")
    }

    fun scheduleRetry(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val whenMs = SystemClock.elapsedRealtime() + RETRY_MS
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenMs, pi(context, REQ_CODE_RETRY))
        FileLogger.writeLine("[ALARM] retry armed +${RETRY_MS/1000}s")
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(context, REQ_CODE_MAIN))
        am.cancel(pi(context, REQ_CODE_RETRY))
        FileLogger.writeLine("[ALARM] cancel all")
    }
}
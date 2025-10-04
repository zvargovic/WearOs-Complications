package com.example.complicationprovider.orchestrator

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import java.util.Date

object AlignedFetchScheduler {
    private const val TAG = "AlignedFetchScheduler"

    /** Koliko minuta između fetch-eva. Mora biti ≥ 1. */
    @Volatile var INTERVAL_MINUTES: Long = 5

    /**
     * Zakaži sljedeći alarm poravnan na zidno vrijeme:
     * - sekunde = 00, ms = 000
     * - minute % INTERVAL_MINUTES == 0
     *
     * Primjeri:
     *  INTERVAL=5  → ...:00, :05, :10, :15, ...
     *  INTERVAL=10 → ...:00, :10, :20, :30, ...
     *  INTERVAL=1  → svaka puna minuta
     */
    fun scheduleNext(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AlignedFetchReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val periodMin = INTERVAL_MINUTES.coerceAtLeast(1)
        val periodMs = periodMin * 60_000L

        // Sadašnje zidno vrijeme (ms)
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        // Zaokruži WALL na sljedeći višekratnik perioda u MINUTAMA (sekunde=0)
        // 1) u minutama:
        val nowMin = nowWall / 60_000L
        // 2) sljedeći višekratnik perioda:
        val nextMinMultiple = ((nowMin / periodMin) + 1) * periodMin
        // 3) natrag u ms (sekunde = 0, ms = 0)
        val nextWall = nextMinMultiple * 60_000L

        // Pretvori u ELAPSED referencu (koju koristi AlarmManager ELAPSED_REALTIME_WAKEUP)
        val delayMs = (nextWall - nowWall).coerceAtLeast(0L)
        val nextElapsed = nowElapsed + delayMs

        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextElapsed, pi)

        val nextTimeStr = DateFormat.format("HH:mm:ss", Date(nextWall))
        Log.d(
            TAG,
            "✅ scheduleNext() → next in ${delayMs / 1000}s → $nextTimeStr (aligned every ${periodMin}m)"
        )
    }
}
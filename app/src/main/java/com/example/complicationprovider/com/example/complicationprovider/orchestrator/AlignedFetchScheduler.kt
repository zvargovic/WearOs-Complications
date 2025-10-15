package com.example.complicationprovider.orchestrator

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import com.example.complicationprovider.util.FileLogger
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.max

object AlignedFetchScheduler {
    private const val TAG = "AlignedFetchScheduler"

    /** Koliko minuta između fetch-eva. Mora biti ≥ 1. */
    @Volatile var INTERVAL_MINUTES: Long = 5

    private const val PREFS = "aligned_scheduler_prefs"
    private const val KEY_NEXT_WALL_MS = "next_wall_ms"
    private const val KEY_LAST_SCHEDULE_REALTIME_MS = "last_schedule_elapsed_ms"

    /** Debounce: ignoriraj duplo zakazivanje unutar ovog prozora, ako target vrijeme (nextWall) nije promijenjeno. */
    private val DEBOUNCE_WINDOW_MS = TimeUnit.SECONDS.toMillis(30)

    /** Minimalni lead-time; ako je do sljedećeg poravnanja < ovoga, pomakni još jedan period naprijed. */
    private const val MIN_LEAD_MS = 5_000L

    /**
     * Zakaži sljedeći alarm poravnan na zidno vrijeme:
     * - sekunde = 00, ms = 000
     * - minute % INTERVAL_MINUTES == 0
     *
     * Primjeri:
     *  INTERVAL=5  → ...:00, :05, :10, :15, ...
     *  INTERVAL=10 → ...:00, :10, :20, :30, ...
     *  INTERVAL=1  → svaka puna minuta
     *
     * Dodatno:
     * - Debounce: ne dupliraj alarm ako je već zakazano isto target vrijeme u zadnjih 30 s.
     * - Ako je do idućeg poravnanja premalo vremena (<5 s), gurni još jedan period naprijed (izbjegni “odmah”).
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

        // Sadašnje zidno vrijeme (ms) i elapsed za ELAPSED_REALTIME
        val nowWall = System.currentTimeMillis()
        val nowElapsed = SystemClock.elapsedRealtime()

        // Zaokruži WALL na sljedeći višekratnik perioda (sekunde=0)
        val nowMin = nowWall / 60_000L
        val nextMinMultiple = ((nowMin / periodMin) + 1) * periodMin
        var nextWall = nextMinMultiple * 60_000L

        // Ako je do poravnanja premalo (<5s), pomakni još jedan period da izbjegnemo “odmah”
        val rawDelay = max(0L, nextWall - nowWall)
        if (rawDelay < MIN_LEAD_MS) {
            nextWall += periodMs
        }

        val delayMs = max(0L, nextWall - nowWall)
        val nextElapsed = nowElapsed + delayMs

        // Debounce guard: preskoči ako je isti target već zakazan “maloprije”
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNextWall = prefs.getLong(KEY_NEXT_WALL_MS, -1L)
        val lastSchedElapsed = prefs.getLong(KEY_LAST_SCHEDULE_REALTIME_MS, -1L)
        val sinceLastSched = if (lastSchedElapsed > 0) (nowElapsed - lastSchedElapsed) else Long.MAX_VALUE

        if (lastNextWall == nextWall && sinceLastSched in 0..DEBOUNCE_WINDOW_MS) {
            val nextStr = DateFormat.format("HH:mm:ss", Date(nextWall))
            Log.d(TAG, "⏭️ scheduleNext(): debounce skip → already scheduled $nextStr ${sinceLastSched}ms ago")
            FileLogger.writeLine("[ALIGN] debounce-skip → next=$nextStr ago=${sinceLastSched}ms")
            return
        }

        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, nextElapsed, pi)

            // Zapiši meta-vrijeme da možemo detektirati duplo zakazivanje
            prefs.edit()
                .putLong(KEY_NEXT_WALL_MS, nextWall)
                .putLong(KEY_LAST_SCHEDULE_REALTIME_MS, nowElapsed)
                .apply()

            val nextTimeStr = DateFormat.format("HH:mm:ss", Date(nextWall))
            Log.d(
                TAG,
                "✅ scheduleNext() → next in ${delayMs / 1000}s → $nextTimeStr (aligned every ${periodMin}m)"
            )
            FileLogger.writeLine("[ALIGN] alarm scheduled → in=${delayMs / 1000}s (${delayMs}ms) next=$nextTimeStr every=${periodMin}m")
        } catch (t: Throwable) {
            Log.w(TAG, "scheduleNext() failed: ${t.message}", t)
            FileLogger.writeLine("[ALIGN][ERR] scheduleNext failed → ${t.message}")
        }
    }
}
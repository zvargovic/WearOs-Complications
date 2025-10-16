package com.example.complicationprovider

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.util.FileLogger
import java.util.concurrent.TimeUnit

private const val TAG_WORK = "WorkFallback"
private const val UNIQUE_NAME = "gold_refresh_fallback"

// koordinacija s aligned receiverom
private const val PREFS = "orchestrator"
private const val KEY_LAST_ALIGNED_TS = "last_aligned_wall_ms"
private const val KEY_LAST_FALLBACK_TS = "last_fallback_wall_ms"

// koliko dugo nakon aligned run-a preskačemo fallback
private const val GUARD_RECENT_ALIGNED_MS = 3 * 60_000L  // 3 min

/**
 * Periodični “safety net”.
 * – every 15 min
 * – prvi run offsetiran za +2 min nakon boota (da se ne sudara s boot/aligned)
 * – preskače ako je aligned fetch radio unutar zadnje 3 minute
 */
class WorkFallback(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAligned = prefs.getLong(KEY_LAST_ALIGNED_TS, 0L)
        val now = System.currentTimeMillis()
        val sinceAligned = now - lastAligned

        if (lastAligned > 0L && sinceAligned in 0..GUARD_RECENT_ALIGNED_MS) {
            Log.d(TAG_WORK, "Skip fallback: aligned was ${sinceAligned / 1000}s ago (guard ${GUARD_RECENT_ALIGNED_MS / 1000}s).")
            FileLogger.writeLine("[WORK] skip • aligned ${sinceAligned/1000}s ago (guard 180s)")
            Result.success()                  // ← zadnja vrijednost grane, bez 'return'
        } else {
            FileLogger.writeLine("[WORK] doWork start → OneShotFetcher.run(fallback-worker)")
            Log.d(TAG_WORK, "Fallback tick → OneShotFetcher.run")

            val ok = OneShotFetcher.run(applicationContext, reason = "fallback-worker")
            if (ok) {
                prefs.edit().putLong(KEY_LAST_FALLBACK_TS, now).apply()
                FileLogger.writeLine("[WORK] doWork end → success")
                Result.success()
            } else {
                FileLogger.writeLine("[WORK] doWork end → retry (fetch failed)")
                Result.retry()
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG_WORK, "Fallback work failed: ${t.message}", t)
        FileLogger.writeLine("[WORK][ERR] doWork exception → ${t::class.java.simpleName}: ${t.message}")
        Result.retry()
    }

    companion object {
        /**
         * Zakazivanje jedinstvenog periodičkog fallbacka:
         * – period: 15 min
         * – initialDelay: 2 min (offset nakon boota)
         * – radi samo kad postoji mreža
         */
        fun schedule(ctx: Context, initialDelayMinutes: Long = 2L) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<WorkFallback>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setInitialDelay(initialDelayMinutes.coerceAtLeast(0L), TimeUnit.MINUTES)
                .build()

            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    req
                )
                Log.d(TAG_WORK, "Scheduled fallback: every 15m, first run +${initialDelayMinutes}m.")
                FileLogger.writeLine("[WORK] enqueueUniquePeriodicWork • 15m, first=+${initialDelayMinutes}m name=$UNIQUE_NAME")
            } catch (t: Throwable) {
                Log.w(TAG_WORK, "WorkFallback.schedule failed: ${t.message}")
                FileLogger.writeLine("[WORK][WARN] WorkFallback.schedule failed")
            }
        }

        /** Po potrebi, ukloni zakazani fallback. */
        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
                Log.d(TAG_WORK, "Canceled unique periodic fallback work.")
                FileLogger.writeLine("[WORK] cancel • name=$UNIQUE_NAME")
            } catch (t: Throwable) {
                Log.w(TAG_WORK, "Cancel failed: ${t.message}")
                FileLogger.writeLine("[WORK][ERR] cancel failed → ${t.message}")
            }
        }
    }
}
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

/**
 * Periodični “safety net” koji svakih ~15 min pogura fetch + refresh komplikacija
 * u slučaju da OS priguši exact alarme.
 */
class WorkFallback(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        FileLogger.writeLine("[WORK] doWork start → OneShotFetcher.run(fallback-worker)")
        Log.d(TAG_WORK, "Fallback tick → OneShotFetcher.run + requestUpdateAllComplications")

        val ok = OneShotFetcher.run(applicationContext, reason = "fallback-worker")

        if (ok) {
            FileLogger.writeLine("[WORK] doWork end → success")
            Result.success()
        } else {
            FileLogger.writeLine("[WORK] doWork end → retry (fetch failed)")
            Result.retry()
        }
    } catch (t: Throwable) {
        Log.w(TAG_WORK, "Fallback work failed: ${t.message}", t)
        FileLogger.writeLine("[WORK][ERR] doWork exception → ${t::class.java.simpleName}: ${t.message}")
        Result.retry()
    }

    companion object {
        /** Zakazivanje jedinstvenog periodičkog fallbacka (15 min, samo kad ima mreže). */
        fun schedule(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = PeriodicWorkRequestBuilder<WorkFallback>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            try {
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE, // zamijeni staro raspoređivanje, ako postoji
                    req
                )
                Log.d(TAG_WORK, "Scheduled unique periodic fallback work (15 min).")
                FileLogger.writeLine("[WORK] enqueueUniquePeriodicWork • 15min interval, name=$UNIQUE_NAME")
            } catch (t: Throwable) {
                Log.w(TAG_WORK, "Failed to schedule periodic fallback: ${t.message}")
                FileLogger.writeLine("[WORK][ERR] enqueue failed → ${t.message}")
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
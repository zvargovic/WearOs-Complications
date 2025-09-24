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
import com.example.complicationprovider.data.GoldFetcher
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
        Log.d(TAG_WORK, "Fallback tick → start fetch + requestUpdateAllComplications")
        GoldFetcher.start(applicationContext)               // pokrene ako već ne radi
        requestUpdateAllComplications(applicationContext)   // ping za Spot/RSI/ROC
        Result.success()
    } catch (t: Throwable) {
        Log.w(TAG_WORK, "Fallback work failed: ${t.message}", t)
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

            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE, // zamijeni staro raspoređivanje, ako postoji
                req
            )
            Log.d(TAG_WORK, "Scheduled unique periodic fallback work (15 min).")
        }

        /** Po potrebi, ukloni zakazani fallback. */
        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
            Log.d(TAG_WORK, "Canceled unique periodic fallback work.")
        }
    }
}
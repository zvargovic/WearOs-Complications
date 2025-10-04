package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.complicationprovider.orchestrator.AlignedFetchScheduler

/**
 * Worker koji odrađuje jedan fetch i na kraju zakaže sljedeći.
 */
class FetchOrchestratorWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork() → starting orchestrated fetch")

        return try {
            val ok = OneShotFetcher.run(applicationContext, reason = "orchestrator")
            if (ok) {
                Log.d(TAG, "doWork() → fetch OK")
            } else {
                Log.w(TAG, "doWork() → fetch failed")
            }

            // 🔑 Zakazivanje sljedećeg ciklusa nakon završetka ovog run-a
            AlignedFetchScheduler.scheduleNext(applicationContext)

            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "doWork() exception: ${t.message}", t)

            // Također zakaži idući pokušaj iako je ovaj pukao
            AlignedFetchScheduler.scheduleNext(applicationContext)

            Result.retry()
        }
    }

    companion object {
        private const val TAG = "FetchOrchestrator"

        fun ensureScheduled(context: Context) {
            Log.d(TAG, "ensureScheduled() → enqueued/kept UNIQUE one-time work")
            AlignedFetchScheduler.scheduleNext(context)
        }
    }
}
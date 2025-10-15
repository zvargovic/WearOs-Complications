package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.complicationprovider.tiles.TilePreRender
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.runBlocking
private const val TAG = "FetchOrchestratorWorker"
private const val UNIQUE_NAME = "gold_fetch_orchestrator"

/**
 * Jednokratni worker koji odvrti jedan kompletan ciklus dohvaćanja
 * koristeći OneShotFetcher. Namijenjen kao “orchestrator” kad ga
 * netko želi explicitno pogurnuti preko WorkManagera.
 */
class FetchOrchestratorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        FileLogger.writeLine("[ORCH] doWork start → OneShotFetcher.runOnce()")
        Log.d(TAG, "Orchestrator tick → OneShotFetcher.run")

        val ok = OneShotFetcher.runOnce(applicationContext,"orchestrator")
        if (ok) {
            FileLogger.writeLine("[ORCH] doWork end → success")
            // (sigurnosno: prerender nakon fetch-a; u slučaju faila radi se i u run catch-u)
            runCatching { TilePreRender.run(applicationContext) }
            Result.success()
        } else {
            FileLogger.writeLine("[ORCH] doWork end → retry (fetch failed)")
            Result.retry()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Orchestrator work failed: ${t.message}", t)
        FileLogger.writeLine("[ORCH][ERR] doWork exception → ${t::class.java.simpleName}: ${t.message}")
        // pokušaj prikazati CLOSED/OPEN izgled prema MarketSession
        runCatching { TilePreRender.run(applicationContext) }
        Result.retry()
    }

    companion object {
        /**
         * Pokreni ovaj worker jednokratno (UNIQUE) — ako već postoji u redu, zamijeni.
         * Mreža je obavezna.
         */
        fun enqueue(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val req = OneTimeWorkRequestBuilder<FetchOrchestratorWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
            FileLogger.writeLine("[ORCH] enqueueUniqueWork • name=$UNIQUE_NAME")
        }
    }
}
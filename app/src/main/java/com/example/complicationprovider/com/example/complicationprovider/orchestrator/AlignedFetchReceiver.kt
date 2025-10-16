package com.example.complicationprovider.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.runBlocking

private const val TAG = "AlignedFetchReceiver"

// SharedPreferences za koordinaciju s fallbackom
private const val PREFS = "orchestrator"
private const val KEY_LAST_ALIGNED_TS = "last_aligned_wall_ms"

/**
 * Prima “aligned” alarm i odrađuje jedan ciklus fetch-a, pa replanira sljedeći tick.
 * Također zapisuje WALL timestamp zadnjeg aligned run-a (za guard u WorkFallback-u).
 */
class AlignedFetchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FileLogger.writeLine("[ALIGNED] onReceive")
        Log.d(TAG, "Aligned fetch tick")

        val app = context.applicationContext
        val now = System.currentTimeMillis()
        // Zapiši odmah trenutak kad smo krenuli (da fallback zna da smo “svježi”)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_ALIGNED_TS, now)
            .apply()

        try {
            runBlocking { OneShotFetcher.runOnce(app, "aligned-alarm") }
        } catch (t: Throwable) {
            Log.w(TAG, "Aligned run failed: ${t.message}", t)
            FileLogger.writeLine("[ALIGNED][ERR] ${t::class.java.simpleName}: ${t.message}")
        } finally {
            AlignedFetchScheduler.scheduleNext(app)
        }
    }
}
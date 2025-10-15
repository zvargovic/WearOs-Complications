package com.example.complicationprovider.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "AlignedFetchReceiver"

/**
 * Prima “aligned” alarm i odrađuje jedan ciklus fetch-a, pa replanira sljedeći tick.
 */
class AlignedFetchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        FileLogger.writeLine("[ALIGNED] onReceive")
        Log.d(TAG, "Aligned fetch tick")

        val pending = goAsync()
        val app = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val ok = OneShotFetcher.runOnce(app, reason = "aligned-alarm")
                FileLogger.writeLine("[ALIGNED] runOnce ok=$ok")
            } catch (t: Throwable) {
                Log.w(TAG, "Aligned run failed: ${t.message}", t)
                FileLogger.writeLine("[ALIGNED][ERR] ${t::class.java.simpleName}: ${t.message}")
            } finally {
                // uvijek replaniraj sljedeći tick
                AlignedFetchScheduler.scheduleNext(app)
                pending.finish()
            }
        }
    }
}
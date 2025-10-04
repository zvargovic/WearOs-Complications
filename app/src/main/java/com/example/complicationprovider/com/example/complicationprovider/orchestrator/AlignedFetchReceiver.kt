package com.example.complicationprovider.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pokreće OneShotFetcher točno svake pune minute (ili koliko je podešeno u AlignedFetchScheduler).
 * Nakon svakog fetch-a sam se ponovno zakazuje.
 */
class AlignedFetchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: "?"
        Log.d("AlignedFetchReceiver", "onReceive action=$action")

        CoroutineScope(Dispatchers.Default).launch {
            try {
                OneShotFetcher.run(context.applicationContext, reason = "aligned-minute")
                Log.d("AlignedFetchReceiver", "OneShotFetcher.run() done")
            } catch (t: Throwable) {
                Log.w("AlignedFetchReceiver", "Fetcher failed: ${t.message}", t)
            } finally {
                // zakazivanje idućeg ticka
                AlignedFetchScheduler.scheduleNext(context.applicationContext)
            }
        }
    }
}
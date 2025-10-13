package com.example.complicationprovider.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.orchestrator.AlignedFetchScheduler
import com.example.complicationprovider.requestUpdateAllComplications
import com.example.complicationprovider.tiles.TilePreRender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Okida se na BOOT / LOCKED_BOOT / PACKAGE_REPLACED.
 * Odmah:
 *  1) prerendera tile (graf ili ikona s natpisom, ovisno o statusu tržišta),
 *  2) traži update svih komplikacija,
 *  3) replanira aligned fetch raspored.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action !in ACTIONS) {
            Log.d(TAG, "Ignoring action: $action")
            return
        }

        Log.i(TAG, "onReceive: $action — kick off prerender + complications + schedule")

        // radimo asinkrono da ne blokiramo broadcast thread
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            val app = context.applicationContext
            try {
                // 1) Odmah nacrtaj početni prikaz (graf ili closed ikonu s natpisom)
                runCatching {
                    TilePreRender.run(app)
                    Log.d(TAG, "TilePreRender.run() done")
                }.onFailure { Log.w(TAG, "TilePreRender failed: ${it.message}", it) }

                // 2) Zatraži refresh svih komplikacija
                runCatching {
                    requestUpdateAllComplications(app)
                    Log.d(TAG, "requestUpdateAllComplications() posted")
                }.onFailure { Log.w(TAG, "Complications update request failed: ${it.message}", it) }

                // 3) Replaniraj periodički aligned fetch
                runCatching {
                    AlignedFetchScheduler.scheduleNext(app)
                    Log.d(TAG, "AlignedFetchScheduler.scheduleNext() scheduled")
                }.onFailure { Log.w(TAG, "Aligned scheduleNext failed: ${it.message}", it) }

            } finally {
                pending.finish()
            }
        }
    }
}
package com.example.complicationprovider.data

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.example.complicationprovider.requestUpdateAllComplications
import com.example.complicationprovider.tiles.EmaSmaTileService
import com.example.complicationprovider.tiles.SpotTileService
import com.example.complicationprovider.tiles.SparklineTileService
/**
 * Jednokratni fetch s PARTIAL_WAKE_LOCK-om.
 * - Drži CPU budnim dok traje mrežni dohvat (ekran se smije ugasiti).
 * - Nakon uspjeha pinga komplikacije i tile.
 */
object OneShotFetcher {
    private const val TAG = "OneShotFetcher"
    private const val WAKE_TAG = "ComplicationProvider:FetchWakelock"
    private const val WAKE_TIMEOUT_MS = 60_000L   // dovoljno za spore mreže

    /**
     * Pokreni jednokratni fetch. Vraća true ako je fetch uspio i spremljen.
     */
    suspend fun run(context: Context, reason: String = "manual"): Boolean {
        Log.d(TAG, "triggered by $reason")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
        }

        Log.d(TAG, "Acquire wakelock ($reason)")
        wl.acquire(WAKE_TIMEOUT_MS)
        try {
            val ok = GoldFetcher.fetchOnce(context)
            if (ok) {
                // Nakon uspješnog spremanja podataka – pingni komplikacije…
                requestUpdateAllComplications(context)
                // …i osvježi Tile (MarketTileService)
                runCatching {
                    SpotTileService.requestUpdate(context)
                    SparklineTileService.requestUpdate(context)
                    EmaSmaTileService.requestUpdate(context)
                    Log.d(TAG, "Tile update requested")
                }.onFailure {
                    Log.w(TAG, "Tile update request failed: ${it.message}")
                }
            }
            Log.d(TAG, "Fetch finished (ok=$ok)")
            return ok
        } catch (t: Throwable) {
            Log.w(TAG, "OneShot fetch failed: ${t.message}", t)
            return false
        } finally {
            if (wl.isHeld) {
                Log.d(TAG, "Release wakelock")
                wl.release()
            }
        }
    }
}
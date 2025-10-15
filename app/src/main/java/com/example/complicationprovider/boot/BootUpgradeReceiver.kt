package com.example.complicationprovider.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.orchestrator.AlignedFetchScheduler
import com.example.complicationprovider.tiles.TilePreRender
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "BootUpgradeReceiver"

// Guard postavke
private const val PREFS_NAME = "boot_guard_prefs"
private const val KEY_LAST_BOOT_RUN_MS = "last_boot_run_ms"
// vremenski prozor u kojem ignoriramo drugi event (LOCKED_BOOT vs BOOT)
private const val DEBOUNCE_WINDOW_MS = 90_000L

/**
 * BOOT/UPGRADE receiver: nakon boota i nakon update-a paketa
 * replanira “aligned” raspored, prerendera tile i pokrene jedan fetch.
 *
 * Debounce/guard:
 *  - per-process: AtomicBoolean onemogućava paralelna pokretanja
 *  - cross-process: SharedPreferences timestamp blokira dupli event unutar 90 s
 */
class BootUpgradeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val act = intent?.action.orEmpty()
        FileLogger.writeLine("[BOOT-UPG] onReceive action=$act")
        Log.d(TAG, "BOOT/UPGRADE action=$act")

        val app = context.applicationContext

        // ==== Cross-process debounce (SharedPreferences) ====
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_BOOT_RUN_MS, 0L)
        if (last > 0 && (now - last) < DEBOUNCE_WINDOW_MS) {
            Log.d(TAG, "Debounce hit (${now - last} ms < $DEBOUNCE_WINDOW_MS) → skip fetch.")
            FileLogger.writeLine("[BOOT-UPG] debounce(skip) • last=${now - last}ms ago, action=$act")
            // i dalje replaniraj tick da ne izgubiš raspored
            runCatching { AlignedFetchScheduler.scheduleNext(app) }
            return
        }
        // zabilježi odmah (best effort) da spriječiš dupli okidač
        prefs.edit().putLong(KEY_LAST_BOOT_RUN_MS, now).apply()

        // ==== Per-process guard (spriječi paralelni run) ====
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            Log.d(TAG, "Already in-flight → skip.")
            FileLogger.writeLine("[BOOT-UPG] in-flight(skip) action=$act")
            return
        }

        // Produži lifetime receivera dok posao ne završi
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // 1) Planiraj sljedeći aligned tick
                AlignedFetchScheduler.scheduleNext(app)

                // 2) Prerender tile (MarketSession → instant closed/open izgled)
                runCatching { TilePreRender.run(app) }

                // 3) Jedan ciklus fetch-a sa razlogom (SUSPEND poziv — ne treba runBlocking)
                val reason = when (act) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> "upgrade"
                    Intent.ACTION_LOCKED_BOOT_COMPLETED -> "locked-boot"
                    else -> "boot"
                }
                val ok = OneShotFetcher.runOnce(app, reason)
                FileLogger.writeLine("[BOOT-UPG] fetch done ok=$ok reason=$reason")
            } catch (t: Throwable) {
                Log.w(TAG, "Boot/Upgrade flow failed: ${t.message}", t)
                FileLogger.writeLine("[BOOT-UPG][ERR] ${t::class.java.simpleName}: ${t.message}")
            } finally {
                IN_FLIGHT.set(false)
                pending.finish()
            }
        }
    }

    companion object {
        private val IN_FLIGHT = AtomicBoolean(false)
    }
}
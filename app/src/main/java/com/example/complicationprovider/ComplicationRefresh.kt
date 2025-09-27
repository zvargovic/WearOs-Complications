package com.example.complicationprovider

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.example.complicationprovider.data.OneShotFetcher
import com.example.complicationprovider.net.NetWatch
import com.example.complicationprovider.data.GoldFetcher

private const val TAG = "CompRefresh"

/** Custom akcija za naš alarm (watchdog). */
const val ACTION_REFRESH_ALARM = "com.example.complicationprovider.ACTION_REFRESH_ALARM"

/** ➕ Tick akcije za prebacivanje CLOSED↔OPEN prikaza bez mreže. */
const val ACTION_MARKET_OPEN_TICK = "com.example.complicationprovider.ACTION_MARKET_OPEN_TICK"
const val ACTION_MARKET_CLOSE_TICK = "com.example.complicationprovider.ACTION_MARKET_CLOSE_TICK"

/** Watchdog interval (ms) — dugi backup. */
private const val WATCHDOG_MIN: Long = 30

/** Jedini točan način kako pingati SVE naše complication datasourcere. */
fun requestUpdateAllComplications(ctx: Context) {
    try {
        val compNames = listOf(
            ComponentName(ctx, com.example.complicationprovider.complications.SpotComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RsiComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RocComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.SparklineComplicationService::class.java),
            // ➕ naš novi ranged provider:
            ComponentName(ctx, com.example.complicationprovider.complications.RangedPriceComplicationService::class.java),
        )
        compNames.forEach { cn ->
            try {
                ComplicationDataSourceUpdateRequester.create(ctx, cn).requestUpdateAll()
                Log.d(TAG, "requestUpdateAll() -> ${cn.shortClassName}  |  $cn")
            } catch (t: Throwable) {
                Log.d(TAG, "Complication class not found → skip: ${cn.className}")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "requestUpdateAllComplications failed: ${t.message}")
    }
}

/** Zakazivanje “backup” buđenja (watchdog). */
fun scheduleComplicationRefresh(ctx: Context, delayMs: Long) {
    val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val intent = Intent(ACTION_REFRESH_ALARM).setPackage(ctx.packageName)
    val pi = PendingIntent.getBroadcast(
        ctx,
        1001,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val triggerAt = SystemClock.elapsedRealtime() + delayMs
    try {
        am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        Log.d(TAG, "Alarm scheduled in ${delayMs}ms (≈ ${delayMs / 1000}s).")
    } catch (t: Throwable) {
        Log.w(TAG, "scheduleComplicationRefresh failed: ${t.message}")
    }
}

/** Helper za minute → ms. */
private fun mins(m: Long) = m * 60_000L
private fun secs(s: Long) = s * 1000L

/** Minimalna mrežna provjera (za stare grane koda) — i dalje koristimo za “gate” gdje treba. */
private fun hasNetwork(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.activeNetwork ?: run {
        Log.d(TAG, "hasNetwork: NO activeNetwork")
        return false
    }
    val caps = cm.getNetworkCapabilities(n) ?: run {
        Log.d(TAG, "hasNetwork: NO capabilities for activeNetwork")
        return false
    }
    val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    val internet  = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val transport = listOfNotNull(
        "WIFI".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        "CELL".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) },
        "BT".takeIf { caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) }
    ).joinToString(",")
    Log.d(TAG, "hasNetwork: validated=$validated internet=$internet transport=[$transport]")
    return validated || internet
}

/** Receiver koji reagira na BOOT i naš WATCHDOG alarm. */
class ComplicationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        Log.d(TAG, "onReceive action=$action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // 1) Registriraj NetworkCallback (globalno) – odmah će pokrenuti fetch kad mreža postane VALIDATED
                NetWatch.register(context.applicationContext, reason = "boot", debounceMs = 15_000L)
                // Ako je već VALIDATED u tom trenu → odmah pokreni jednokratni fetch
                NetWatch.pokeIfAlreadyValidated(context.applicationContext, reason = "boot-immediate")

                // 2) Pingni komplikacije (da probaju povući zadnje poznato)
                requestUpdateAllComplications(context)

                // 3) Dugi watchdog backup
                scheduleComplicationRefresh(context, mins(WATCHDOG_MIN))

                // (opcionalno) Stari WorkManager fallback
                try { WorkFallback.schedule(context) } catch (_: Throwable) {}
            }

            ACTION_REFRESH_ALARM -> {
                // Watchdog: osiguraj da smo “na liniji” s NetworkCallback-om
                NetWatch.register(context.applicationContext, reason = "watchdog", debounceMs = 15_000L)
                // Ako je mreža već VALIDATED upravo sada → odradi jednokratni fetch odmah
                NetWatch.pokeIfAlreadyValidated(context.applicationContext, reason = "watchdog-immediate")

                // Uvijek nudge-aj komplikacije
                requestUpdateAllComplications(context)

                // Re-armaj watchdog
                scheduleComplicationRefresh(context, mins(WATCHDOG_MIN))
            }

            // ➕ Tickovi kalendara tržišta – ne rade mrežu, samo pushnu repaint komplikacija
            ACTION_MARKET_OPEN_TICK,
            ACTION_MARKET_CLOSE_TICK -> {
                Log.d(TAG, "Market tick → requestUpdateAllComplications()")
                requestUpdateAllComplications(context)
            }

            else -> {
                // Ostali intenti: samo lagani nudge i re-armaj watchdog
                requestUpdateAllComplications(context)
                scheduleComplicationRefresh(context, mins(WATCHDOG_MIN))
            }
        }
    }
}
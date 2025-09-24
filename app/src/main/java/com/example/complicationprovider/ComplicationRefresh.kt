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
import com.example.complicationprovider.data.GoldFetcher

private const val TAG = "CompRefresh"

/** Custom akcija za naš alarm. */
const val ACTION_REFRESH_ALARM = "com.example.complicationprovider.ACTION_REFRESH_ALARM"

/** Profilirani intervali (usklađeni s točkom 5) */
private const val INTERVAL_SCREEN_ON_MIN = 2L     // agresivno kad gledaš sat
private const val INTERVAL_SCREEN_OFF_MIN = 6L    // rjeđe u pozadini

/** Jedini točan način kako pingati SVE naše complication datasourcere. */
fun requestUpdateAllComplications(ctx: Context) {
    try {
        val compNames = listOf(
            ComponentName(ctx, com.example.complicationprovider.complications.SpotComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RsiComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RocComplicationService::class.java),
        )
        compNames.forEach { cn ->
            ComplicationDataSourceUpdateRequester.create(ctx, cn).requestUpdateAll()
            Log.d(TAG, "requestUpdateAll() -> ${cn.shortClassName}  |  $cn")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "requestUpdateAllComplications failed: ${t.message}")
    }
}

private fun hasNetwork(ctx: Context): Boolean {
    val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val n = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(n) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Zakazivanje sljedećeg “buđenja” za refresh komplikacija. */
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

/** Receiver koji reagira na naš alarm i na “wakeup” trenutke sustava. */
class ComplicationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        Log.d(TAG, "onReceive action=$action")

        // Ako nema mreže → preskoči fetch, ipak pingaj komplikacije (da se UI “proba” osvježiti), pa pokušaj kasnije.
        val online = hasNetwork(context)
        if (!online) {
            Log.d(TAG, "No network → skip fetch, re-schedule later.")
            requestUpdateAllComplications(context)
            scheduleComplicationRefresh(context, mins(INTERVAL_SCREEN_OFF_MIN))
            return
        }

        when (action) {
            // Naš custom alarm → kratki ciklus: pobudi fetcher i pingaj komplikacije
            ACTION_REFRESH_ALARM -> {
                GoldFetcher.setAggressive(false)                // default “pozadina”
                GoldFetcher.start(context)                      // “poke” fetcher (ne otvara activity)
                requestUpdateAllComplications(context)
                scheduleComplicationRefresh(context, mins(INTERVAL_SCREEN_OFF_MIN))
            }

            // Uključivanje/otključavanje/boot → odmah refresh + agresivniji interval
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> {
                GoldFetcher.setAggressive(true)                 // brži ritam dok je zaslon aktivan
                GoldFetcher.start(context)
                requestUpdateAllComplications(context)
                scheduleComplicationRefresh(context, mins(INTERVAL_SCREEN_ON_MIN))
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Nakon boota: aktiviraj fetcher, pingaj komplikacije i startaj fallback WorkManager
                GoldFetcher.setAggressive(false)
                GoldFetcher.start(context)
                requestUpdateAllComplications(context)
                scheduleComplicationRefresh(context, mins(INTERVAL_SCREEN_OFF_MIN))
                WorkFallback.schedule(context)                  // točka 4
            }

            else -> {
                // Za svaki slučaj: refresh + re-schedule “pozadinski”
                GoldFetcher.setAggressive(false)
                GoldFetcher.start(context)
                requestUpdateAllComplications(context)
                scheduleComplicationRefresh(context, mins(INTERVAL_SCREEN_OFF_MIN))
            }
        }
    }
}
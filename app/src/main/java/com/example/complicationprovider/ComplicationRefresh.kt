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
import com.example.complicationprovider.net.NetworkWatcher

private const val TAG = "CompRefresh"

/** Custom akcija za naš alarm. */
const val ACTION_REFRESH_ALARM = "com.example.complicationprovider.ACTION_REFRESH_ALARM"

/** Profilirani intervali */
private const val INTERVAL_SCREEN_ON_MIN = 2L     // agresivno kad gledaš sat
private const val WATCHDOG_MIN            = 30L    // dugi backup, umjesto 6 min

/** Jedini točan način kako pingati SVE naše complication datasourcere. */
fun requestUpdateAllComplications(ctx: Context) {
    try {
        val compNames = listOf(
            ComponentName(ctx, com.example.complicationprovider.complications.SpotComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RsiComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.RocComplicationService::class.java),
            ComponentName(ctx, com.example.complicationprovider.complications.SparklineComplicationService::class.java),
        )
        compNames.forEach { cn ->
            ComplicationDataSourceUpdateRequester.create(ctx, cn).requestUpdateAll()
            Log.d(TAG, "requestUpdateAll() -> ${cn.shortClassName}  |  $cn")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "requestUpdateAllComplications failed: ${t.message}")
    }
}

/** Zakazivanje sljedećeg “buđenja” (watchdog). */
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

/** Brza provjera mreže samo za log/debug. */
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

/** Receiver: boot/screen/alarm. */
class ComplicationRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val ctx = context.applicationContext
        val action = intent?.action.orEmpty()
        Log.d(TAG, "onReceive action=$action")

        // Svaki put osiguraj mrežni callback (i instant probe unutra)
        NetworkWatcher.ensureRegistered(ctx)

        when (action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Boot: pokreni fetch odmah; mrežni watcher će i dalje reagirati na VALIDATED
                GoldFetcher.setAggressive(false)
                GoldFetcher.start(ctx)
                requestUpdateAllComplications(ctx)
                scheduleComplicationRefresh(ctx, mins(WATCHDOG_MIN))
            }

            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> {
                // Ekran upaljen: agresivniji ritam; stvarni “okidač” fetch-a je VALIDATED iz NetworkWatcher-a
                GoldFetcher.setAggressive(true)
                requestUpdateAllComplications(ctx)
                scheduleComplicationRefresh(ctx, mins(WATCHDOG_MIN))
            }

            ACTION_REFRESH_ALARM -> {
                // Watchdog: probudi petlju (ako je proces preživio bez eventa)
                GoldFetcher.setAggressive(false)
                GoldFetcher.start(ctx)
                requestUpdateAllComplications(ctx)
                scheduleComplicationRefresh(ctx, mins(WATCHDOG_MIN))
            }

            else -> {
                // Safety net
                GoldFetcher.setAggressive(false)
                GoldFetcher.start(ctx)
                requestUpdateAllComplications(ctx)
                scheduleComplicationRefresh(ctx, mins(WATCHDOG_MIN))
            }
        }

        // (Opcionalno) log stanja mreže za dijagnostiku
        hasNetwork(ctx)
    }
}
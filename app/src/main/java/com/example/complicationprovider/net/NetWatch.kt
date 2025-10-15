package com.example.complicationprovider.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.CancellationException

/**
 * Kombinira stari i novi NetWatch API — puni backward compatibility.
 */
object NetWatch {

    // ======== LEGACY API (za OneShotFetcher.kt) ========

    /** Je li mreža dostupna (bilo WiFi ili mobilna). */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Tekstualni opis trenutno aktivnog transporta. */
    fun activeTransport(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(net) ?: return "none"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bt"
            else -> "unknown"
        }
    }

    /** Suspendira dok se ne pojavi validna mreža (do timeouta). */
    suspend fun waitForInternet(context: Context, timeoutMs: Long = 4000L): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (isOnline(context)) return true
            delay(250)
        }
        return false
    }

    /** Legacy DNS warmup (samo lista hostova). */
    suspend fun warmupDns(hosts: List<String>): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        for (h in hosts) {
            val r = runCatching { InetAddress.getAllByName(h) }.isSuccess
            ok = ok or r
        }
        ok
    }

    // ======== NOVI API (za Workeri / Receivers) ========

    suspend fun <R> runWithNet(
        context: Context,
        reason: String? = null,
        warmupDns: Boolean = true,
        timeoutMs: Long = 4_000L,
        block: suspend () -> R
    ): R {
        if (!hasValidatedNetwork(context)) {
            delay(350)
            if (!hasValidatedNetwork(context))
                throw IllegalStateException("No validated network${reason?.let { " ($it)" } ?: ""}")
        }

        if (warmupDns) {
            withContext(Dispatchers.IO) {
                runCatching {
                    warmupDns(
                        context = context,
                        hosts = listOf("google.com", "api.metals.live", "api.metals.dev")
                    )
                }
            }
        }

        return withTimeoutSoft(timeoutMs) { block() }
    }

    suspend fun <R> withOnline(context: Context, reason: String? = null, block: suspend () -> R): R =
        runWithNet(context, reason, true, 4_000L, block)

    suspend fun <R> withOnline(context: Context, block: suspend () -> R): R =
        runWithNet(context, null, true, 4_000L, block)

    suspend fun <R> netGuard(context: Context, reason: String? = null, block: suspend () -> R): R =
        runWithNet(context, reason, true, 4_000L, block)

    suspend fun <R> netGuard(context: Context, block: suspend () -> R): R =
        runWithNet(context, null, true, 4_000L, block)

    fun pokeSockets(context: Context) {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifi != null && Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION")
                wifi.reassociate()
            }
        }
    }

    suspend fun warmupDns(context: Context, hosts: List<String>): Boolean = withContext(Dispatchers.IO) {
        var ok = false
        for (h in hosts) {
            val r = runCatching { InetAddress.getAllByName(h) }.isSuccess
            ok = ok or r
        }
        ok
    }

    private fun hasValidatedNetwork(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        val validated = if (Build.VERSION.SDK_INT >= 23)
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        else
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasTransport =
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        return validated && hasTransport
    }

    private suspend fun <T> withTimeoutSoft(timeoutMs: Long, block: suspend () -> T): T {
        if (timeoutMs <= 0L) return block()
        return kotlinx.coroutines.withTimeout(timeoutMs) { block() }
    }
}
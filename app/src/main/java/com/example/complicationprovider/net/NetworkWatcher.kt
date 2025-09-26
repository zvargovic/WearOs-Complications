package com.example.complicationprovider.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.example.complicationprovider.data.GoldFetcher

private const val TAG = "NetworkWatcher"

/**
 * Globalni osluškivač mreže.
 * - registrira se na bootu, u receiveru i u MainActivity
 * - čim mreža postane VALIDATED → pokreće fetch (bez age-gatea)
 * - pri registraciji napravi “instant probe”: ako je već VALIDATED, pokreni fetch odmah
 */
object NetworkWatcher {

    @Volatile private var registered = false
    private var cm: ConnectivityManager? = null
    private lateinit var appCtx: Context

    fun ensureRegistered(ctx: Context) {
        appCtx = ctx.applicationContext
        cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (!registered) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm!!.registerNetworkCallback(req, callback)
                registered = true
                Log.d(TAG, "registerNetworkCallback OK")
            } catch (t: Throwable) {
                Log.w(TAG, "registerNetworkCallback failed: ${t.message}")
            }
        }

        // Odmah provjeri trenutno stanje i, ako je VALIDATED, poženi fetch
        instantProbe()
    }

    fun unregister() {
        try { cm?.unregisterNetworkCallback(callback) } catch (_: Throwable) {}
        registered = false
        cm = null
    }

    // --- PRIVATE ---

    private fun instantProbe() {
        val mgr = cm ?: return
        val net = mgr.activeNetwork ?: run {
            Log.d(TAG, "probe: NO activeNetwork")
            return
        }
        val caps = mgr.getNetworkCapabilities(net) ?: run {
            Log.d(TAG, "probe: NO capabilities for activeNetwork")
            return
        }
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val internet  = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasWifi   = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasCell   = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        Log.d(
            TAG,
            "probe: validated=$validated internet=$internet transport=[${
                listOfNotNull(
                    "WIFI".takeIf { hasWifi }, "CELL".takeIf { hasCell }
                ).joinToString(",")
            }]"
        )

        if (validated) {
            Log.d(TAG, "probe → VALIDATED → start fetch NOW")
            GoldFetcher.start(appCtx)
        }
    }

    private val callback = object : NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "onAvailable: $network")
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "onLost: $network")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val internet  = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val hasWifi   = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val hasCell   = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

            Log.d(
                TAG,
                "onCapsChanged: validated=$validated internet=$internet transport=[${
                    listOfNotNull(
                        "WIFI".takeIf { hasWifi }, "CELL".takeIf { hasCell }
                    ).joinToString(",")
                }]"
            )

            if (validated) {
                Log.d(TAG, "VALIDATED → start fetch NOW")
                GoldFetcher.start(appCtx)
            }
        }
    }
}
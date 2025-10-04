package com.example.complicationprovider.net

import android.content.Context
import android.net.*
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// === Centralni import za OneShotFetcher (ali ga više ne zovemo osim ako flagovi dopuštaju)
import com.example.complicationprovider.data.OneShotFetcher

/**
 * NetWatch: prati mrežu i (po potrebi) triggira fetch.
 * SADA: po defaultu NEMA triggiranja; jedini izvor fetcha je WorkManager.
 */
object NetWatch {
    private const val TAG = "NetWatch"

    // --------- FEATURE FLAGS (podesivo) ----------
    /** Ako je true: na VALIDATED mrežu triggat će OneShotFetcher. Default: false (isključeno). */
    private const val TRIGGER_ON_VALIDATED = false
    /** Ako je true: na promjene Wi-Fi-ja triggat će OneShotFetcher. Default: false (isključeno). */
    private const val TRIGGER_ON_WIFI = false
    // ---------------------------------------------

    private var registered = false
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun start(context: Context) {
        if (registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable (network=${network.hashCode()})")
                maybeTrigger(context, reason = "available")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val cell = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                Log.d(TAG, "onCapabilitiesChanged validated=$validated wifi=$wifi cell=$cell")
                if (validated) {
                    maybeTrigger(context, reason = "validated-online")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                Log.d(TAG, "onLinkPropertiesChanged iface=${lp.interfaceName} dns=${lp.dnsServers}")
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost (network=${network.hashCode()})")
            }
        }

        cm.registerNetworkCallback(req, callback!!)
        registered = true
        Log.i(TAG, "NetWatch started (TRIGGER_ON_VALIDATED=$TRIGGER_ON_VALIDATED, TRIGGER_ON_WIFI=$TRIGGER_ON_WIFI)")
    }

    fun stop(context: Context) {
        if (!registered) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { cm.unregisterNetworkCallback(callback!!) }
        callback = null
        registered = false
        Log.i(TAG, "NetWatch stopped")
    }

    private fun maybeTrigger(context: Context, reason: String) {
        when (reason) {
            "validated-online" -> {
                if (TRIGGER_ON_VALIDATED) {
                    Log.i(TAG, "→ OneShotFetcher.run(reason=$reason)")
                    CoroutineScope(Dispatchers.Default).launch {
                        OneShotFetcher.run(context, reason)
                        Log.i(TAG, "OneShotFetcher.run DONE (reason=$reason)")
                    }
                } else {
                    Log.d(TAG, "suppressed trigger (validated-online) — orchestrator only")
                }
            }
            "available" -> {
                if (TRIGGER_ON_WIFI) {
                    Log.i(TAG, "→ OneShotFetcher.run(reason=$reason)")
                    CoroutineScope(Dispatchers.Default).launch {
                        OneShotFetcher.run(context, reason)
                        Log.i(TAG, "OneShotFetcher.run DONE (reason=$reason)")
                    }
                } else {
                    Log.d(TAG, "suppressed trigger (available) — orchestrator only")
                }
            }
            else -> {
                Log.d(TAG, "suppressed trigger ($reason)")
            }
        }
    }
}
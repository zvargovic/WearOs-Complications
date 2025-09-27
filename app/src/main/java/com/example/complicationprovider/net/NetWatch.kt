package com.example.complicationprovider.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.ConnectivityManager.NetworkCallback
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.complicationprovider.data.OneShotFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * NetWatch – sluša DEFAULT mrežu i čim postane ONLINE (INTERNET + VALIDATED),
 * uz debounce i detaljne logove, pokreće OneShotFetcher.run(...).
 *
 * - registerDefaultNetworkCallback (API ≥ 24) + fallback na registerNetworkCallback
 * - Debounce preko elapsedRealtime (otporno na promjenu sata)
 * - Kratki requestNetwork "hold" (8 s) da mreža ne padne odmah na Wear OS-u
 * - Glasni logovi: registracija, promjene capova/transporta, debounce razlozi, triggeri
 */
object NetWatch {
    private const val TAG = "NetWatch"

    // --- konfiguracija / state ---
    @Volatile private var cm: ConnectivityManager? = null
    @Volatile private var callback: NetworkCallback? = null
    @Volatile private var regCount: Int = 0

    @Volatile private var debounceMsCfg: Long = 15_000L
    @Volatile private var lastTriggerElapsed: Long = 0L
    @Volatile private var lastOnline: Boolean? = null
    @Volatile private var lastNetId: Long? = null

    private const val HOLD_MS: Long = 8_000L
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var holdCb: NetworkCallback? = null

    /** Registracija listenera; sigurno je zvati više puta (ref-count). */
    @Synchronized
    fun register(context: Context, reason: String, debounceMs: Long = 15_000L) {
        debounceMsCfg = debounceMs
        if (regCount++ > 0) {
            Log.d(TAG, "register(): already active (count=$regCount, reason=$reason)")
            // čak i ako je već registriran, odmah provjeri trenutno stanje (poštuje debounce)
            pokeIfAlreadyValidated(context.applicationContext, "register-again")
            return
        }

        val app = context.applicationContext
        val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val cb = object : NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "onAvailable: id=${nid(network)} (waiting for VALIDATED)")
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val netId = nid(network)
                val online = isOnline(caps)
                if (lastNetId != netId) {
                    Log.d(TAG, "onCapabilitiesChanged: NEW default net id=$netId → reset debounce")
                    lastNetId = netId
                    lastTriggerElapsed = 0L
                }

                Log.d(
                    TAG,
                    "onCapabilitiesChanged: id=$netId online=$online ${capSummary(caps)}"
                )

                if (online) {
                    holdNetworkFor(app, HOLD_MS)
                    maybeTrigger(app, "validated-online")
                } else {
                    // samo log; triggera se isključivo na ONLINE
                    logStateIfChanged(false, "capabilitiesChanged")
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "onLinkPropertiesChanged: id=${nid(network)} ${lpSummary(linkProperties)}")
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "onLost: id=${nid(network)}")
                if (lastNetId == nid(network)) {
                    lastNetId = null
                }
                logStateIfChanged(false, "onLost")
            }

            override fun onUnavailable() {
                Log.d(TAG, "onUnavailable")
                lastNetId = null
                logStateIfChanged(false, "onUnavailable")
            }
        }

        // Registracija: preferira default callback, uz fallback.
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                mgr.registerDefaultNetworkCallback(cb)
                Log.w(TAG, "REGISTER: registerDefaultNetworkCallback (reason=$reason, debounceMs=$debounceMsCfg)")
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                mgr.registerNetworkCallback(req, cb)
                Log.w(TAG, "REGISTER: legacy registerNetworkCallback (reason=$reason, debounceMs=$debounceMsCfg)")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "REGISTER default failed: ${t.message}", t)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            mgr.registerNetworkCallback(req, cb)
            Log.w(TAG, "REGISTER: FALLBACK registerNetworkCallback used (reason=$reason)")
        }

        cm = mgr
        callback = cb

        // Inicijalno stanje odmah (poštuje debounce).
        pokeIfAlreadyValidated(app, "initial")
    }

    /** Deregistracija (ref-count). */
    @Synchronized
    fun unregister(context: Context, reason: String) {
        if (--regCount > 0) {
            Log.d(TAG, "unregister(): still in use (count=$regCount, reason=$reason)")
            return
        }
        try {
            callback?.let { cb -> cm?.unregisterNetworkCallback(cb) }
            holdCb?.let { cb -> cm?.unregisterNetworkCallback(cb) }
            Log.w(TAG, "UNREGISTER (reason=$reason)")
        } catch (t: Throwable) {
            Log.w(TAG, "unregister error: ${t.message}", t)
        } finally {
            callback = null
            holdCb = null
            cm = null
            lastOnline = null
            lastNetId = null
        }
    }

    /** Ako je mreža već ONLINE, pokreni fetch (poštuje debounce). */
    fun pokeIfAlreadyValidated(context: Context, reason: String) {
        val app = context.applicationContext
        try {
            val mgr = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = mgr.activeNetwork
            val caps = if (net != null) mgr.getNetworkCapabilities(net) else null
            val online = isOnline(caps)
            Log.d(TAG, "pokeIfAlreadyValidated($reason): online=$online ${capSummary(caps)}")
            if (online) {
                holdNetworkFor(app, HOLD_MS)
                maybeTrigger(app, "poke-$reason")
            } else {
                logStateIfChanged(false, "poke-$reason")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pokeIfAlreadyValidated error: ${t.message}", t)
        }
    }

    // -------------------- interno --------------------

    @Synchronized
    private fun maybeTrigger(context: Context, reason: String) {
        logStateIfChanged(true, reason)
        val now = SystemClock.elapsedRealtime()
        val delta = now - lastTriggerElapsed
        if (delta < debounceMsCfg) {
            Log.d(TAG, "TRIGGER SKIP (debounce): delta=${delta}ms < ${debounceMsCfg}ms, reason=$reason")
            return
        }
        if (lastTriggerElapsed == 0L) {
            Log.d(TAG, "TRIGGER OK (first fire), reason=$reason")
        } else {
            Log.d(TAG, "TRIGGER OK (debounce passed ${delta}ms), reason=$reason")
        }
        lastTriggerElapsed = now
        triggerFetch(context, reason)
    }

    private fun triggerFetch(context: Context, reason: String) {
        Log.i(TAG, "→ OneShotFetcher.run(reason=$reason)")
        scope.launch {
            try {
                OneShotFetcher.run(context, reason = reason)
                Log.i(TAG, "OneShotFetcher.run DONE (reason=$reason)")
            } catch (t: Throwable) {
                Log.w(TAG, "OneShotFetcher failed: ${t.message}", t)
            }
        }
    }

    /** Zadrži mrežu kratko (HOLD_MS) da ne padne dok traje fetch (Wear OS policy). */
    private fun holdNetworkFor(ctx: Context, millis: Long) {
        val mgr = cm ?: (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : NetworkCallback() {}
        try {
            mgr.requestNetwork(req, cb)
            holdCb = cb
            Log.d(TAG, "holdNetworkFor: requested ${millis}ms")
            main.postDelayed({
                runCatching {
                    mgr.unregisterNetworkCallback(cb)
                    if (holdCb === cb) holdCb = null
                }.onSuccess {
                    Log.d(TAG, "holdNetworkFor: released")
                }.onFailure {
                    Log.w(TAG, "holdNetworkFor release failed: ${it.message}")
                }
            }, millis)
        } catch (t: Throwable) {
            Log.w(TAG, "holdNetworkFor error: ${t.message}")
        }
    }

    // --- util log/detect ---

    private fun isOnline(caps: NetworkCapabilities?): Boolean {
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun nid(n: Network): Long = n.hashCode().toLong()

    @Synchronized
    private fun logStateIfChanged(online: Boolean, src: String) {
        if (lastOnline == online) return
        lastOnline = online
        if (online) Log.w(TAG, "STATE: ONLINE  (src=$src)")
        else        Log.w(TAG, "STATE: OFFLINE (src=$src)")
    }

    private fun capSummary(c: NetworkCapabilities?): String {
        if (c == null) return "[caps=null]"
        val caps = mutableListOf<String>()
        fun add(b: Boolean, n: String) { if (b) caps.add(n) }
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), "INTERNET")
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), "VALIDATED")
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED), "NOT_METERED")
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED), "TRUSTED")
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING), "NOT_ROAMING")
        add(c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN), "NOT_VPN")

        val tr = mutableListOf<String>()
        fun t(b: Boolean, n: String) { if (b) tr.add(n) }
        t(c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "WIFI")
        t(c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), "CELL")
        t(c.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH), "BT")
        t(c.hasTransport(NetworkCapabilities.TRANSPORT_VPN), "VPN")
        t(c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), "ETH")

        val up = runCatching { c.linkUpstreamBandwidthKbps }.getOrDefault(-1)
        val dn = runCatching { c.linkDownstreamBandwidthKbps }.getOrDefault(-1)

        return "[caps=${caps.joinToString("|").ifEmpty { "—" }} " +
                "tr=${tr.joinToString("|").ifEmpty { "—" }} " +
                "up=${up}kbps down=${dn}kbps]"
    }

    private fun lpSummary(lp: LinkProperties): String {
        val iface = lp.interfaceName ?: "?"
        val dns = lp.dnsServers.joinToString(",") { it.hostAddress ?: it.toString() }
        val routes = lp.routes.joinToString(",") { r ->
            // naziv sučelja (API-ovi se razlikuju)
            val routeIf = runCatching { r.`interface` }.getOrNull()
                ?: runCatching { r.getInterface() }.getOrNull()
                ?: ""
            val dest = runCatching { r.destination?.toString() }.getOrNull() ?: r.toString()
            val def = runCatching { if (r.isDefaultRoute) "(def)" else "" }.getOrDefault("")
            "$dest/$routeIf$def"
        }
        return "[if=$iface dns=[$dns] routes=[$routes]]"
    }
}
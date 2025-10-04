package com.example.complicationprovider.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PowerManager
import android.util.Log
import com.example.complicationprovider.requestUpdateAllComplications
import com.example.complicationprovider.tiles.EmaSmaTileService
import com.example.complicationprovider.tiles.SparklineTileService
import com.example.complicationprovider.tiles.SpotTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Jednokratni fetch s PARTIAL_WAKE_LOCK-om i mrežnim sanity-jem.
 * - Čeka VALIDATED mrežu (WIFI/CELLULAR; ne VPN; iface != "lo").
 * - Nakon validacije prvo radi DNS preflight (po potrebi beskonačno).
 * - Tek kad DNS prođe, pokreće HTTP fetch.
 * - Po završetku pinga komplikacije i tileove.
 */
object OneShotFetcher {
    private const val TAG = "OneShotFetcher"
    private const val WAKE_TAG = "ComplicationProvider:FetchWakelock"

    // ===== PODESIVO =====
    /** Koliko dugo držati PARTIAL_WAKE_LOCK (ms). */
    private var WAKE_TIMEOUT_MS = 60_000L
    /** Koliko dugo čekati VALIDATED mrežu (ms). */
    private var NETWORK_WAIT_TIMEOUT_MS = 15_000L
    /** Grace nakon validacije mreže prije prvog DNS preflighta (ms). */
    private var POST_VALIDATE_GRACE_MS = 1_500L

    /** Koliko puta pokušati DNS preflight prije odustajanja.
     *  0 = BESKONAČNO (dok ne prođe). */
    private var MAX_DNS_PREFLIGHT_TRIES = 0

    /** Pauza između DNS preflight pokušaja (ms). */
    private var DNS_PREFLIGHT_RETRY_SLEEP_MS = 800L

    /** Timeout po DNS domeni tijekom preflighta (ms). */
    private var DNS_PREFLIGHT_PER_HOST_TIMEOUT_MS = 2_000L

    /** Domene koje moraju proći DNS prije fetcha. */
    private val DNS_PREFLIGHT_HOSTS = listOf(
        "data-asg.goldprice.org",
        "api.twelvedata.com",
        "www.investing.com"
    )

    /** Pokušaja samog fetcha (nakon uspješnog DNS-a). */
    private var MAX_FETCH_ATTEMPTS = 2
    /** Pauza između pokušaja fetcha (ms). */
    private var RETRY_SLEEP_MS = 1_200L

    /** Minimalni razmak između run-ova (ms) – debounce. */
    private var DEBOUNCE_WINDOW_MS = 20_000L
    // =====================

    private var lastRunMs: Long = 0

    /**
     * Pokreni jednokratni fetch. Vraća true ako je fetch uspio i spremljen.
     */
    suspend fun run(context: Context, reason: String = "manual"): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRunMs < DEBOUNCE_WINDOW_MS) {
            Log.i(TAG, "Debounce: ignoring trigger '$reason' (last run ${now - lastRunMs}ms ago)")
            return false
        }
        lastRunMs = now

        Log.d(TAG, "triggered by $reason")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
        }

        Log.d(TAG, "Acquire wakelock ($reason) for ${WAKE_TIMEOUT_MS}ms")
        wl.acquire(WAKE_TIMEOUT_MS)
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            // 1) Čekaj VALIDATED mrežu s ispravnim transportom / iface
            val net = awaitValidatedUsableNetwork(context, NETWORK_WAIT_TIMEOUT_MS)
            if (net == null) {
                Log.w(TAG, "No validated/usable network within ${NETWORK_WAIT_TIMEOUT_MS}ms")
                return false
            }

            // 2) Kratki grace nakon validacije
            if (POST_VALIDATE_GRACE_MS > 0) delay(POST_VALIDATE_GRACE_MS)

            // 3) Log LinkProperties + DNS liste
            logLinkProperties(cm, net)

            // 4) Privremeno bind proces na tu mrežu (i zapamti prijašnju)
            val prev = cm.getBoundNetworkForProcess()
            val boundOk = cm.bindProcessToNetwork(net)
            Log.d(TAG, "bindProcessToNetwork(${net.hashCode()}) -> $boundOk")

            try {
                // 5) DNS preflight – pokušavaj dok ne prođe (ili do max pokušaja ako je >0)
                var tries = 0
                val maxTxt = if (MAX_DNS_PREFLIGHT_TRIES <= 0) "∞" else MAX_DNS_PREFLIGHT_TRIES.toString()
                while (true) {
                    tries++
                    val ok = dnsPreflight()
                    Log.i(TAG, "DNS preflight: $ok (try $tries/$maxTxt)")
                    if (ok) break
                    if (MAX_DNS_PREFLIGHT_TRIES > 0 && tries >= MAX_DNS_PREFLIGHT_TRIES) {
                        Log.w(TAG, "DNS preflight failed → abort this run")
                        return false
                    }
                    delay(DNS_PREFLIGHT_RETRY_SLEEP_MS)
                }

                // 6) Pokušaji fetcha
                var ok = false
                repeat(MAX_FETCH_ATTEMPTS) { attempt ->
                    Log.i(TAG, "Fetch attempt ${attempt + 1}/$MAX_FETCH_ATTEMPTS (boundNetwork=$boundOk)")
                    ok = GoldFetcher.fetchOnce(context)
                    if (ok) return@repeat
                    if (attempt < MAX_FETCH_ATTEMPTS - 1) delay(RETRY_SLEEP_MS)
                }

                // 7) Ako uspjeh – pingni komplikacije i tileove
                if (ok) {
                    requestUpdateAllComplications(context)
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
            } finally {
                // 8) Vrati bind na prijašnje stanje
                val restored = cm.bindProcessToNetwork(prev)
                Log.d(TAG, "bindProcessToNetwork(prev=${prev?.hashCode()}) restore -> $restored")
            }
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

    /**
     * Čeka mrežu (WIFI/CELLULAR), INTERNET + VALIDATED, iface != "lo", NOT VPN.
     * Vraća Network ili null nakon timeouta.
     */
    private suspend fun awaitValidatedUsableNetwork(context: Context, timeoutMs: Long): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun usable(caps: NetworkCapabilities?, lp: LinkProperties?): Boolean {
            if (caps == null || lp == null) return false
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated   = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val notVpn      = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val goodTransport =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            val ifaceOk = lp.interfaceName?.lowercase() != "lo"
            return hasInternet && validated && notVpn && goodTransport && ifaceOk
        }

        // Brza provjera: aktivna mreža
        cm.activeNetwork?.let { n ->
            val caps = cm.getNetworkCapabilities(n)
            val lp   = cm.getLinkProperties(n)
            if (usable(caps, lp)) {
                Log.d(TAG, "Active network already usable (iface=${lp?.interfaceName})")
                return n
            }
        }

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Network?> { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        val caps = cm.getNetworkCapabilities(network)
                        val lp   = cm.getLinkProperties(network)
                        if (usable(caps, lp) && cont.isActive) {
                            Log.d(TAG, "onAvailable usable (iface=${lp?.interfaceName})")
                            cont.resume(network)
                            cm.unregisterNetworkCallback(this)
                        } else {
                            Log.d(TAG, "onAvailable (await provisioning...)")
                        }
                    }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        val lp = cm.getLinkProperties(network)
                        if (usable(caps, lp) && cont.isActive) {
                            Log.d(TAG, "onCapabilitiesChanged usable (iface=${lp?.interfaceName})")
                            cont.resume(network)
                            cm.unregisterNetworkCallback(this)
                        }
                    }
                    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                        val caps = cm.getNetworkCapabilities(network)
                        if (usable(caps, lp) && cont.isActive) {
                            Log.d(TAG, "onLinkPropertiesChanged usable (iface=${lp.interfaceName})")
                            cont.resume(network)
                            cm.unregisterNetworkCallback(this)
                        }
                    }
                    override fun onUnavailable() {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                cm.registerNetworkCallback(req, cb)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        }
    }

    /** Ispiši DNS i iface za izabranu mrežu. */
    private fun logLinkProperties(cm: ConnectivityManager, net: Network) {
        val lp = cm.getLinkProperties(net)
        val iface = lp?.interfaceName ?: "?"
        val dns = lp?.dnsServers?.joinToString(", ") { it.hostAddress ?: it.toString() } ?: "n/a"
        Log.i(TAG, "Network ${net.hashCode()} LinkProperties: dns=[$dns], iface=$iface")
    }

    /**
     * DNS preflight – pokuša resolve-ati ključne hostove.
     * Vraća true samo ako SVI hostovi prođu.
     */
    private suspend fun dnsPreflight(): Boolean {
        for (host in DNS_PREFLIGHT_HOSTS) {
            val ok = withTimeoutOrNull(DNS_PREFLIGHT_PER_HOST_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getAllByName(host) }
                        .onFailure { Log.w(TAG, "DNS preflight fail for $host: ${it.message}") }
                        .isSuccess
                }
            } ?: false
            if (!ok) return false
        }
        return true
    }
}
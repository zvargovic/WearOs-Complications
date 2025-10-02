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
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume

/**
 * Jednokratni fetch s PARTIAL_WAKE_LOCK-om.
 * - Drži CPU budnim dok traje mrežni dohvat (ekran se smije ugasiti).
 * - Prije fetcha čeka VALIDATED mrežu i DNS spremnost.
 * - Kratko "settle" kašnjenje nakon validacije da se LinkProperties ustabile.
 * - Za trajanje fetcha bind-a proces na aktivnu mrežu; na kraju unbind.
 * - DNS preflight s retry-em, detaljni DNS logovi.
 * - Nakon uspjeha pinga komplikacije i tile-ove.
 */
object OneShotFetcher {
    private const val TAG = "OneShotFetcher"
    private const val WAKE_TAG = "ComplicationProvider:FetchWakelock"

    // ===== PODESIVO (sve na jednom mjestu) =====
    /** Koliko dugo držati PARTIAL_WAKE_LOCK (ms). */
    private var WAKE_TIMEOUT_MS = 15_000L
    /** Koliko dugo čekati VALIDATED mrežu prije fetcha (ms). */
    private var NETWORK_WAIT_TIMEOUT_MS = 15_000L
    /** Kratki delay nakon validacije da se DNS/LinkProperties srede (ms). */
    private var POST_VALIDATION_SETTLE_MS = 1_000L
    /** Maks. vrijeme čekanja da se pojave DNS serveri u LinkProperties (ms). */
    private var DNS_READY_WAIT_MS = 2_000L
    /** Interval provjere DNS spremnosti (ms). */
    private var DNS_POLL_INTERVAL_MS = 250L
    /** Preflight DNS hostname-ovi koje koristimo u fetcheru. */
    private val DNS_PREFLIGHT_HOSTS = listOf(
        "data-asg.goldprice.org",
        "api.twelvedata.com",
        "www.investing.com"
    )
    /** Broj pokušaja DNS preflighta pri UnknownHost/rezolucijskim problemima. */
    private var DNS_PREFLIGHT_RETRIES = 100
    /** Pauza između DNS preflight pokušaja (exponential-ish) u ms. */
    private var DNS_PREFLIGHT_BACKOFF_MS = 400L
    /** Bindati proces na odabranu mrežu za vrijeme fetcha. */
    private var BIND_PROCESS_TO_NETWORK = true
    // ==========================================

    /**
     * Pokreni jednokratni fetch. Vraća true ako je fetch uspio i spremljen.
     */
    suspend fun run(context: Context, reason: String = "manual"): Boolean {
        Log.d(TAG, "triggered by $reason")

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
        }

        Log.d(TAG, "Acquire wakelock ($reason) for ${WAKE_TIMEOUT_MS}ms")
        wl.acquire(WAKE_TIMEOUT_MS)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var bound = false

        try {
            // 1) Pričekaj VALIDATED mrežu i dobij Network referencu
            val net: Network? = awaitValidatedNetwork(context, NETWORK_WAIT_TIMEOUT_MS)
            Log.d(TAG, "Network validated=${net != null} (waited up to ${NETWORK_WAIT_TIMEOUT_MS}ms)")

            // 2) Opcionalno bind na mrežu + kratki settle delay
            if (net != null && BIND_PROCESS_TO_NETWORK) {
                val ok = cm.bindProcessToNetwork(net)
                bound = ok
                Log.d(TAG, "bindProcessToNetwork(${net}) => $ok")
            }

            if (net != null) {
                // settle: da LinkProperties dobije DNS/route i sl.
                if (POST_VALIDATION_SETTLE_MS > 0) {
                    Log.d(TAG, "Post-validation settle for ${POST_VALIDATION_SETTLE_MS}ms")
                    delay(POST_VALIDATION_SETTLE_MS)
                }

                // 3) DNS ready čekanje (provjera da LinkProperties ima dnsServers)
                awaitDnsReady(cm, net, DNS_READY_WAIT_MS, DNS_POLL_INTERVAL_MS)

                // 4) DNS preflight s retry-jem (+ logiraj)
                dnsPreflightWithLogs(net, cm, DNS_PREFLIGHT_HOSTS, DNS_PREFLIGHT_RETRIES, DNS_PREFLIGHT_BACKOFF_MS)
            } else {
                Log.w(TAG, "No validated network; proceeding anyway (best effort).")
            }

            // 5) Fetch — logika unutra GoldFetcher-u (ne diramo)
            val ok = GoldFetcher.fetchOnce(context)

            if (ok) {
                // Nakon uspješnog spremanja podataka – pingni komplikacije i tile-ove
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
        } catch (t: Throwable) {
            Log.w(TAG, "OneShot fetch failed: ${t.message}", t)
            return false
        } finally {
            if (bound) {
                // Vrati proces na default routing
                (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                    .bindProcessToNetwork(null)
                Log.d(TAG, "unbindProcessFromNetwork")
            }
            if (wl.isHeld) {
                Log.d(TAG, "Release wakelock")
                wl.release()
            }
        }
    }

    /**
     * Čeka mrežu s NET_CAPABILITY_INTERNET i NET_CAPABILITY_VALIDATED.
     * Vrati Network čim je VALIDATED, ili null nakon timeouta.
     */
    private suspend fun awaitValidatedNetwork(context: Context, timeoutMs: Long): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Brza provjera: je li već aktivna VALIDATED mreža?
        cm.activeNetwork?.let { n ->
            cm.getNetworkCapabilities(n)?.let { caps ->
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    return n
                }
            }
        }

        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ConnectivityManager.NetworkCallback() {
                    private fun maybeResume(n: Network, caps: NetworkCapabilities) {
                        val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        if (ok && cont.isActive) {
                            cont.resume(n)
                            cm.unregisterNetworkCallback(this)
                        }
                    }

                    override fun onAvailable(network: Network) {
                        cm.getNetworkCapabilities(network)?.let { caps -> maybeResume(network, caps) }
                    }

                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        maybeResume(network, caps)
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

    /**
     * Čeka da LinkProperties za danu mrežu dobiju barem jedan DNS server.
     */
    private suspend fun awaitDnsReady(
        cm: ConnectivityManager,
        net: Network,
        timeoutMs: Long,
        pollMs: Long
    ) {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val lp: LinkProperties? = cm.getLinkProperties(net)
            val dns = lp?.dnsServers.orEmpty()
            if (dns.isNotEmpty()) {
                Log.d(TAG, "DNS ready: servers=${dns.joinToString { it.hostAddress ?: it.toString() }}; " +
                        "domains=${lp?.domains}; privateDns=${lp?.isPrivateDnsActive}(${lp?.privateDnsServerName})")
                return
            }
            Log.d(TAG, "DNS not ready yet (no servers). Poll again in ${pollMs}ms…")
            delay(pollMs)
        }
        val lp: LinkProperties? = cm.getLinkProperties(net)
        Log.w(TAG, "DNS not ready after ${timeoutMs}ms. lp=${lp}")
    }

    /**
     * DNS preflight: pokuša resolve za navedene hostove, s retry-em i logovima.
     */
    private suspend fun dnsPreflightWithLogs(
        net: Network,
        cm: ConnectivityManager,
        hosts: List<String>,
        retries: Int,
        backoffMs: Long
    ) {
        // Za log: pokaži trenutno viđene DNS-ove
        cm.getLinkProperties(net)?.let { lp ->
            Log.d(TAG, "Preflight LP: dns=${lp.dnsServers.joinToString { it.hostAddress ?: it.toString() }}, " +
                    "domains=${lp.domains}, privateDns=${lp.isPrivateDnsActive}(${lp.privateDnsServerName})")
        }

        var attempt = 0
        while (true) {
            val results = hosts.map { host ->
                runCatching {
                    // Važno: koristimo Network za rezoluciju (ako je moguće)
                    // InetAddress nema direktan overload za Network, pa OS koristi process binding.
                    // Kako smo već bindali proces, običan InetAddress će ići preko tog networka.
                    val addrs = InetAddress.getAllByName(host).toList()
                    host to addrs
                }
            }

            val failures = results.filter { it.isFailure }
            val success = results.filter { it.isSuccess }

            success.forEach {
                val (host, addrs) = it.getOrThrow()
                Log.d(TAG, "DNS preflight OK: $host -> ${addrs.joinToString { a -> a.hostAddress ?: a.toString() }}")
            }
            failures.forEach {
                Log.w(TAG, "DNS preflight FAIL: ${it.exceptionOrNull()?.message}")
            }

            if (failures.isEmpty()) return

            if (attempt >= retries) {
                Log.w(TAG, "DNS preflight still failing after ${attempt + 1} attempts; continuing anyway.")
                return
            }

            val delayMs = backoffMs * (attempt + 1)
            Log.d(TAG, "Retrying DNS preflight in ${delayMs}ms (attempt ${attempt + 1}/$retries)…")
            delay(delayMs)
            attempt++
        }
    }
}
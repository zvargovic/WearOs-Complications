package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import com.example.complicationprovider.net.LoggedHttp
import com.example.complicationprovider.net.NetWatch
import com.example.complicationprovider.tiles.TilePreRender
import com.example.complicationprovider.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orkestracija jednog ciklusa dohvaćanja.
 * - Single-flight: max 1 aktivan run u isto vrijeme (globalni guard)
 * - Debounce: ignorira triggere u zadnjih [DEBOUNCE_MS] ms (SharedPrefs)
 * - Svaki run koristi SVJEŽE OkHttp klijente (bez dijeljenja pool-ova)
 */
object OneShotFetcher {

    private const val TAG = "OneShotFetcher"

    // ---- global guard + debounce storage ----
    private val running = AtomicBoolean(false)
    private const val PREFS = "one_shot_guard"
    private const val KEY_LAST_OK_RUN_MS = "last_ok_run_ms"
    private const val DEBOUNCE_MS = 90_000L   // 90 s prozor

    /**
     * Nesuspend “sugar” za starije pozivatelje.
     * Slobodno koristite i dalje: OneShotFetcher.run(ctx, "reason")
     */
    fun run(ctx: Context, reason: String): Boolean = runBlocking {
        runOnce(ctx, reason)
    }

    /**
     * Glavni API (suspend).
     *
     * Redoslijed:
     *  1) Guard: single-flight + debounce
     *  2) Mreža + DNS warmup
     *  3) Svježi klijenti (odvojeni pool-ovi)
     *  4) Inject u GoldFetcher, fetch
     *  5) Tile prerender (ionako ga radi i GoldFetcher)
     *  6) Cleanup + bilježenje zadnjeg uspješnog run-a
     */
    suspend fun runOnce(context: Context, reason: String): Boolean = withContext(Dispatchers.IO) {
        val app = context.applicationContext

        // --- debounce (na zadnji USPJEŠAN run) ---
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastOk = prefs.getLong(KEY_LAST_OK_RUN_MS, 0L)
        val now = System.currentTimeMillis()
        val since = now - lastOk
        if (lastOk > 0 && since in 0..DEBOUNCE_MS) {
            Log.d(TAG, "Skip run(reason=$reason): debounced ${since}ms < ${DEBOUNCE_MS}ms.")
            FileLogger.writeLine("[RUN] skip • debounce ${since}ms < ${DEBOUNCE_MS}ms (reason=$reason)")
            return@withContext false
        }

        // --- single-flight guard ---
        if (!running.compareAndSet(false, true)) {
            Log.d(TAG, "Skip run(reason=$reason): already running.")
            FileLogger.writeLine("[RUN] skip • already running (reason=$reason)")
            return@withContext false
        }

        FileLogger.writeLine("[RUN] OneShot start (reason=$reason)")
        try {
            // 1) Mreža + DNS warmup
            val onlineBefore = NetWatch.isOnline(app)
            val transport = NetWatch.activeTransport(app)
            FileLogger.writeLine("[RUN] net=$transport online=$onlineBefore (reason=$reason)")
            NetWatch.waitForInternet(app, timeoutMs = 4000)
            NetWatch.warmupDns(
                listOf(
                    "www.investing.com",
                    "data-asg.goldprice.org",
                    "api.twelvedata.com",
                    "www.google.com"
                )
            )

            // 2) Svježi klijenti (odvojeni pool-ovi → nema “zaglavljivanja”)
            val longPool = ConnectionPool(0, 1, TimeUnit.SECONDS) // agresivno kolektiranje
            val shortPool = ConnectionPool(0, 1, TimeUnit.SECONDS)

            val fresh: OkHttpClient = LoggedHttp.new("HTTP-long").newBuilder()
                .connectionPool(longPool)
                .build()

            val freshShort: OkHttpClient = LoggedHttp.newShort("HTTP-short").newBuilder()
                .connectionPool(shortPool)
                .build()

            // 3) Inject u GoldFetcher i pokreni
            GoldFetcher.setHttpClient(fresh, freshShort)
            val ok = GoldFetcher.fetchOnce(app)

            // 4) (Tile prerender pokreće i GoldFetcher, ali i ovdje osiguramo)
            runCatching { TilePreRender.run(app) }

            // 5) Cleanup
            GoldFetcher.setHttpClient(null, null)
            longPool.evictAll()
            shortPool.evictAll()

            if (ok) {
                prefs.edit().putLong(KEY_LAST_OK_RUN_MS, System.currentTimeMillis()).apply()
            }

            FileLogger.writeLine("[RUN] OneShot end ok=$ok (reason=$reason)")
            ok
        } catch (t: Throwable) {
            FileLogger.writeLine("[RUN][ERR] ${t::class.java.simpleName}: ${t.message} (reason=$reason)")
            Log.w(TAG, "runOnce failed (reason=$reason): ${t.message}", t)
            // pokušaj barem iscrtati CLOSED/OPEN izgled
            runCatching { TilePreRender.run(app) }
            false
        } finally {
            running.set(false)
        }
    }

    // (Opcionalno) ako netko negdje zove staru verziju bez 'reason' — neka i dalje radi.
    @JvmOverloads
    fun run(context: Context): Boolean = run(context, "unspecified")

    @JvmOverloads
    suspend fun runOnce(context: Context): Boolean = runOnce(context, "unspecified")
}
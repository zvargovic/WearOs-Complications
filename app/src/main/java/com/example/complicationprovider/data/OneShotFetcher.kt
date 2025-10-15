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

/**
 * Orkestracija jednog ciklusa dohvaćanja.
 * Svaki run koristi SVJEŽE OkHttp klijente (normalni + “short”).
 */
object OneShotFetcher {

    private const val TAG = "OneShotFetcher"

    // ===== PUBLIC API =====
    /**
     * SINKRONI wrapper kako bi pozivatelji IZVAN korutina (BroadcastReceiver, itd.)
     * mogli zvati OneShotFetcher.run(ctx, "reason") bez promjena.
     */
    fun run(ctx: Context, reason: String): Boolean = runBlocking {
        runOnce(ctx, reason)
    }

    /**
     * SUSPEND varijanta za WorkManager/CoroutineScope pozive.
     */
    suspend fun runOnce(context: Context, reason: String): Boolean = withContext(Dispatchers.IO) {
        FileLogger.writeLine("[RUN] OneShot start • reason=$reason")
        try {
            // 1) Mreža + DNS warmup
            val onlineBefore = NetWatch.isOnline(context)
            val transport = NetWatch.activeTransport(context)
            FileLogger.writeLine("[RUN] net=$transport online=$onlineBefore")
            NetWatch.waitForInternet(context, timeoutMs = 4000)
            NetWatch.warmupDns(
                hosts = listOf(
                    "www.investing.com",
                    "data-asg.goldprice.org",
                    "api.twelvedata.com",
                    "www.google.com"
                )
            )

            // 2) Svježi klijenti (odvojeni pool-ovi → nema “zaglavljivanja”)
            val longPool = ConnectionPool(0, 1, TimeUnit.SECONDS) // odmah kolektiraj
            val shortPool = ConnectionPool(0, 1, TimeUnit.SECONDS)

            val fresh: OkHttpClient = LoggedHttp.new("HTTP-long").newBuilder()
                .connectionPool(longPool)
                .build()

            val freshShort: OkHttpClient = LoggedHttp.newShort("HTTP-short").newBuilder()
                .connectionPool(shortPool)
                .build()

            // 3) Inject u GoldFetcher i pokreni
            GoldFetcher.setHttpClient(fresh, freshShort)
            val ok = GoldFetcher.fetchOnce(context)

            // 4) (Tile prerender pokreće sam GoldFetcher → TilePreRender.run(context))
            // 5) Očisti reference
            GoldFetcher.setHttpClient(null, null)
            longPool.evictAll()
            shortPool.evictAll()

            FileLogger.writeLine("[RUN] OneShot end ok=$ok")
            ok
        } catch (t: Throwable) {
            FileLogger.writeLine("[RUN][ERR] ${t::class.java.simpleName}: ${t.message}")
            Log.w(TAG, "runOnce failed: ${t.message}", t)
            // pokušaćemo osigurati da Tile ipak prikaže CLOSED izgled
            runCatching { TilePreRender.run(context) }
            false
        }
    }
}
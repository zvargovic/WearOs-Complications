package com.example.complicationprovider.net

import android.util.Log
import com.example.complicationprovider.util.FileLogger
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pomoćni graditelj OkHttp klijenata s pojačanom dijagnostikom.
 * Ne zadržava globalno stanje – svaki poziv vraća SVJEŽI klijent.
 */
object LoggedHttp {

    private const val TAG = "LoggedHttp"

    /** Lagani log interceptor (bez tijela) da ne puni logove. */
    private val wiretap = Interceptor { chain ->
        val req: Request = chain.request()
        val t0 = System.nanoTime()
        FileLogger.writeLine("[HTTP] → ${req.method} ${req.url}")
        try {
            val resp: Response = chain.proceed(req)
            val t1 = System.nanoTime()
            val ms = (t1 - t0) / 1_000_000
            FileLogger.writeLine("[HTTP] ← ${resp.code} ${req.url}  ${ms}ms")
            resp
        } catch (t: Throwable) {
            FileLogger.writeLine("[HTTP][ERR] ${t::class.java.simpleName}: ${t.message}")
            throw t
        }
    }

    /** EventListener za mrežnu dijagnostiku (DNS/conn/tls). */
    private fun events(tag: String) = object : EventListener() {
        override fun dnsStart(call: okhttp3.Call, domainName: String) {
            FileLogger.writeLine("[$tag] dnsStart $domainName")
        }
        override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
            FileLogger.writeLine("[$tag] dnsEnd $domainName → ${inetAddressList.joinToString { it.hostAddress ?: "?" }}")
        }
        override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) {
            FileLogger.writeLine("[$tag] connectStart ${inetSocketAddress.hostString}:${inetSocketAddress.port}")
        }
        override fun secureConnectStart(call: okhttp3.Call) {
            FileLogger.writeLine("[$tag] tlsStart")
        }
        override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
            FileLogger.writeLine("[$tag] tlsEnd ${handshake?.cipherSuite}")
        }
        override fun connectFailed(
            call: okhttp3.Call,
            inetSocketAddress: java.net.InetSocketAddress,
            proxy: java.net.Proxy,
            protocol: okhttp3.Protocol?,
            ioe: IOException
        ) {
            FileLogger.writeLine("[$tag][ERR] connectFailed ${inetSocketAddress.hostString}:${inetSocketAddress.port} ${ioe.message}")
        }
    }

    /**
     * Svježi “produžni” klijent (dulji timeouts).
     * - Bez shared ConnectionPool-a (svaki je nov).
     * - Bez Cache-a (izbjegni “zaglavljena” stanja).
     */
    fun new(tag: String = TAG): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .eventListenerFactory { events(tag) }
            .addInterceptor(wiretap)
            .build()

    /** Kratki klijent za “brze” pozive (FX, pingovi). */
    fun newShort(tag: String = "$TAG-short"): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .eventListenerFactory { events(tag) }
            .addInterceptor(wiretap)
            .build()
}
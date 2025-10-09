// app/src/main/java/com/example/complicationprovider/net/LoggedHttp.kt
package com.example.complicationprovider.net

import android.net.ConnectivityManager
import android.net.Network
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Minimalni helper za izradu OkHttp klijenata koji su vezani na točno zadanu mrežu (Network).
 * - DNS ide preko Network.getAllByName() → izbjegava globalni negativni cache.
 * - socketFactory = net.socketFactory → sav promet ide kroz tu mrežu.
 * - Dva preseta timeouts: "short" i "long".
 *
 * Namjerno bez HttpLoggingInterceptor-a (nema dodatnih ovisnosti).
 */
object LoggedHttp {

    /** DNS koji resolve-a *preko zadanog Network-a*. */
    private class NetworkDns(private val net: Network) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            // Network#getAllByName baca UnknownHostException ako ne može resolve-ati
            val addrs = net.getAllByName(hostname)
            return addrs?.toList() ?: emptyList()
        }
    }

    /** Zajednički dio za oba klijenta. */
    private fun baseBuilder(net: Network): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .dns(NetworkDns(net))
            .socketFactory(net.socketFactory)
            .retryOnConnectionFailure(true)
            .pingInterval(15, TimeUnit.SECONDS) // HTTP/2 health

    /** Kratki timeouts – za brze JSON/REST pozive. */
    fun makeShortClient(cm: ConnectivityManager, net: Network): OkHttpClient =
        baseBuilder(net)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()

    /** Dulji timeouts – za scrape/teže stranice. */
    fun makeLongClient(cm: ConnectivityManager, net: Network): OkHttpClient =
        baseBuilder(net)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
}
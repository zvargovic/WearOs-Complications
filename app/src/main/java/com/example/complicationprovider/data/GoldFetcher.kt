package com.example.complicationprovider.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs

object GoldFetcher {
    private const val TAG = "GoldFetcher"

    // --- HTTP klijent ---
    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(20))
            .build()
    }

    // --- URL-ovi / user agent ---
    private val INVESTING_USD = listOf(
        "https://www.investing.com/currencies/xau-usd",
        "https://www.investing.com/commodities/gold?quote=1",
    )
    private val INVESTING_EUR = listOf(
        "https://www.investing.com/currencies/xau-eur",
        "https://www.investing.com/commodities/gold?quote=1",
    )

    private const val GOLDPRICE_USD_URL = "https://data-asg.goldprice.org/dbXRates/USD"
    private const val GOLDPRICE_EUR_URL = "https://data-asg.goldprice.org/dbXRates/EUR"

    private const val TD_PRICE_URL = "https://api.twelvedata.com/price"
    private const val TD_FX_URL = "https://api.twelvedata.com/exchange_rate"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0 Safari/537.36"

    // Rasponi i tolerancije (za sanity/median)
    private const val MIN_USD = 200.0
    private const val MAX_USD = 15000.0
    private const val MIN_EUR = 200.0
    private const val MAX_EUR = 10000.0
    private const val THR_USD = 1.5
    private const val THR_EUR = 1.2

    // Održavamo informaciju o “prvom fetchu” zbog politike kad je market zatvoren
    @Volatile private var didFirstFetch = false

    // ---------- JAVNI API: jednokratni fetch ----------
    /**
     * Jednokratno pokreni dohvat i spremanje (bez ikakvog loopa).
     * Vraća true ako je uspješno spremljeno.
     *
     * Pozivati ISKLJUČIVO iz OneShotFetcher-a.
     */
    suspend fun fetchOnce(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            performFetch(context)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "fetchOnce failed: ${t.message}", t)
            false
        }
    }

    // ---------- GLAVNI FETCH KORACI (interno) ----------
    private suspend fun performFetch(context: Context) {
        val repo = SettingsRepo(context)

        // Politika otvorenosti tržišta:
        // - Ako je prvo pokretanje u životnom ciklusu, dozvoli fetch i kad je zatvoreno (warm cache).
        // - Nakon toga, poštuj “market open”.
        val runNow = isMarketOpenUtc() || !didFirstFetch
        if (!runNow) {
            val now = ZonedDateTime.now(ZoneOffset.UTC)
            val until = timeUntilMarketOpen(now)
            Log.i(TAG, "[MARKET] Closed (UTC ${now.dayOfWeek} ${now.toLocalTime()}) — skip now, until open: $until")
            throw IllegalStateException("Market closed")
        }

        val settings = repo.flow.first()
        val apiKey = settings.apiKey.orEmpty()
        logHeader()

        // USD paralelno
        val usdDeferred = coroutineScope {
            async {
                listOf(
                    fetchGoldpriceUsd(),
                    fetchInvestingUsd(),
                    fetchTdUsd(apiKey),
                )
            }
        }
        // FX
        val fxDeferred = coroutineScope { async { fetchTdEurUsdRate(apiKey) } }
        // EUR bazni (GP + Investing)
        val eurDeferred = coroutineScope {
            async {
                listOf(
                    fetchGoldpriceEur(),
                    fetchInvestingEur(),
                )
            }
        }

        val usdQuotes = usdDeferred.await()
        val eurUsd = fxDeferred.await()
        val eurBase = eurDeferred.await()

        logUsdSection(usdQuotes)

        // TD EUR = TD_USD / (EUR/USD)
        val tdUsd = usdQuotes.find { it.name == "TD" }?.value
        val tdEur = tdUsd?.let { it.divide(eurUsd, 6, RoundingMode.HALF_UP) }

        val eurQuotes = buildList {
            addAll(eurBase)
            add(Quote("TD", tdEur?.setScale(2, RoundingMode.HALF_UP), unit = "EUR/oz", ccy = "EUR"))
        }
        logEurSection(eurQuotes, usdRef = pickRef(usdQuotes), eurRate = eurUsd)

        // ---- Snapshot → DataStore ----
        val usdCons = consensus(usdQuotes.filter { it.value != null })
        val eurCons = consensus(eurQuotes.filter { it.value != null })

        val snap = Snapshot(
            usdConsensus = usdCons.toDouble(),
            eurConsensus = eurCons.toDouble(),
            eurUsdRate = eurUsd.toDouble(),
            updatedEpochMs = System.currentTimeMillis(),
        )

        // 1) spremi snapshot + history (i pričekaj commit)
        repo.saveSnapshot(snap)
        repo.appendHistory(
            HistoryRec(
                ts = snap.updatedEpochMs,
                usd = snap.usdConsensus,
                eur = snap.eurConsensus,
                fx  = snap.eurUsdRate
            )
        )
        Log.i(TAG, "[SNAPSHOT] saved: USD=${fmt(usdCons)} EUR=${fmt(eurCons)} FX=$eurUsd")

        // 2) Log indikatora (sada povijest uključuje i ovaj zapis)
        val history = repo.historyFlow.first()
        logIndicators(history)

        // označi da smo odradili barem jedan fetch (radi market-closed politike)
        didFirstFetch = true
    }

    // ----------------- INDIKATORI: log -----------------
    private fun logIndicators(history: List<HistoryRec>) {
        if (history.isEmpty()) {
            Log.i(TAG, "[IND] Nema history zapisa još.")
            return
        }
        val closeEur = history.map { it.eur }.filter { it > 0.0 }
        val closeUsd = history.map { it.usd }.filter { it > 0.0 }

        val pRsi = 14
        val pShort = 9
        val pMid = 20
        val pLong = 50

        fun d2(v: Double?) = if (v == null) "n/a" else DecimalFormat("0.00",
            DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }).format(v)

        val dayStartUtc = ZonedDateTime.now(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC)
            .toInstant().toEpochMilli()

        val eurRsi = Indicators.rsi(closeEur, pRsi)
        val eurSma20 = Indicators.sma(closeEur, pMid)
        val eurEma20 = Indicators.ema(closeEur, pMid)
        val eurStd20 = Indicators.stddev(closeEur, pMid)
        val eurRoc1  = Indicators.roc(closeEur, 1)
        val (eurMin, eurMax) = Indicators.dayMinMax(history, dayStartUtc) { it.eur }

        Log.i(TAG,
            "[IND][EUR] close=${d2(closeEur.lastOrNull())}  RSI$pRsi=${d2(eurRsi)}  " +
                    "SMA$pMid=${d2(eurSma20)}  EMA$pMid=${d2(eurEma20)}  " +
                    "σ$pMid=${d2(eurStd20)}  ROC1=${d2(eurRoc1)}%  DayMin=${d2(eurMin)}  DayMax=${d2(eurMax)}"
        )

        val usdRsi = Indicators.rsi(closeUsd, pRsi)
        val usdSma20 = Indicators.sma(closeUsd, pMid)
        val usdEma20 = Indicators.ema(closeUsd, pMid)
        val usdStd20 = Indicators.stddev(closeUsd, pMid)
        val usdRoc1  = Indicators.roc(closeUsd, 1)
        val (usdMin, usdMax) = Indicators.dayMinMax(history, dayStartUtc) { it.usd }

        Log.i(TAG,
            "[IND][USD] close=${d2(closeUsd.lastOrNull())}  RSI$pRsi=${d2(usdRsi)}  " +
                    "SMA$pMid=${d2(usdSma20)}  EMA$pMid=${d2(usdEma20)}  " +
                    "σ$pMid=${d2(usdStd20)}  ROC1=${d2(usdRoc1)}%  DayMin=${d2(usdMin)}  DayMax=${d2(usdMax)}"
        )

        val eurSma9 = Indicators.sma(closeEur, pShort)
        val eurSma50 = Indicators.sma(closeEur, pLong)
        val usdSma9 = Indicators.sma(closeUsd, pShort)
        val usdSma50 = Indicators.sma(closeUsd, pLong)

        Log.i(TAG, "[IND][EUR] SMA$pShort=${d2(eurSma9)}  SMA$pLong=${d2(eurSma50)}")
        Log.i(TAG, "[IND][USD] SMA$pShort=${d2(usdSma9)}  SMA$pLong=${d2(usdSma50)}")
    }

    // ----- market status -----
    private fun isMarketOpenUtc(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): Boolean {
        //return when (now.dayOfWeek) {
            //DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> false
            //else -> true
        return true
    }

    private fun timeUntilMarketOpen(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): String {
        return when (now.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> {
                val nextOpen = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                val dur = Duration.between(now, nextOpen)
                val h = dur.toHours()
                val m = dur.minusHours(h).toMinutes()
                "${h}h ${m}m"
            }
            else -> "0h 0m"
        }
    }

    // ----- modeli/pomagala -----
    private data class Quote(
        val name: String,
        val value: BigDecimal?,
        val unit: String = "USD/oz",
        val ccy: String = "USD",
    )

    private fun req(url: String): Request =
        Request.Builder()
            .url(url)
            .addHeader("User-Agent", UA)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9,hr;q=0.8")
            .addHeader("Referer", "https://www.google.com/")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Pragma", "no-cache")
            .build()

    private fun normalizeNumber(raw0: String): Double {
        var s = raw0.trim().replace("\u00A0", "").replace("\u202F", "").replace(" ", "")
        val lastDot = s.lastIndexOf('.')
        val lastCom = s.lastIndexOf(',')
        s = if (lastDot != -1 && lastCom != -1) {
            if (lastCom > lastDot) s.replace(".", "").replace(",", ".") else s.replace(",", "")
        } else {
            if (s.contains(',') && !s.contains('.')) s.replace(".", "").replace(",", ".") else s
        }
        return s.toDouble()
    }

    // ----- dohvat -----
    private fun fetchGoldpriceUsd(): Quote = try {
        client.newCall(req(GOLDPRICE_USD_URL)).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val items = JSONObject(body).optJSONArray("items")
            val item = items?.optJSONObject(0)
            val price = when {
                item?.has("xauPrice") == true -> item.getDouble("xauPrice")
                item?.has("XAUPrice") == true -> item.getDouble("XAUPrice")
                item?.has("xau") == true -> item.getDouble("xau")
                else -> Double.NaN
            }
            if (price.isNaN()) error("key missing")
            Quote("Goldprice.org", price.toBigDecimal().setScale(2, RoundingMode.HALF_UP)).also {
                Log.i(TAG, "[OK] Goldprice.org: XAU/USD = ${fmt(it.value)} USD/oz")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] Goldprice.org failed: ${t.message}")
        Quote("Goldprice.org", null)
    }

    private fun fetchGoldpriceEur(): Quote = try {
        client.newCall(req(GOLDPRICE_EUR_URL)).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val items = JSONObject(body).optJSONArray("items")
            val item = items?.optJSONObject(0)
            val price = when {
                item?.has("xauPrice") == true -> item.getDouble("xauPrice")
                item?.has("XAUPrice") == true -> item.getDouble("XAUPrice")
                item?.has("xau") == true -> item.getDouble("xau")
                else -> Double.NaN
            }
            if (price.isNaN()) error("key missing")
            Quote(
                "Goldprice.org",
                price.toBigDecimal().setScale(2, RoundingMode.HALF_UP),
                unit = "EUR/oz",
                ccy = "EUR",
            ).also {
                Log.i(TAG, "[OK] Goldprice.org: XAU/EUR = ${fmt(it.value)} EUR/oz")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] Goldprice.org (EUR) failed: ${t.message}")
        Quote("Goldprice.org", null, unit = "EUR/oz", ccy = "EUR")
    }

    private fun fetchInvestingUsd(): Quote = try {
        val html = INVESTING_USD.firstNotNullOfOrNull { url ->
            try { client.newCall(req(url)).execute().use { it.body?.string() } } catch (_: Throwable) { null }
        } ?: error("no page")
        val value = parseInvestingPriceFromHtml(html, "USD") ?: error("extract fail")
        Quote("Investing", value.toBigDecimal().setScale(2, RoundingMode.HALF_UP)).also {
            Log.i(TAG, "[OK] Investing: XAU/USD = ${fmt(it.value)} USD/oz")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] Investing failed: ${t.message}")
        Quote("Investing", null)
    }

    private fun fetchInvestingEur(): Quote = try {
        val html = INVESTING_EUR.firstNotNullOfOrNull { url ->
            try { client.newCall(req(url)).execute().use { it.body?.string() } } catch (_: Throwable) { null }
        } ?: error("no page")
        val value = parseInvestingPriceFromHtml(html, "EUR") ?: error("extract fail")
        Quote("Investing", value.toBigDecimal().setScale(2, RoundingMode.HALF_UP), unit = "EUR/oz", ccy = "EUR").also {
            Log.i(TAG, "[OK] Investing: XAU/EUR = ${fmt(it.value)} EUR/oz")
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] Investing (EUR) failed: ${t.message}")
        Quote("Investing", null, unit = "EUR/oz", ccy = "EUR")
    }

    private fun fetchTdUsd(apiKey: String): Quote = try {
        require(apiKey.isNotBlank()) { "Missing TD API key" }
        val url = "$TD_PRICE_URL?symbol=XAU/USD&apikey=$apiKey"
        client.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val price = JSONObject(body).optString("price").takeIf { it.isNotBlank() }?.toDoubleOrNull()
                ?: error("no price")
            Quote("TD", price.toBigDecimal().setScale(2, RoundingMode.HALF_UP)).also {
                Log.i(TAG, "[OK] TD: XAU/USD = ${fmt(it.value)} USD/oz")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] TD failed: ${t.message}")
        Quote("TD", null)
    }

    private fun fetchTdEurUsdRate(apiKey: String): BigDecimal = try {
        require(apiKey.isNotBlank()) { "Missing TD API key" }
        val url = "$TD_FX_URL?symbol=EUR/USD&apikey=$apiKey"
        client.newCall(req(url)).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val js = JSONObject(body)
            val rate = js.optDouble("rate", js.optDouble("price", js.optDouble("value", Double.NaN)))
            if (rate.isNaN() || rate <= 0.0) error("invalid rate")
            rate.toBigDecimal().setScale(6, RoundingMode.HALF_UP).also {
                Log.i(TAG, "[OK] FX: EUR/USD = $it")
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "[WARN] FX failed: ${t.message}")
        BigDecimal.ONE
    }

    // ----- Investing parser -----
    private fun parseInvestingPriceFromHtml(html: String, currency: String): Double? {
        val (lo, hi) = if (currency.equals("USD", true)) MIN_USD to MAX_USD else MIN_EUR to MAX_EUR
        fun ok(v: Double) = v in lo..hi

        Regex("""data-test="instrument-price-last"[^>]*>(.*?)</""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)?.let { inner ->
            Regex("""[\d\.,\u00A0\u202F]+""").findAll(inner).map { it.value }
                .sortedByDescending { it.length }.forEach { cand ->
                    runCatching { normalizeNumber(cand) }.getOrNull()?.let { if (ok(it)) return it }
                }
        }

        Regex("""id="last_last"[^>]*>(.*?)<""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
            ?.replace(Regex("<[^>]+>"), "")?.trim()
            ?.let { runCatching { normalizeNumber(it) }.getOrNull()?.let { v -> if (ok(v)) return v } }

        Regex(""""last"\s*:\s*"([\d\.,\u00A0\u202F]+)""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let { runCatching { normalizeNumber(it) }.getOrNull()?.let { v -> if (ok(v)) return v } }

        Regex("""itemprop="price"\s+content="([\d\.,\u00A0\u202F]+)""",
            setOf(RegexOption.IGNORE_CASE),
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let { runCatching { normalizeNumber(it) }.getOrNull()?.let { v -> if (ok(v)) return v } }

        return null
    }

    // ----- Log sekcije / statistika -----
    private fun logHeader() {
        Log.i(TAG, "Starting XAU multi (USD → EUR).")
    }

    private fun logUsdSection(quotes: List<Quote>) {
        val ok = quotes.filter { it.value != null }
        ok.forEach { Log.i(TAG, "[OK] ${it.name}: XAU/USD = ${fmt(it.value)} USD/oz") }

        val sp = spread(ok)
        if (sp != null) {
            val inv = ok.find { it.name == "Investing" }?.value
            val td = ok.find { it.name == "TD" }?.value
            val gp = ok.find { it.name == "Goldprice.org" }?.value
            val dTdInv = if (td != null && inv != null) fmt(td - inv) else "n/a"
            val dGpInv = if (gp != null && inv != null) fmt(gp - inv) else "n/a"
            Log.i(TAG, "[A] USD spread (max–min): ${fmt(sp)} USD/oz  |  ΔTD-Inv: $dTdInv  ΔGP-Inv: $dGpInv")
            val cons = consensus(ok)
            Log.i(TAG, "[CONSENSUS] XAU/USD = ${fmt(cons)} USD/oz  |  conf=1.00 (spread=${fmt(sp)} USD/oz; kept=${ok.size}/${quotes.size})")
        } else {
            Log.w(TAG, "[CONSENSUS] Nema dovoljno valjanih USD izvora.")
        }
    }

    private fun logEurSection(eurQuotes: List<Quote>, usdRef: Quote?, eurRate: BigDecimal) {
        eurQuotes.forEach { Log.i(TAG, "[OK] ${it.name}: XAU/EUR = ${fmt(it.value)} EUR/oz") }
        val sp = spread(eurQuotes)
        if (sp != null) {
            val inv = eurQuotes.find { it.name == "Investing" }?.value
            val td = eurQuotes.find { it.name == "TD" }?.value
            val gp = eurQuotes.find { it.name == "Goldprice.org" }?.value
            val dTdInv = if (td != null && inv != null) fmt(td - inv) else "n/a"
            val dGpInv = if (gp != null && inv != null) fmt(gp - inv) else "n/a"
            Log.i(TAG, "[A] EUR spread (max–min): ${fmt(sp)} EUR/oz  |  ΔTD-Inv: $dTdInv  ΔGP-Inv: $dGpInv")
            val cons = consensus(eurQuotes)
            val usdText = usdRef?.value?.let { fmt(it) } ?: "n/a"
            Log.i(TAG, "[CONSENSUS] XAU/EUR = ${fmt(cons)} EUR/oz  |  conf=1.00 (spread=${fmt(sp)} EUR/oz; kept=${eurQuotes.size}/${eurQuotes.size}) | XAU/USD = $usdText USD/oz, Tečaj EUR/USD = $eurRate")
        } else {
            Log.w(TAG, "[CONSENSUS] Nema dovoljno valjanih EUR izvora.")
        }
    }

    private fun pickRef(quotes: List<Quote>): Quote? =
        quotes.firstOrNull { it.name == "TD" }
            ?: quotes.firstOrNull { it.name == "Investing" }
            ?: quotes.firstOrNull()

    private fun spread(quotes: List<Quote>): BigDecimal? {
        val vals = quotes.mapNotNull { it.value }
        if (vals.size < 2) return null
        val mx = vals.maxOrNull()!!
        val mn = vals.minOrNull()!!
        return mx.subtract(mn).setScale(2, RoundingMode.HALF_UP)
    }

    private fun consensus(quotes: List<Quote>): BigDecimal {
        val vals = quotes.mapNotNull { it.value }
        if (vals.isEmpty()) return BigDecimal.ZERO
        val sorted = vals.sorted()
        val med = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            sorted[sorted.size / 2 - 1].add(sorted[sorted.size / 2])
                .divide(BigDecimal(2), 6, RoundingMode.HALF_UP)
        }
        val isEur = quotes.firstOrNull()?.ccy == "EUR"
        val thr = if (isEur) THR_EUR else THR_USD
        val kept = vals.filter { abs(it.subtract(med).toDouble()) <= thr }
        val base = if (kept.isEmpty()) vals else kept
        val sum = base.reduce { a, b -> a.add(b) }
        return sum.divide(BigDecimal(base.size), 6, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP)
    }

    private operator fun BigDecimal.minus(other: BigDecimal): BigDecimal =
        this.subtract(other).setScale(2, RoundingMode.HALF_UP)

    private fun fmt(v: BigDecimal?): String =
        if (v == null) "n/a"
        else DecimalFormat("0.00", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }).format(v)
}
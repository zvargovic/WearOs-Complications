package com.example.complicationprovider.complications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateUtils
import android.util.Log
import androidx.core.content.getSystemService
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.ACTION_MARKET_CLOSE_TICK
import com.example.complicationprovider.ACTION_MARKET_OPEN_TICK
import com.example.complicationprovider.R
import com.example.complicationprovider.data.SnapshotStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.*
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * EUR ranged komplikacija (čita direktno iz SnapshotStore/DataStore):
 *  - OPEN  → RangedValue 0..100: value = pozicija SPOT cijene unutar DANAŠNJEG [min..max]
 *            (tekst = "XX%"; bez min/max labela)
 *            SPOT = zadnja non-null točka iz 5-min serije (danas).
 *  - CLOSED→ RangedValue countdown C→O ili SHORT_TEXT (tekst = vrijeme do otvaranja)
 *
 * Market kalendar: OTVORENO pon–pet (00:00–24:00) po UTC; vikend zatvoreno.
 */
class RangedPriceComplicationService : ComplicationDataSourceService() {

    private val TAG = "RangedPriceComp"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Ako tvoj watchface crta ranged “obrnuto”, prebaciti na true
    private val INVERT_ARC = false

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.RANGED_VALUE -> {
            // Preview OPEN (pozicija ~40%)
            rangedOpenNormalized(
                spot = 3213.0,
                dayMin = 3200.0,
                dayMax = 3240.0
            )
        }
        ComplicationType.SHORT_TEXT -> {
            // Preview CLOSED
            shortClosed("1h 23m")
        }
        else -> null
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val type = request.complicationType
        val app = applicationContext

        scope.launch {
            try {
                // 1) Market timing (UTC)
                val nowUtc = Instant.now().atZone(ZoneOffset.UTC)
                val marketOpen = isMarketOpenUtc(nowUtc)

                if (!marketOpen) {
                    val nextOpenMs = nextOpenUtcMs(nowUtc)
                    val lastCloseMs = lastCloseUtcMs(nowUtc)
                    scheduleTick(app, ACTION_MARKET_OPEN_TICK, nextOpenMs)

                    val data = when (type) {
                        ComplicationType.RANGED_VALUE -> rangedClosed(
                            nowMs = System.currentTimeMillis(),
                            lastCloseMs = lastCloseMs,
                            nextOpenMs = nextOpenMs
                        )
                        ComplicationType.SHORT_TEXT -> shortClosed(
                            timeToOpenText = humanTimeTo(nextOpenMs - System.currentTimeMillis())
                        )
                        else -> null
                    }
                    listener.onComplicationData(data ?: NoDataComplicationData())
                    return@launch
                }

                // 2) OPEN → čitaj iz SnapshotStore (današnji zapis)
                //    Uzimamo 5-min seriju (288) da dohvatimo zadnju non-null kao SPOT.
                val day = SnapshotStore.get(app, slots = 288)

                val dayMin = day.min ?: 0.0
                val dayMax = day.max ?: 0.0
                val spot = lastNonNull(day.series) ?: day.open ?: dayMax

                Log.d(TAG, "OPEN(DataStore) → day=${day.dayKey} spot=$spot min=$dayMin max=$dayMax updated=${day.updatedMs}")

                // Ako nemamo smislen raspon, malo ga raširi oko spota
                val (lo, hi) = ensureRange(spot, dayMin, dayMax)

                val data = when (type) {
                    ComplicationType.RANGED_VALUE ->
                        rangedOpenNormalized(spot = spot, dayMin = lo, dayMax = hi)
                    ComplicationType.SHORT_TEXT ->
                        shortOpen(spot = spot) // € zaokruženi
                    else -> null
                }

                val nextCloseMs = nextCloseUtcMs(nowUtc)
                scheduleTick(app, ACTION_MARKET_CLOSE_TICK, nextCloseMs)

                listener.onComplicationData(data ?: NoDataComplicationData())
            } catch (t: Throwable) {
                Log.w(TAG, "onComplicationRequest error: ${t.message}", t)
                listener.onComplicationData(NoDataComplicationData())
            }
        }
    }

    // ---------------- helpers (DataStore) ----------------

    private fun lastNonNull(list: List<Double?>): Double? {
        for (i in list.indices.reversed()) {
            val v = list[i]
            if (v != null && v.isFinite() && v > 0.0) return v
        }
        return null
    }

    private fun ensureRange(pivot: Double, minV: Double?, maxV: Double?): Pair<Double, Double> {
        var lo = minV ?: pivot
        var hi = maxV ?: pivot
        if (!lo.isFinite() || !hi.isFinite() || hi - lo <= 1e-6) {
            val pad = max(kotlin.math.abs(pivot) * 0.005, 0.5) // ~0.5% ili min 0.5 €
            lo = pivot - pad
            hi = pivot + pad
        }
        if (lo > hi) lo = hi.also { _ -> /* swap handled by min/max in builders anyway */ }
        return lo to hi
    }

    // ---------------- builders ----------------

    /** Normalizirani ranged prikaz: današnji min..max (iz DataStore) → 0..100 [%] */
    private fun rangedOpenNormalized(
        spot: Double,
        dayMin: Double,
        dayMax: Double
    ): ComplicationData {
        val lo = min(dayMin, dayMax)
        val hi = max(dayMin, dayMax)
        val range = (hi - lo).coerceAtLeast(1e-9)
        val frac = ((spot - lo) / range).coerceIn(0.0, 1.0)   // 0..1
        val pct  = (frac * 100.0).toInt()                     // 0..100

        val drawPct = if (INVERT_ARC) (100 - pct).coerceIn(0, 100) else pct

        Log.d(TAG, "rangedOpen(norm DS) → spot=$spot min=$lo max=$hi frac=$frac pct=$pct drawPct=$drawPct")

        val percentText = "$pct%"

        return RangedValueComplicationData.Builder(
            /* value = */ drawPct.toFloat(),
            /* min   = */ 0f,
            /* max   = */ 100f,
            /* text  = */ PlainComplicationText.Builder(percentText).build()
        )
            // Neki watchfaceovi na ranged slotu prikazuju samo 'title' – stavi isto
            .setTitle(PlainComplicationText.Builder(percentText).build())
            .setMonochromaticImage(providerIconOpen())
            .build()
    }

    /** SHORT_TEXT u OPEN: € zaokruženo (npr. €3379) */
    private fun shortOpen(spot: Double): ComplicationData {
        val text = PlainComplicationText.Builder(
            String.format(Locale.getDefault(), "€%.0f", spot)
        ).build()
        return ShortTextComplicationData.Builder(
            text,
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        ).setMonochromaticImage(providerIconOpen()).build()
    }

    private fun rangedClosed(
        nowMs: Long,
        lastCloseMs: Long,
        nextOpenMs: Long
    ): ComplicationData {
        val lo = lastCloseMs.toFloat()
        val hi = nextOpenMs.toFloat()
        val v = nowMs.toFloat().coerceIn(lo, hi)

        return RangedValueComplicationData.Builder(
            v, lo, hi,
            PlainComplicationText.Builder("C → O").build()
        )
            .setTitle(PlainComplicationText.Builder(humanTimeTo(nextOpenMs - nowMs)).build())
            .setMonochromaticImage(providerIconClosed())
            .build()
    }

    private fun shortClosed(timeToOpenText: String): ComplicationData {
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(timeToOpenText).build(),
            PlainComplicationText.Builder("C → O").build()
        ).setMonochromaticImage(providerIconClosed()).build()
    }

    // ---------------- icons ----------------

    private fun providerIconOpen(): MonochromaticImage {
        val icon: Icon = Icon.createWithResource(this, R.drawable.ic_gold_market_open)
        return MonochromaticImage.Builder(icon).build()
    }

    private fun providerIconClosed(): MonochromaticImage {
        val icon: Icon = Icon.createWithResource(this, R.drawable.ic_gold_market_closed)
        return MonochromaticImage.Builder(icon).build()
    }

    // ---------------- timing helpers (UTC market: Mon–Fri open, weekend closed) ----------------

    /** Otvoreno pon–pet (UTC), zatvoreno sub/ned. */
    private fun isMarketOpenUtc(nowUtc: ZonedDateTime): Boolean {
        val dow = nowUtc.dayOfWeek.value // Mon=1..Sun=7
        return dow in 1..5
    }

    /** Sljedeće otvaranje: prva iduća ponoć (00:00 UTC) koja pada na pon–pet. */
    private fun nextOpenUtcMs(nowUtc: ZonedDateTime): Long {
        var d = nowUtc.toLocalDate()
        while (d.dayOfWeek.value !in 1..5) d = d.plusDays(1)
        val openUtc = ZonedDateTime.of(d, LocalTime.MIDNIGHT, ZoneOffset.UTC)
        return openUtc.toInstant().toEpochMilli()
    }

    /** Sljedeće zatvaranje: iduća ponoć (00:00 UTC). */
    private fun nextCloseUtcMs(nowUtc: ZonedDateTime): Long {
        val nextMidnight = nowUtc.toLocalDate().plusDays(1)
        return ZonedDateTime.of(nextMidnight, LocalTime.MIDNIGHT, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    }

    /** Zadnje zatvaranje: ponoć nakon zadnjeg radnog dana (petak→subota 00:00). */
    private fun lastCloseUtcMs(nowUtc: ZonedDateTime): Long {
        var d = nowUtc.toLocalDate()
        while (d.dayOfWeek.value !in 1..5) d = d.minusDays(1)
        val closeDate = d.plusDays(1)
        return ZonedDateTime.of(closeDate, LocalTime.MIDNIGHT, ZoneOffset.UTC)
            .toInstant().toEpochMilli()
    }

    // ---------------- misc ----------------

    private fun humanTimeTo(deltaMs: Long): String {
        val d = max(0L, deltaMs)
        val days = d / DateUtils.DAY_IN_MILLIS
        val h = (d % DateUtils.DAY_IN_MILLIS) / DateUtils.HOUR_IN_MILLIS
        val m = (d % DateUtils.HOUR_IN_MILLIS) / DateUtils.MINUTE_IN_MILLIS
        return when {
            days > 0 -> "${days}d ${h}h"
            h > 0    -> "${h}h ${m}m"
            else     -> "${m}m"
        }
    }

    private fun scheduleTick(context: Context, action: String, whenMs: Long) {
        if (whenMs <= System.currentTimeMillis()) return
        val am: AlarmManager = context.getSystemService()!!
        val pi = PendingIntent.getBroadcast(
            context,
            if (action == ACTION_MARKET_OPEN_TICK) 3101 else 3102,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            Log.d(TAG, "Scheduled tick $action at ${Date(whenMs)}")
        }.onFailure {
            Log.w(TAG, "scheduleTick failed: ${it.message}")
        }
    }
}
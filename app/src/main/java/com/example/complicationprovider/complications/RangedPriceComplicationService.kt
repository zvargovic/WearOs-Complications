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
import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.*
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * EUR ranged komplikacija:
 *  - OPEN  → RangedValue: [dayMin .. dayMax], value = current EUR (+ OPEN ikona)
 *  - CLOSED→ RangedValue countdown C→O ili SHORT_TEXT (+ CLOSED ikona)
 *
 * Market kalendar: OTVORENO pon–pet (00:00–24:00) po UTC; vikend zatvoreno.
 */
class RangedPriceComplicationService : ComplicationDataSourceService() {

    private val TAG = "RangedPriceComp"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.RANGED_VALUE -> {
            // Preview: OPEN
            rangedOpen(
                eurNow = 3213.0,
                dayMin = 3204.0,
                dayMax = 3230.0
            )
        }
        ComplicationType.SHORT_TEXT -> {
            // Preview: CLOSED
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
        val repo = SettingsRepo(app)

        scope.launch {
            try {
                // 1) Snapshot + history (EUR only)
                val snap = repo.snapshotFlow.first()
                val eurNow = snap.eurConsensus
                val history = repo.historyFlow.first()

                // 2) Market timing (UTC)
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

                // 3) OPEN → day min/max (EUR) iz historyja
                val dayStartUtcMs = LocalDate.now(ZoneOffset.UTC)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()

                val (dayMin, dayMax) =
                    Indicators.dayMinMax(history, dayStartUtcMs) { it.eur }

                val minVal = dayMin ?: eurNow
                val maxVal = dayMax ?: (eurNow + 0.01)

                val nextCloseMs = nextCloseUtcMs(nowUtc)
                scheduleTick(app, ACTION_MARKET_CLOSE_TICK, nextCloseMs)

                val data = when (type) {
                    ComplicationType.RANGED_VALUE ->
                        rangedOpen(eurNow = eurNow, dayMin = minVal, dayMax = maxVal)
                    ComplicationType.SHORT_TEXT ->
                        shortOpen(eurNow = eurNow)
                    else -> null
                }
                listener.onComplicationData(data ?: NoDataComplicationData())
            } catch (t: Throwable) {
                Log.w(TAG, "onComplicationRequest error: ${t.message}", t)
                listener.onComplicationData(NoDataComplicationData())
            }
        }
    }

    // ---------------- builders ----------------

    private fun rangedOpen(
        eurNow: Double,
        dayMin: Double,
        dayMax: Double
    ): ComplicationData {
        val lo = min(dayMin, dayMax).toFloat()
        val hi = max(dayMin, dayMax).toFloat()
        val v = eurNow.toFloat().coerceIn(lo, hi)

        val text = PlainComplicationText.Builder(
            String.format(Locale.getDefault(), "€%.0f", eurNow)
        ).build()

        return RangedValueComplicationData.Builder(v, lo, hi, text)
            .setTitle(PlainComplicationText.Builder("min — + — max").build())
            .setMonochromaticImage(providerIconOpen())
            .build()
    }

    private fun shortOpen(eurNow: Double): ComplicationData {
        val text = PlainComplicationText.Builder(
            String.format(Locale.getDefault(), "€%.0f", eurNow)
        ).build()
        return ShortTextComplicationData.Builder(
            text,
            PlainComplicationText.Builder("open").build()
        )
            .setMonochromaticImage(providerIconOpen())
            .build()
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
        )
            .setMonochromaticImage(providerIconClosed())
            .build()
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
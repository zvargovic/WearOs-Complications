package com.example.complicationprovider

import android.content.ComponentName
import android.content.Context
import android.util.Log
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import com.example.complicationprovider.data.SettingsRepo

private const val TAG = "ComplicationSvc"

// preview i range za RANGED_VALUE
private const val PREVIEW_EUR = "€3149"
private const val RANGE_MIN = 0f
private const val RANGE_MAX = 5000f

class ComplicationProviderService : ComplicationDataSourceService() {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val marketPreview = "Market: Open"

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(PREVIEW_EUR).build(),
                    PlainComplicationText.Builder("XAU/EUR").build()
                ).build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder("$PREVIEW_EUR · $marketPreview").build(),
                    PlainComplicationText.Builder("XAU/EUR consensus and market status").build()
                ).build()

            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = 3149f,
                    min = RANGE_MIN,
                    max = RANGE_MAX,
                    contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                ).setText(PlainComplicationText.Builder(PREVIEW_EUR).build())
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE -> {
                val icon = Icon.createWithResource(this, android.R.drawable.presence_online)
                MonochromaticImageComplicationData.Builder(
                    MonochromaticImage.Builder(icon).build(),
                    PlainComplicationText.Builder(marketPreview).build()
                ).build()
            }

            ComplicationType.SMALL_IMAGE -> {
                val icon = Icon.createWithResource(this, android.R.drawable.presence_online)
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(icon, SmallImageType.ICON).build(),
                    PlainComplicationText.Builder(marketPreview).build()
                ).build()
            }

            ComplicationType.PHOTO_IMAGE -> NoDataComplicationData()

            else -> NoDataComplicationData()
        }
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        scope.launch {
            try {
                val repo = SettingsRepo(applicationContext)
                val snap = repo.snapshotFlow.first()   // zadnji snapshot

                val eur: Double = snap.eurConsensus ?: 0.0
                val eurText = if (eur > 0.0) formatEur(eur) else "—"

                val marketOpenNow = isMarketOpenUtc()
                val marketStatusText = if (marketOpenNow) "Market: Open" else "Market: Closed"

                val data: ComplicationData = when (request.complicationType) {
                    ComplicationType.SHORT_TEXT -> {
                        ShortTextComplicationData.Builder(
                            text = PlainComplicationText.Builder(eurText).build(),
                            contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                        ).build()
                    }

                    ComplicationType.LONG_TEXT -> {
                        LongTextComplicationData.Builder(
                            text = PlainComplicationText.Builder("$eurText · $marketStatusText").build(),
                            contentDescription = PlainComplicationText.Builder("XAU/EUR with market status").build()
                        ).build()
                    }

                    ComplicationType.RANGED_VALUE -> {
                        val v = eur.toFloat().coerceIn(RANGE_MIN, RANGE_MAX)
                        RangedValueComplicationData.Builder(
                            value = v,
                            min = RANGE_MIN,
                            max = RANGE_MAX,
                            contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                        ).setText(PlainComplicationText.Builder(eurText).build())
                            .build()
                    }

                    ComplicationType.MONOCHROMATIC_IMAGE -> {
                        val iconRes = if (marketOpenNow)
                            android.R.drawable.presence_online
                        else
                            android.R.drawable.presence_busy
                        val icon = Icon.createWithResource(this@ComplicationProviderService, iconRes)
                        MonochromaticImageComplicationData.Builder(
                            MonochromaticImage.Builder(icon).build(),
                            PlainComplicationText.Builder(marketStatusText).build()
                        ).build()
                    }

                    ComplicationType.SMALL_IMAGE -> {
                        val iconRes = if (marketOpenNow)
                            android.R.drawable.presence_online
                        else
                            android.R.drawable.presence_busy
                        val icon = Icon.createWithResource(this@ComplicationProviderService, iconRes)
                        SmallImageComplicationData.Builder(
                            SmallImage.Builder(icon, SmallImageType.ICON).build(),
                            PlainComplicationText.Builder(marketStatusText).build()
                        ).build()
                    }

                    ComplicationType.PHOTO_IMAGE -> NoDataComplicationData()

                    else -> NoDataComplicationData()
                }

                listener.onComplicationData(data)
            } catch (t: Throwable) {
                Log.e(TAG, "onComplicationRequest error: ${t.message}", t)
                listener.onComplicationData(NoDataComplicationData())
            }
        }
    }
}

/** Format: €3149.11 */
private fun formatEur(v: Double): String {
    val dfs = DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
    return "€" + DecimalFormat("0.##", dfs).format(v)
}

/** Jednostavna provjera: tržište zatvoreno vikendom (UTC). */
private fun isMarketOpenUtc(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): Boolean {
    return when (now.dayOfWeek) {
        DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> false
        else -> true
    }
}

/** Ručni trigger refresh-a komplikacija. */
fun requestUpdateAllComplications(ctx: Context) {
    try {
        ComplicationDataSourceUpdateRequester
            .create(ctx, ComponentName(ctx, ComplicationProviderService::class.java))
            .requestUpdateAll()
        Log.d(TAG, "Complications refresh requested.")
    } catch (t: Throwable) {
        Log.w(TAG, "requestUpdateAllComplications failed: ${t.message}")
    }
}
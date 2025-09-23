package com.example.complicationprovider

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.data.SettingsRepo
import com.example.complicationprovider.data.Snapshot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.filter
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private const val TAG = "ComplicationSvc"

private const val PREVIEW_EUR = "€3149"
private const val RANGE_MIN = 0f
private const val RANGE_MAX = 5000f

class ComplicationProviderService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(PREVIEW_EUR).build(),
                    PlainComplicationText.Builder("XAU/EUR").build()
                ).build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder("$PREVIEW_EUR · Demo").build(),
                    PlainComplicationText.Builder("XAU/EUR consensus").build()
                ).build()

            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = 3149f, min = RANGE_MIN, max = RANGE_MAX,
                    contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                ).setText(PlainComplicationText.Builder(PREVIEW_EUR).build())
                    .build()

            else -> NoDataComplicationData()
        }
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val repo = SettingsRepo(this)

        // 1) Pokušaj dohvatiti PRVI VALJAN snapshot (ima timestamp ili neku >0 vrijednost)
        val snap: Snapshot = runBlocking {
            val valid = withTimeoutOrNull(2000) {
                repo.snapshotFlow
                    .filter { it.updatedEpochMs > 0L || it.usdConsensus > 0.0 || it.eurConsensus > 0.0 }
                    .first()
            }
            // 2) Ako nije stiglo u 2s, uzmi što god trenutno postoji (može biti 0)
            valid ?: repo.snapshotFlow.first()
        }

        Log.d(
            TAG,
            "snapshot for complication -> USD=${snap.usdConsensus} EUR=${snap.eurConsensus} FX=${snap.eurUsdRate} ts=${snap.updatedEpochMs}"
        )

        fun fmt2(v: Double) = DecimalFormat(
            "0.00",
            DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        ).format(v)

        fun fmt4(v: Double) = DecimalFormat(
            "0.0000",
            DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        ).format(v)

        val usdTxt = if (snap.usdConsensus > 0.0) "USD ${fmt2(snap.usdConsensus)}" else "USD —"
        val eurTxt = if (snap.eurConsensus > 0.0) "EUR ${fmt2(snap.eurConsensus)}" else "EUR —"
        val fxTxt  = if (snap.eurUsdRate > 0.0) fmt4(snap.eurUsdRate) else "—"

        val oneLine = "$usdTxt  •  $eurTxt  •  FX $fxTxt"

        val data: ComplicationData = when (request.complicationType) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(oneLine).build(),
                    PlainComplicationText.Builder("Gold").build()
                ).build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder(oneLine).build(),
                    PlainComplicationText.Builder("Gold").build()
                ).build()

            ComplicationType.RANGED_VALUE -> {
                // Za ranged: koristimo samo EUR (ako postoji)
                val eur = (snap.eurConsensus.takeIf { it > 0.0 } ?: 0.0).toFloat()
                RangedValueComplicationData.Builder(
                    value = eur, min = RANGE_MIN, max = RANGE_MAX,
                    contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                ).setText(
                    PlainComplicationText.Builder(
                        if (eur > 0f) "€${fmt2(eur.toDouble())}" else "—"
                    ).build()
                ).build()
            }

            else -> NoDataComplicationData()
        }

        listener.onComplicationData(data)
    }
}

/** Ručni trigger refresh-a komplikacija (pozovi nakon snimanja snapshota). */

package com.example.complicationprovider

import android.content.ComponentName
import android.content.Context
import android.util.Log
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
import java.util.Locale
import com.example.complicationprovider.data.SettingsRepo

private const val TAG = "ComplicationSvc"

// za preview i fallback
private const val PREVIEW_EUR = "€3149"
private const val RANGE_MIN = 0f
private const val RANGE_MAX = 5000f

class ComplicationProviderService : ComplicationDataSourceService() {

    private val scope = CoroutineScope(Job() + Dispatchers.IO)

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder(PREVIEW_EUR).build(),
                    PlainComplicationText.Builder("XAU/EUR").build()
                ).build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(
                    PlainComplicationText.Builder("$PREVIEW_EUR · Open").build(),
                    PlainComplicationText.Builder("XAU/EUR consensus").build()
                ).build()

            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = 3149f,
                    min = RANGE_MIN,
                    max = RANGE_MAX,
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
        scope.launch {
            try {
                val repo = SettingsRepo(applicationContext)

                // Snapshot iz DataStore-a
                val snap = repo.snapshotFlow.first()

                val eur: Double = snap.eurConsensus ?: 0.0
                val eurText = if (eur > 0.0) formatEur(eur) else "—"

                val data: ComplicationData = when (request.complicationType) {
                    ComplicationType.SHORT_TEXT -> {
                        ShortTextComplicationData.Builder(
                            text = PlainComplicationText.Builder(eurText).build(),
                            contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
                        ).build()
                    }

                    ComplicationType.LONG_TEXT -> {
                        LongTextComplicationData.Builder(
                            text = PlainComplicationText.Builder("$eurText · XAU/EUR").build(),
                            contentDescription = PlainComplicationText.Builder("XAU/EUR").build()
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

/** Ručni trigger refresh-a komplikacija (možeš zvati iz mjesta gdje spremamo snapshot). */
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
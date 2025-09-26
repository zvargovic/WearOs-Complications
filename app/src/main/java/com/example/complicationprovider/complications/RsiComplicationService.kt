package com.example.complicationprovider.complications

import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.R
import com.example.complicationprovider.data.HistoryRec
import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private const val TAG = "RsiComp"

class RsiComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("62").build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        ).build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        Log.d(TAG, "onComplicationRequest type=${request.complicationType}")

        val repo = SettingsRepo(this)
        val history: List<HistoryRec> = runBlocking {
            withTimeoutOrNull(1200) { repo.historyFlow.first() } ?: emptyList()
        }.takeLast(50)

        val eurCloses = history.map { it.eur }.filter { it > 0.0 }
        val rsi = Indicators.rsi(eurCloses, 14)
        val text = if (rsi == null) "—" else rsi.roundToInt().toString()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        ).build()

        listener.onComplicationData(data)
    }
}
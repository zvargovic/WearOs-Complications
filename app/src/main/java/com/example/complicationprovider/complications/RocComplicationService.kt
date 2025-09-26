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
import kotlin.math.abs
import java.text.DecimalFormat

private const val TAG = "RocComp"

class RocComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("0.7").build(), // bez “%”
            PlainComplicationText.Builder(getString(R.string.comp_roc_name)).build()
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
        val roc1 = Indicators.roc(eurCloses, 1) // npr. -0.42
        val df = DecimalFormat("0.0")
        val text = if (roc1 == null) "—" else df.format(abs(roc1)) // bez znaka, bez “%”

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(getString(R.string.comp_roc_name)).build()
        ).build()

        listener.onComplicationData(data)
    }
}
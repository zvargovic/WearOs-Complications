package com.example.complicationprovider.complications
import kotlinx.coroutines.flow.first
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.R
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat

private const val TAG_RSI = "RsiComp"

class RsiComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_rsi)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("RSI 50").build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        ).setMonochromaticImage(img).build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val repo = SettingsRepo(this)
        val closes = runBlocking {
            withTimeoutOrNull(1500) { repo.historyFlow.first().map { it.eur }.filter { it > 0.0 } } ?: emptyList()
        }

        val rsi = computeRsi(closes, 14)
        val txt = if (rsi != null) "RSI ${DecimalFormat("0.#").format(rsi)}" else "RSI —"

        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_rsi)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(txt).build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        ).setMonochromaticImage(img).build()

        Log.d(TAG_RSI, "emit $txt")
        listener.onComplicationData(data)
    }

    private fun computeRsi(closes: List<Double>, period: Int): Double? {
        if (closes.size <= period) return null
        var gains = 0.0
        var losses = 0.0
        for (i in (closes.size - period) until closes.size) {
            val d = closes[i] - closes[i - 1]
            if (d >= 0) gains += d else losses -= d
        }
        if (gains + losses == 0.0) return 50.0
        val rs = (gains / period) / ((losses / period).coerceAtLeast(1e-9))
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
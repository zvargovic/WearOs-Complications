package com.example.complicationprovider.complications
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.R
import com.example.complicationprovider.data.HistoryRec
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import kotlin.math.max

private const val TAG_RSI = "RsiComp"

class RsiComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_rsi)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("RSI 48.5").build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        )
            .setMonochromaticImage(img)
            .build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val repo = SettingsRepo(this)

        // Uzmemo do 200 zadnjih točaka pa računamo RSI(14) po EUREN close-u
        val closes: List<Double> = runBlocking {
            withTimeoutOrNull(1500) {
                repo.historyFlow.first().map { it.eur }.filter { it > 0.0 }
            } ?: emptyList()
        }

        val rsi = computeRsi(closes, 14)
        val txt = if (rsi != null) "RSI ${DecimalFormat("0.##").format(rsi)}" else "RSI —"

        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_rsi)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(txt).build(),
            PlainComplicationText.Builder(getString(R.string.comp_rsi_name)).build()
        )
            .setMonochromaticImage(img)
            .build()

        Log.d(TAG_RSI, "emit $txt")
        listener.onComplicationData(data)
    }

    private fun computeRsi(closes: List<Double>, period: Int): Double? {
        if (closes.size <= period) return null
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val diff = closes[i] - closes[i - 1]
            if (diff >= 0) gains += diff else losses += -diff
        }
        var avgGain = gains / period
        var avgLoss = losses / period

        for (i in period + 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            val g = max(diff, 0.0)
            val l = max(-diff, 0.0)
            avgGain = (avgGain * (period - 1) + g) / period
            avgLoss = (avgLoss * (period - 1) + l) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }
}
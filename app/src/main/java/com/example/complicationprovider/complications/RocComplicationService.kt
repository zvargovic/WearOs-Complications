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
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import kotlin.math.abs

private const val TAG_ROC = "RocComp"

class RocComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_roc)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("ROC -0.12%").build(),
            PlainComplicationText.Builder(getString(R.string.comp_roc_name)).build()
        )
            .setMonochromaticImage(img)
            .build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val repo = SettingsRepo(this)
        val closes: List<Double> = runBlocking {
            withTimeoutOrNull(1500) {
                repo.historyFlow.first().map { it.eur }.filter { it > 0.0 }
            } ?: emptyList()
        }

        val roc = computeRocPercent(closes, 1)
        val txt = if (roc != null) "ROC ${DecimalFormat("0.##").format(roc)}%" else "ROC —"

        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_roc)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(txt).build(),
            PlainComplicationText.Builder(getString(R.string.comp_roc_name)).build()
        )
            .setMonochromaticImage(img)
            .build()

        Log.d(TAG_ROC, "emit $txt")
        listener.onComplicationData(data)
    }

    private fun computeRocPercent(closes: List<Double>, period: Int): Double? {
        if (closes.size <= period) return null
        val last = closes.last()
        val ref = closes[closes.size - 1 - period]
        if (ref == 0.0) return null
        return (last - ref) / ref * 100.0
    }
}
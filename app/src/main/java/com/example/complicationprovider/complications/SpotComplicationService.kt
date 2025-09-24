package com.example.complicationprovider.complications
import kotlinx.coroutines.flow.first
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.R
import com.example.complicationprovider.data.SettingsRepo
import com.example.complicationprovider.data.Snapshot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private const val TAG_SPOT = "SpotComp"

class SpotComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_spot)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("€3149.00").build(),
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        ).setMonochromaticImage(img).build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        Log.d(TAG_SPOT, "onComplicationRequest type=${request.complicationType} at ${System.currentTimeMillis()}")

        val repo = SettingsRepo(this)
        val snap: Snapshot = runBlocking {
            withTimeoutOrNull(1500) {
                repo.snapshotFlow.first { it.updatedEpochMs > 0L || it.eurConsensus > 0.0 }
            } ?: repo.snapshotFlow.value
        }

        fun fmt2(v: Double) = DecimalFormat(
            "0.00", DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        ).format(v)

        val text = if (snap.eurConsensus > 0.0) "€${fmt2(snap.eurConsensus)}" else "—"

        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_spot)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        ).setMonochromaticImage(img).build()

        Log.d(TAG_SPOT, "emit $text")
        listener.onComplicationData(data)
    }
}
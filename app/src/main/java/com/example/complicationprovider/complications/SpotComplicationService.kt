package com.example.complicationprovider.complications

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
import com.example.complicationprovider.data.Snapshot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
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
        )
            .setMonochromaticImage(img)
            .build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val t0 = System.currentTimeMillis() // DODANO
        Log.d(TAG_SPOT, "onComplicationRequest type=${request.complicationType} at $t0") // DODANO

        val repo = SettingsRepo(this)

        val snap: Snapshot = runBlocking {
            withTimeoutOrNull(1500) {
                repo.snapshotFlow
                    .filter { it.updatedEpochMs > 0L || it.eurConsensus > 0.0 } // DODANO
                    .first()
            } ?: repo.snapshotFlow.value
        }

        // DODANO: detalji učitanog snapshota
        Log.d(
            TAG_SPOT,
            "snapshot loaded ts=${snap.updatedEpochMs} usd=${snap.usdConsensus} eur=${snap.eurConsensus} fx=${snap.eurUsdRate}"
        )

        fun fmt2(v: Double) = DecimalFormat(
            "0.00",
            DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        ).format(v)

        val text = if (snap.eurConsensus > 0.0) "€${fmt2(snap.eurConsensus)}" else "—"

        // DODANO: što ćemo prikazati
        Log.d(TAG_SPOT, "render text='$text' (elapsed=${System.currentTimeMillis() - t0}ms)")

        val img = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_gold_spot)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        )
            .setMonochromaticImage(img)
            .build()

        Log.d(TAG_SPOT, "emit $text") // postojeći log
        listener.onComplicationData(data)
        Log.d(TAG_SPOT, "listener.onComplicationData() done (total=${System.currentTimeMillis() - t0}ms)") // DODANO
    }
}
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
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.max

private const val TAG_SPOT = "SpotComp"

// Pragovi starosti (minute) za boju/ikonicu
private const val FRESH_MIN = 2L
private const val WARM_MIN  = 6L

class SpotComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Pretpostavi “svježe” u previewu
        val dot = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_dot_fresh)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("3149.00").build(), // samo broj
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        ).setMonochromaticImage(dot).build()
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
            "0.00",
            DecimalFormatSymbols(Locale.US).apply { decimalSeparator = '.' }
        ).format(v)

        val text = if (snap.eurConsensus > 0.0) fmt2(snap.eurConsensus) else "—"

        // Starost podatka
        val ageMs  = max(0L, System.currentTimeMillis() - snap.updatedEpochMs)
        val ageMin = ageMs / 60_000L

        val dotRes = when {
            ageMin < FRESH_MIN -> R.drawable.ic_dot_fresh  // ≤ 2 min
            ageMin < WARM_MIN  -> R.drawable.ic_dot_warm   // 2-6 min
            else               -> R.drawable.ic_dot_stale  // > 6 min
        }

        val dot = MonochromaticImage.Builder(
            Icon.createWithResource(this, dotRes)
        ).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(text).build(),
            PlainComplicationText.Builder(getString(R.string.comp_spot_name)).build()
        ).setMonochromaticImage(dot).build()

        Log.d(
            TAG_SPOT,
            "emit text='$text'  ageMin=$ageMin  dot=${resources.getResourceEntryName(dotRes)}"
        )
        listener.onComplicationData(data)
    }
}
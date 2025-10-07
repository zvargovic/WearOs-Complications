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
import java.util.Locale
import kotlin.math.max
import kotlin.math.round

private const val TAG_SPOT = "SpotComp"

// Pragovi starosti (minute) za boju/ikonicu
private const val FRESH_MIN = 5L
private const val WARM_MIN  = 15L

class SpotComplicationService : ComplicationDataSourceService() {

    /** Kompaktni prikaz za komplikaciju: 3382.88 → "3383" */
    private fun fmtCompact(value: Double): String {
        val rounded = round(value).toInt()
        return String.format(Locale.US, "%d", rounded)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        // Pretpostavi “svježe” u previewu
        val dot = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_dot_fresh)
        ).build()

        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder("3149").build(), // čisti preview
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

        // SAMO za komplikaciju koristimo zaokruženi integer
        val text = if (snap.eurConsensus > 0.0) fmtCompact(snap.eurConsensus) else "—"

        // Starost podatka
        val ageMs  = max(0L, System.currentTimeMillis() - snap.updatedEpochMs)
        val ageMin = ageMs / 60_000L

        val dotRes = when {
            ageMin < FRESH_MIN -> R.drawable.ic_dot_fresh  // ≤ 5 min
            ageMin < WARM_MIN  -> R.drawable.ic_dot_warm   // 5–15 min
            else               -> R.drawable.ic_dot_stale  // > 15 min
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
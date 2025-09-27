package com.example.complicationprovider.complications

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import com.example.complicationprovider.R
import com.example.complicationprovider.data.HistoryRec
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "SparkComp"

class SparklineComplicationService : ComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val bmp = drawSpark(emptyList())
        val img = MonochromaticImage.Builder(Icon.createWithBitmap(bmp)).build()
        return ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(getString(R.string.comp_spark_name)).build(),
            PlainComplicationText.Builder(getString(R.string.comp_spark_name)).build()
        ).setMonochromaticImage(img).build()
    }

    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        Log.d(TAG, "onComplicationRequest type=${request.complicationType}")

        val repo = SettingsRepo(this)
        val history: List<HistoryRec> = runBlocking {
            withTimeoutOrNull(1200) { repo.historyFlow.first() } ?: emptyList()
        }.takeLast(40)

        val bmp = drawSpark(history)
        val img = MonochromaticImage.Builder(Icon.createWithBitmap(bmp)).build()

        val data = ShortTextComplicationData.Builder(
            PlainComplicationText.Builder(getString(R.string.comp_spark_name)).build(),
            PlainComplicationText.Builder(getString(R.string.comp_spark_name)).build()
        ).setMonochromaticImage(img).build()

        listener.onComplicationData(data)
    }

    private fun drawSpark(hist: List<HistoryRec>): Bitmap {
        val w = 64
        val h = 64
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (hist.size < 2) return bmp

        val vals = hist.map { it.eur }.filter { it > 0.0 }
        if (vals.size < 2) return bmp

        val min = vals.minOrNull()!!
        val max = vals.maxOrNull()!!
        val span = (max - min).coerceAtLeast(1e-6)

        val c = Canvas(bmp)
        val pad = 6f
        val area = RectF(pad, pad, w - pad, h - pad)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        var prevX = area.left
        var prevY = area.bottom - ((vals.first() - min).toFloat() / span.toFloat()) * area.height()
        val stepX = area.width() / (vals.size - 1).coerceAtLeast(1)

        for (i in 1 until vals.size) {
            val x = area.left + i * stepX
            val y = area.bottom - ((vals[i] - min).toFloat() / span.toFloat()) * area.height()
            c.drawLine(prevX, prevY, x, y, p)
            prevX = x; prevY = y
        }
        return bmp
    }
}
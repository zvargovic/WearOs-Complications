package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.data.SnapshotStore
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

object TilePreRender {

    private const val TAG = "TilePreRender"

    fun run(appContext: Context) {
        try {
            val cfg = ChartRenderer.Config(
                widthPx = ChartDims.W_PX,
                heightPx = ChartDims.H_PX,
                showPlotBounds = true,   // vidi okvir
                showRsi = true,          // prikaži RSI
                rsiTextPx = 14f,
                rsiOffsetBelowAreaPx = 16f
            )

            val d = runBlocking { SnapshotStore.get(appContext, cfg.slotsPerDay) }

            // zadnja cijena
            val last = d.series.lastOrNull { it != null } ?: d.open

            // pripremi close listu za RSI (samo nenull)
            val closes = d.series.filterNotNull()
            val rsiVal = Indicators.rsi(closes, 14)   // može biti null dok nema dovoljno točaka

            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = last,
                rsi = rsiVal
            )

            val png = ChartRenderer.renderPNG(appContext, cfg, series) { v ->
                if (abs(v) >= 1000) String.format("%,.2f €", v) else String.format("%.2f €", v)
            }
            TilePngCache.bytes = png
            TilePngCache.version = System.currentTimeMillis().toString()

            Log.d(TAG, "preRender ok → png=${png.size}B, ver=${TilePngCache.version}")

            TileService.getUpdater(appContext).requestUpdate(SparklineTileService::class.java)

        } catch (t: Throwable) {
            Log.e(TAG, "preRender failed", t)
        }
    }
}
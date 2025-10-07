package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.example.complicationprovider.data.SnapshotStore
import com.example.complicationprovider.data.Indicators
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

object TilePreRender {

    private const val TAG = "TilePreRender"

    fun run(appContext: Context) {
        try {
            // 1) dimenzije grafa (centralizirane u ChartDims)
            val cfg = ChartRenderer.Config(
                widthPx = ChartDims.W_PX,
                heightPx = ChartDims.H_PX,
                slotsPerDay = 288,        // 5-min slotovi
                showPlotBounds = false,    // marker kvadrat ostaje uključen
                showRsi = true,
                // prikaži RSI
            )

            // 2) učitaj seriju iz SnapshotStore-a (blokirajuće, kratko)
            val d = runBlocking { SnapshotStore.get(appContext, cfg.slotsPerDay) }

            val last = d.series.lastOrNull { it != null } ?: d.open

            // RSI ulaz: sve nenull vrijednosti (cijene) iz trenutne serije
            val close = d.series.filterNotNull()
            val rsi = Indicators.rsi(close, cfg.rsiPeriod)

            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = last,
                rsi = rsi
            )

            // 3) render u PNG i spremi u memorijski cache
            val png = ChartRenderer.renderPNG(appContext, cfg, series) { v ->
                if (abs(v) >= 1000) String.format("%,.2f €", v) else String.format("%.2f €", v)
            }
            TilePngCache.bytes = png
            TilePngCache.version = System.currentTimeMillis().toString()

            Log.d(TAG, "preRender ok → png=${png.size}B, ver=${TilePngCache.version}, rsi=$rsi")

            // 4) pingaj tile da preuzme novi resource
            TileService.getUpdater(appContext)
                .requestUpdate(SparklineTileService::class.java)

        } catch (t: Throwable) {
            Log.e(TAG, "preRender failed", t)
        }
    }
}
package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.example.complicationprovider.R
import com.example.complicationprovider.data.SnapshotStore
import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.util.MarketSession
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

object TilePreRender {

    private const val TAG = "TilePreRender"

    fun run(appContext: Context) {
        try {
            // 1) Dimenzije i market postavke
            val cfg = ChartRenderer.Config(
                widthPx = ChartDims.W_PX,
                heightPx = ChartDims.H_PX,
                slotsPerDay = 288,
                showPlotBounds = false,
                showRsi = true,
                openHour = 8,
                closeHour = 22
            )

            // 2) Učitaj snapshot (serija + global last)
            val d = runBlocking { SnapshotStore.get(appContext, cfg.slotsPerDay) }

            // zadnja vrijednost iz serije → fallback na open
            val lastInSeries = d.series.lastOrNull { it != null } ?: d.open
            // “trenutni” last: preferiraj global-last pa seriju
            val lastNow = d.gLast ?: lastInSeries

            // 3) RSI iz trenutne serije
            val close = d.series.filterNotNull()
            val rsi = Indicators.rsi(close, cfg.rsiPeriod)

            // 4) Market status
            val isOpen = MarketSession.isOpenNow(appContext)

            // 5) Serija za renderer (lastPrice = lastNow!)
            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = lastNow,
                rsi = rsi,
                isMarketOpen = isOpen
            )

            Log.d(
                TAG,
                "preRender data → open=${d.open} lastInSeries=$lastInSeries lastNow=${d.gLast} " +
                        "min=${d.min} max=${d.max} isOpen=$isOpen"
            )

            // 6) Ako je zatvoreno → kartica s ETA; inače render grafa
            val png: ByteArray = if (!isOpen) {
                ChartRenderer.renderClosedPNG(
                    context = appContext,
                    cfg = cfg,
                    iconResId = R.drawable.ic_graph_closed,
                    statusTextOverride = MarketSession.closedEtaText(appContext)
                )
            } else {
                ChartRenderer.renderPNG(
                    context = appContext,
                    cfg = cfg,
                    series = series
                ) { v -> if (abs(v) >= 1000) String.format("%,.2f €", v) else String.format("%.2f €", v) }
            }

            // 7) Cache + ping tile
            TilePngCache.bytes = png
            TilePngCache.version = System.currentTimeMillis().toString()
            Log.d(TAG, "preRender ok → png=${png.size}B, ver=${TilePngCache.version}, rsi=$rsi, isOpen=$isOpen")
            TileService.getUpdater(appContext).requestUpdate(SparklineTileService::class.java)

        } catch (t: Throwable) {
            Log.e(TAG, "preRender failed", t)
        }
    }
}
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
            // 1) dimenzije i market postavke (NE DIRAMO TVOJE VRIJEDNOSTI)
            val cfg = ChartRenderer.Config(
                widthPx = ChartDims.W_PX,
                heightPx = ChartDims.H_PX,
                slotsPerDay = 288,
                showPlotBounds = false,
                showRsi = true,
                openHour = 8,
                closeHour = 22
            )

            // 2) Učitaj seriju iz SnapshotStore-a (blokirajuće)
            val d = runBlocking { SnapshotStore.get(appContext, cfg.slotsPerDay) }
            val last = d.series.lastOrNull { it != null } ?: d.open

            // 3) Izračun RSI (kao i prije)
            val close = d.series.filterNotNull()
            val rsi = Indicators.rsi(close, cfg.rsiPeriod)

            // 4) Centralizirani market status preko MarketSession
            val isOpen = MarketSession.isOpenNow(appContext)

            // 5) Složi opis serije (rendereru prosljeđujemo i isOpen da zna status)
            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = last,
                rsi = rsi,
                isMarketOpen = isOpen
            )

            // 6) ⬅️ GRANANJE: closed → IKONA s centraliziranim ETA tekstom; open → GRAF
            val png: ByteArray = if (!isOpen) {
                ChartRenderer.renderClosedPNG(
                    context = appContext,
                    cfg = cfg,
                    iconResId = R.drawable.ic_graph_closed,
                    statusTextOverride = MarketSession.closedEtaText(appContext) // centralizirani tekst
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
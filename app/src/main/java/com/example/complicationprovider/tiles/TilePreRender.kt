package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.tiles.TileService
import com.example.complicationprovider.R
import com.example.complicationprovider.data.SnapshotStore
import com.example.complicationprovider.data.Indicators
import kotlinx.coroutines.runBlocking
import java.time.DayOfWeek
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.abs

object TilePreRender {

    private const val TAG = "TilePreRender"

    fun run(appContext: Context) {
        try {
            // 1) dimenzije i market postavke
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
            val rsi = com.example.complicationprovider.data.Indicators.rsi(close, cfg.rsiPeriod)

            // 4) Lokalna detekcija otvorenosti tržišta — ISTA logika kao u rendereru
            val zone = runCatching { java.time.ZoneId.of(cfg.marketTzId) }
                .getOrElse { java.time.ZoneId.systemDefault() }
            val now = java.time.ZonedDateTime.now(zone)
            fun isWeekday(d: java.time.LocalDate) = d.dayOfWeek.value in 1..5
            fun sessionStart(d: java.time.LocalDate) =
                java.time.ZonedDateTime.of(d, java.time.LocalTime.of(cfg.openHour, cfg.openMinute), zone)
            fun sessionEnd(d: java.time.LocalDate) =
                java.time.ZonedDateTime.of(d.plusDays(1), java.time.LocalTime.of(cfg.closeHour, cfg.closeMinute), zone)

            val today = now.toLocalDate()
            val yday = today.minusDays(1)
            val isOpen =
                (isWeekday(yday) && !now.isBefore(sessionStart(yday)) && now.isBefore(sessionEnd(yday))) ||
                        (isWeekday(today) && !now.isBefore(sessionStart(today)) && now.isBefore(sessionEnd(today)))

            // 5) Složi opis serije (prosljeđujemo i isOpen da renderer zna status)
            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = last,
                rsi = rsi,
                isMarketOpen = isOpen
            )

            // 6) ⬅️ KONAČNO GRANANJE: closed → crtaj IKONU; open → crtaj GRAF
            val png: ByteArray = if (!isOpen) {
                ChartRenderer.renderClosedPNG(
                    context = appContext,
                    cfg = cfg,
                    iconResId = R.drawable.ic_graph_closed   // <-- tvoja vektorska/PNG ikona
                )
            } else {
                ChartRenderer.renderPNG(
                    context = appContext,
                    cfg = cfg,
                    series = series
                ) { v -> if (kotlin.math.abs(v) >= 1000) String.format("%,.2f €", v) else String.format("%.2f €", v) }
            }

            // 7) Cache + ping tile
            TilePngCache.bytes = png
            TilePngCache.version = System.currentTimeMillis().toString()
            Log.d("TilePreRender", "preRender ok → png=${png.size}B, ver=${TilePngCache.version}, rsi=$rsi, isOpen=$isOpen")
            androidx.wear.tiles.TileService.getUpdater(appContext)
                .requestUpdate(SparklineTileService::class.java)

        } catch (t: Throwable) {
            Log.e("TilePreRender", "preRender failed", t)
        }
    }

    private fun isMarketOpenUtc(now: ZonedDateTime = ZonedDateTime.now(ZoneOffset.UTC)): Boolean =
        when (now.dayOfWeek) {
            DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> false
            else -> true
        }
}
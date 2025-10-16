package com.example.complicationprovider.tiles

import android.content.Context
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.wear.tiles.ResourceBuilders as TileResBuilders

import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL

import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * SpotTileService – prikazuje XAU/EUR spot, dnevni min/max i RSI(14).
 */
class SpotTileService : TileService() {

    private fun c(argb: Int) = ColorBuilders.argb(argb)
    private fun dp(v: Float) = DimensionBuilders.dp(v)
    private fun sp(v: Float) = DimensionBuilders.SpProp.Builder().setValue(v).build()

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        android.util.Log.d("SpotTile", "onTileRequest() start")
        val repo = SettingsRepo(applicationContext)

        val eurNow: Double
        val dayMin: Double?
        val dayMax: Double?
        val rsiVal: Double

        runBlocking {
            val snap = repo.snapshotFlow.first()

            // 1) SnapshotStore (lokalni cache + global last)
            val store = com.example.complicationprovider.data.SnapshotStore.get(applicationContext)

            // Fallback lanci: preferiraj gLast, pa zadnju točku iz serije, pa open
            val lastInSeries: Double = store.series.lastOrNull { it != null } ?: store.open
            val lastNow: Double = store.gLast ?: lastInSeries

            // eurNow: prvo consensus sa mreže; ako ga nema → lastNow iz store-a
            eurNow = if (snap.eurConsensus > 0.0) snap.eurConsensus else lastNow

            // 2) Min/Max za današnji dan (UTC). Ako history prazan, uzmi iz store-a.
            val history = repo.historyFlow.first()
            val dayStartUtcMs = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

            val (mn, mx) = com.example.complicationprovider.data.Indicators
                .dayMinMax(history, dayStartUtcMs) { it.eur }
            dayMin = mn ?: store.min
            dayMax = mx ?: store.max

            // 3) RSI(14) na dostupnoj povijesti (ako prazno → 50.0)
            val series = if (history.isNotEmpty()) history.map { it.eur } else emptyList()
            rsiVal = com.example.complicationprovider.data.Indicators.rsi(series, 14) ?: 50.0

            // Debug pomoćni logovi
            android.util.Log.d(
                "SpotTile",
                "fallbacks → lastInSeries=%.2f lastNow=%.2f eurConsensus=%.2f"
                    .format(lastInSeries, lastNow, snap.eurConsensus)
            )
        }

        android.util.Log.d(
            "SpotTile",
            "onTileRequest data → eurNow=%.2f min=%.2f max=%.2f rsi=%.1f".format(
                eurNow, (dayMin ?: 0.0), (dayMax ?: 0.0), rsiVal
            )
        )

        val layout = buildLayout(
            spotTxt = "€" + "%,.2f".format(Locale.US, eurNow),
            minTxt  = dayMin?.let { "€" + "%,.2f".format(Locale.US, it) } ?: "—",
            maxTxt  = dayMax?.let { "€" + "%,.2f".format(Locale.US, it) } ?: "—",
            rsiTxt  = "RSI " + String.format(Locale.US, "%.1f", rsiVal)
        )

        val timeline = ProtoTL.Timeline.Builder()
            .addTimelineEntry(ProtoTL.TimelineEntry.Builder().setLayout(layout).build())
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()

        android.util.Log.d("SpotTile", "onTileRequest END → returning Tile")
        return Futures.immediateFuture(tile)
    }

    private fun buildLayout(
        spotTxt: String,
        minTxt: String,
        maxTxt: String,
        rsiTxt: String
    ): LayoutElementBuilders.Layout {
        val cardBg = c(0x1FFFFFFF.toInt())
        val titleCol = c(0xFFE0E0E0.toInt())
        val priceCol = c(0xFFFFFFFF.toInt())
        val minCol = c(0xFFF05454.toInt())
        val maxCol = c(0xFF38D66B.toInt())
        val hintCol = c(0xFFB0B0B0.toInt())

        val title = text("Spot (XAU/EUR)", 13f, titleCol, true)
        val price = text(spotTxt, 28f, priceCol, true)
        val minT = text("▼ $minTxt", 14f, minCol, false)
        val maxT = text("▲ $maxTxt", 14f, maxCol, false)
        val rsi = text(rsiTxt, 14f, hintCol, false)

        val rowMinMax = LayoutElementBuilders.Row.Builder()
            .addContent(minT)
            .addContent(spacer(12f))
            .addContent(maxT)
            .build()

        val col = LayoutElementBuilders.Column.Builder()
            .addContent(title)
            .addContent(spacer(6f))
            .addContent(price)
            .addContent(spacer(6f))
            .addContent(rowMinMax)
            .addContent(spacer(6f))
            .addContent(rsi)
            .build()

        val card = cardBox(cardBg, col)

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(card)
            .build()
    }

    private fun text(
        s: String,
        sizeSp: Float,
        color: ColorBuilders.ColorProp,
        bold: Boolean
    ): LayoutElementBuilders.Text {
        val weight = LayoutElementBuilders.FontWeightProp.Builder()
            .setValue(
                if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                else LayoutElementBuilders.FONT_WEIGHT_NORMAL
            )
            .build()
        val style = LayoutElementBuilders.FontStyle.Builder()
            .setSize(sp(sizeSp))
            .setWeight(weight)
            .setColor(color)
            .build()
        return LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(style)
            .build()
    }

    private fun cardBox(
        bg: ColorBuilders.ColorProp,
        content: LayoutElementBuilders.LayoutElement
    ): LayoutElementBuilders.Box {
        return LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(bg)
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(22f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(14f)).build())
                    .build()
            )
            .addContent(content)
            .build()
    }

    private fun spacer(hDp: Float) =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(hDp)).build()

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(SpotTileService::class.java)
        }
    }
}
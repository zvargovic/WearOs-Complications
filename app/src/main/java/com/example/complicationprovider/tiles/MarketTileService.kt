package com.example.complicationprovider.tiles

import android.content.Context
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import androidx.wear.tiles.ResourceBuilders as TileResBuilders

// ProtoLayout (1.3.0)
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL

import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class MarketTileService : TileService() {

    private val RADIUS_DP    = 22f
    private val PADDING_DP   = 14f
    private val GAP_V_DP     = 8f
    private val GAP_INNER_DP = 6f

    private fun c(argb: Int) = ColorBuilders.argb(argb)
    private fun dp(v: Float) = DimensionBuilders.dp(v)
    private fun sp(v: Float) = DimensionBuilders.SpProp.Builder().setValue(v).build()

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val repo = SettingsRepo(applicationContext)

        val eurNow: Double
        val dayMin: Double?
        val dayMax: Double?
        val rsiVal: Double

        // kratko, sigurno blokiranje – isto kao prije
        runBlocking {
            val snap = repo.snapshotFlow.first()
            eurNow = snap.eurConsensus

            val history = repo.historyFlow.first()
            val dayStartUtcMs = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val (mn, mx) = Indicators.dayMinMax(history, dayStartUtcMs) { it.eur }
            dayMin = mn
            dayMax = mx

            val series = history.map { it.eur }
            rsiVal = Indicators.rsi(series, 14) ?: 50.0
        }

        val layout = buildTileLayout(
            spotTxt = "€" + "%,.2f".format(Locale.US, eurNow),
            minTxt  = dayMin?.let { "€" + "%,.2f".format(Locale.US, it) } ?: "—",
            maxTxt  = dayMax?.let { "€" + "%,.2f".format(Locale.US, it) } ?: "—",
            rsiTxt  = "RSI: " + String.format(Locale.US, "%.1f", rsiVal)
        )

        val timeline = ProtoTL.Timeline.Builder()
            .addTimelineEntry(
                ProtoTL.TimelineEntry.Builder()
                    .setLayout(layout)
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("2")                 // bump da srušimo keš
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<TileResBuilders.Resources> {
        val res = TileResBuilders.Resources.Builder()
            .setVersion("2") // upareno s gore
            .build()
        return Futures.immediateFuture(res)
    }

    private fun buildTileLayout(
        spotTxt: String,
        minTxt: String,
        maxTxt: String,
        rsiTxt: String
    ): LayoutElementBuilders.Layout {
        val cardBg   = c(0x1FFFFFFF.toInt())
        val titleCol = c(0xFFE0E0E0.toInt())
        val priceCol = c(0xFFFFFFFF.toInt())
        val minCol   = c(0xFFF05454.toInt())
        val maxCol   = c(0xFF38D66B.toInt())
        val hintCol  = c(0xFFB0B0B0.toInt())

        // Kartica 1 — Spot + Min/Max
        val card1 = cardBox(
            cardBg,
            LayoutElementBuilders.Column.Builder()
                .addContent(text("Spot (XAU/EUR)", 13f, titleCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(text(spotTxt, 26f, priceCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(
                    LayoutElementBuilders.Row.Builder()
                        .addContent(text("▼ $minTxt", 14f, minCol, false))
                        .addContent(spacer(12f))
                        .addContent(text("▲ $maxTxt", 14f, maxCol, false))
                        .build()
                )
                .build()
        )

        // Kartica 2 — placeholder
        val card2 = cardBox(
            cardBg,
            LayoutElementBuilders.Column.Builder()
                .addContent(text("Dnevni graf", 13f, titleCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(text("sparkline (uskoro)", 12f, hintCol, false))
                .build()
        )

        // Kartica 3 — RSI
        val card3 = cardBox(
            cardBg,
            LayoutElementBuilders.Column.Builder()
                .addContent(text("RSI (14)", 13f, titleCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(text(rsiTxt, 16f, priceCol, false))
                .build()
        )

        val rootCol = LayoutElementBuilders.Column.Builder()
            .addContent(card1)
            .addContent(spacer(GAP_V_DP))
            .addContent(card2)
            .addContent(spacer(GAP_V_DP))
            .addContent(card3)
            .build()

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(rootCol)
            .build()
    }

    private fun text(
        s: String,
        sizeSp: Float,
        color: ColorBuilders.ColorProp,
        bold: Boolean
    ): LayoutElementBuilders.Text {
        val weight = LayoutElementBuilders.FontWeightProp.Builder()
            .setValue(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
            else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
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
                                    .setRadius(dp(RADIUS_DP))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(dp(PADDING_DP))
                            .build()
                    )
                    .build()
            )
            .addContent(content)
            .build()
    }

    private fun spacer(hDp: Float) =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(hDp)).build()

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(MarketTileService::class.java)
        }
    }
}
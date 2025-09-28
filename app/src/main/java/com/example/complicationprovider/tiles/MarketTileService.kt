package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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

    private val TAG = "MarketTile"

    private val RADIUS_DP    = 22f
    private val PADDING_DP   = 14f
    private val GAP_V_DP     = 8f
    private val GAP_INNER_DP = 6f

    private fun c(argb: Int) = ColorBuilders.argb(argb)
    private fun dp(v: Float) = DimensionBuilders.dp(v)
    private fun sp(v: Float) = DimensionBuilders.SpProp.Builder().setValue(v).build()
    private fun expand() = DimensionBuilders.ExpandedDimensionProp.Builder().build()

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        Log.d(TAG, "onTileRequest()")
        val tile = runCatching {
            val repo = SettingsRepo(applicationContext)

            var eurNow = 0.0
            var dayMin: Double? = null
            var dayMax: Double? = null
            var rsiVal = 50.0

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

            TileBuilders.Tile.Builder()
                .setResourcesVersion("4")
                .setTileTimeline(timeline)
                .setFreshnessIntervalMillis(60_000)
                .build()
        }.getOrElse { e ->
            Log.w(TAG, "onTileRequest failed: ${e.message}", e)
            val timeline = ProtoTL.Timeline.Builder()
                .addTimelineEntry(
                    ProtoTL.TimelineEntry.Builder()
                        .setLayout(fallbackLayout())
                        .build()
                )
                .build()
            TileBuilders.Tile.Builder()
                .setResourcesVersion("4")
                .setTileTimeline(timeline)
                .setFreshnessIntervalMillis(60_000)
                .build()
        }

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<TileResBuilders.Resources> {
        val res = TileResBuilders.Resources.Builder()
            .setVersion("4")
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

        val card2 = cardBox(
            cardBg,
            LayoutElementBuilders.Column.Builder()
                .addContent(text("Dnevni graf", 13f, titleCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(text("sparkline (uskoro)", 12f, hintCol, false))
                .build()
        )

        val card3 = cardBox(
            cardBg,
            LayoutElementBuilders.Column.Builder()
                .addContent(text("RSI (14)", 13f, titleCol, true))
                .addContent(spacer(GAP_INNER_DP))
                .addContent(text(rsiTxt, 16f, priceCol, false))
                .build()
        )

        val rootCol = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())              // širina = full bleed
            // visinu ne postavljamo → wrap (kompatibilno)
            .addContent(card1)
            .addContent(spacer(GAP_V_DP))
            .addContent(card2)
            .addContent(spacer(GAP_V_DP))
            .addContent(card3)
            .build()

        val rootBox = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            // visinu ne postavljamo → wrap
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(dp(4f))
                            .build()
                    )
                    .build()
            )
            .addContent(rootCol)
            .build()

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(rootBox)
            .build()
    }

    private fun fallbackLayout(): LayoutElementBuilders.Layout {
        val box = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(c(0xFF202020.toInt()))
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(dp(16f))
                            .build()
                    )
                    .build()
            )
            .addContent(text("Gold tile", 16f, c(0xFFFFFFFF.toInt()), true))
            .addContent(spacer(6f))
            .addContent(text("Nema podataka", 14f, c(0xFFB0B0B0.toInt()), false))
            .build()

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(box)
            .build()
    }

    // --- helpers ---

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
            .setWidth(expand())
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
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(dp(hDp))
            .build()

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(MarketTileService::class.java)
        }
    }
}
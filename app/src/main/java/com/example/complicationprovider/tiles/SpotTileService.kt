package com.example.complicationprovider.tiles

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.ResourceBuilders as TileResBuilders
import com.example.complicationprovider.data.Indicators
import com.example.complicationprovider.data.SettingsRepo
import com.example.complicationprovider.data.SnapshotStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
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
        Log.e("SpotTile", ">>> onTileRequest START")

        val repo = SettingsRepo(applicationContext)

        val eurNow: Double
        val dayMin: Double?
        val dayMax: Double?
        val rsiVal: Double

        runBlocking {
            val snap = repo.snapshotFlow.first()
            val store = SnapshotStore.get(applicationContext)

            // --- 1) Cijena sada: consensus → gLast → lastInSeries → open
            val lastInSeries: Double = store.series.lastOrNull { it != null } ?: store.open
            val lastNow: Double = store.gLast ?: lastInSeries
            eurNow = if (snap.eurConsensus > 0.0) snap.eurConsensus else lastNow

            // --- 2) Dnevni min/max (UTC): iz history; fallback na SnapshotStore min/max
            val history = repo.historyFlow.first()
            val dayStartUtcMs = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

            val (mn, mx) = Indicators.dayMinMax(history, dayStartUtcMs) { it.eur }
            dayMin = mn ?: store.min
            dayMax = mx ?: store.max

            // --- 3) RSI(14): iz history; fallback na lokalnu seriju; default 50.0
            val series = if (history.isNotEmpty()) history.map { it.eur } else store.series.filterNotNull()
            rsiVal = Indicators.rsi(series, 14) ?: 50.0

            // Debug logovi
            Log.d(
                "SpotTile",
                "fallbacks → eurConsensus=${snap.eurConsensus} lastInSeries=$lastInSeries lastNow=$lastNow"
            )
            Log.d("SpotTile", "Resolved → eurNow=$eurNow dayMin=$dayMin dayMax=$dayMax rsiVal=$rsiVal")
        }

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

        Log.e("SpotTile", "<<< onTileRequest END → returning Tile")
        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<TileResBuilders.Resources> {
        Log.d("SpotTile", "onResourcesRequest() → version=${requestParams.version}")
        val res = TileResBuilders.Resources.Builder()
            .setVersion("1")
            .build()
        return Futures.immediateFuture(res)
    }

    private fun buildLayout(
        spotTxt: String,
        minTxt: String,
        maxTxt: String,
        rsiTxt: String
    ): LayoutElementBuilders.Layout {
        val cardBg  = c(0x1FFFFFFF.toInt())
        val titleCol = c(0xFFE0E0E0.toInt())
        val priceCol = c(0xFFFFFFFF.toInt())
        val minCol   = c(0xFFF05454.toInt())
        val maxCol   = c(0xFF38D66B.toInt())
        val hintCol  = c(0xFFB0B0B0.toInt())

        val title = text("Spot (XAU/EUR)", 13f, titleCol, true)
        val price = text(spotTxt, 28f, priceCol, true)
        val minT  = text("▼ $minTxt", 14f, minCol, false)
        val maxT  = text("▲ $maxTxt", 14f, maxCol, false)
        val rsi   = text(rsiTxt, 14f, hintCol, false)

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
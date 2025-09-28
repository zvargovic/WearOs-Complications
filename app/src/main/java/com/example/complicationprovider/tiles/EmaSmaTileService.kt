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

class EmaSmaTileService : TileService() {

    private val RADIUS_DP = 22f
    private val PADDING_DP = 14f
    private val GAP_DP = 6f

    private fun c(argb: Int) = ColorBuilders.argb(argb)
    private fun dp(v: Float) = DimensionBuilders.dp(v)
    private fun sp(v: Float) = DimensionBuilders.SpProp.Builder().setValue(v).build()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val layout = buildLayout()
        val timeline = ProtoTL.Timeline.Builder()
            .addTimelineEntry(ProtoTL.TimelineEntry.Builder().setLayout(layout).build())
            .build()
        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()
        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<TileResBuilders.Resources> {
        val res = TileResBuilders.Resources.Builder().setVersion("1").build()
        return Futures.immediateFuture(res)
    }

    private fun buildLayout(): LayoutElementBuilders.Layout {
        val cardBg   = c(0x1FFFFFFF.toInt())
        val titleCol = c(0xFFE0E0E0.toInt())
        val hintCol  = c(0xFFB0B0B0.toInt())

        val title = text("Križni graf", 13f, titleCol, true)
        val body  = text("(uskoro)", 12f, hintCol, false)

        val content = LayoutElementBuilders.Column.Builder()
            .addContent(title)
            .addContent(spacer(GAP_DP))
            .addContent(body)
            .build()

        val card = cardBox(cardBg, content)

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(card)
            .build()
    }

    private fun text(s: String, sizeSp: Float, color: ColorBuilders.ColorProp, bold: Boolean) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(sizeSp))
                    .setWeight(
                        LayoutElementBuilders.FontWeightProp.Builder()
                            .setValue(
                                if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD
                                else LayoutElementBuilders.FONT_WEIGHT_NORMAL
                            ).build()
                    )
                    .setColor(color)
                    .build()
            )
            .build()

    private fun cardBox(bg: ColorBuilders.ColorProp, content: LayoutElementBuilders.LayoutElement) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(bg)
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(RADIUS_DP)).build()
                            ).build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder().setAll(dp(PADDING_DP)).build()
                    )
                    .build()
            )
            .addContent(content)
            .build()

    private fun spacer(hDp: Float) =
        LayoutElementBuilders.Spacer.Builder().setHeight(dp(hDp)).build()

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(SparklineTileService::class.java)
        }
    }
}
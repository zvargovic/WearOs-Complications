package com.example.complicationprovider.tiles

// Tiles
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders as TilesRes
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// ProtoLayout (layout/timeline)
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL

// <-- VAŽNO: app R (radi drawabla ic_gold_market_open)
import com.example.complicationprovider.R

/**
 * Tile s 3 zone:
 *  1) Header: ikona + spot tekst (podesivi dx/dy + razmak ikona↔tekst)
 *  2) Sredina: PNG pravokutnik s gridom i skalom, centriran bez skaliranja
 *  3) Footer: tekst (podesivi dx/dy)
 */
class SparklineTileService : TileService() {

    // ---------- PODEŠAVANJE ----------
    // Pravokutnik (PNG)
    private var RECT_W_PX = 180
    private var RECT_H_PX = 100
    private var RECT_STROKE_PX = 1f
    private var RECT_COLOR = 0x00FF7A00.toInt()

    // GRID (unutar pravokutnika)
    private var GRID_BAND_H_PX = 8
    private var GRID_GAP_H_PX  = 9
    private var GRID_COLOR_ARGB = 0xAA3F2A00.toInt()

    // SKALA (donja linija s crticama i brojevima)
    private var SCALE_Y_PX = 88              // visina linije unutar rect-a
    private var SCALE_MAIN_COLOR = 0xFFFF7A00.toInt()
    private var SCALE_STROKE_PX = 1f
    private var SCALE_TICK_LEN_PX = -4        // duljina okomite crtice
    private var SCALE_TICK_COUNT = 5         // broj podioka
    private var SCALE_FONT_SIZE_PX = 8f
    private var SCALE_TEXT_COLOR = 0xFFFFFFFF.toInt()
    private var SCALE_TEXT_GAP_V = 2         // vertikalni razmak linija↔brojevi

    // Header
    private var HEADER_ICON_SIZE_DP = 12f
    private var HEADER_ICON_TEXT_GAP_DP = 10f
    private var HEADER_TEXT_SP = 12f
    private var HEADER_DX_DP = -80f
    private var HEADER_DY_DP = 1f

    // Razmak header↔rect
    private var HEADER_TO_RECT_SPACING_DP = 8f

    // Footer
    private var FOOTER_TEXT_SP = 12f
    private var FOOTER_DX_DP = 0f
    private var FOOTER_DY_DP = 0f
    private var RECT_TO_FOOTER_SPACING_DP = 10f

    // Boje teksta
    private val COLOR_TEXT = ColorBuilders.argb(0xFFFFFFFF.toInt())
    private val COLOR_MUTED = ColorBuilders.argb(0xFFB0B0B0.toInt())

    // ID-evi resursa
    private val RES_ID_RECT = "rect_png"
    private val RES_ID_ICON = "icon_png"

    private val resVersion: String
        get() = ImageCache.version ?: "1"

    // ---------- Tile lifecycle ----------
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest)
            : ListenableFuture<TileBuilders.Tile> {

        ensureResources(applicationContext)

        val layout = buildLayout()

        val timeline = ProtoTL.Timeline.Builder()
            .addTimelineEntry(
                ProtoTL.TimelineEntry.Builder().setLayout(layout).build()
            ).build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(resVersion)
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()

        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(requestParams: RequestBuilders.ResourcesRequest)
            : ListenableFuture<TilesRes.Resources> {

        val res = TilesRes.Resources.Builder()
            .setVersion(resVersion)
            .apply {
                // rect
                ImageCache.rectPng?.let { bytes ->
                    val inline = TilesRes.InlineImageResource.Builder()
                        .setData(bytes)
                        .setWidthPx(RECT_W_PX)
                        .setHeightPx(RECT_H_PX)
                        .build()
                    val img = TilesRes.ImageResource.Builder()
                        .setInlineResource(inline)
                        .build()
                    addIdToImageMapping(RES_ID_RECT, img)
                }
                // ikona
                ImageCache.iconPng?.let { bytes ->
                    val sizePx = dpToPx(applicationContext, HEADER_ICON_SIZE_DP)
                    val inline = TilesRes.InlineImageResource.Builder()
                        .setData(bytes)
                        .setWidthPx(sizePx)
                        .setHeightPx(sizePx)
                        .build()
                    val img = TilesRes.ImageResource.Builder()
                        .setInlineResource(inline)
                        .build()
                    addIdToImageMapping(RES_ID_ICON, img)
                }
            }
            .build()

        return Futures.immediateFuture(res)
    }

    // ---------- Layout ----------
    private fun buildLayout(): LayoutElementBuilders.Layout {
        val dp = { v: Float -> DimensionBuilders.dp(v) }
        val sp = { v: Float -> DimensionBuilders.SpProp.Builder().setValue(v).build() }
        val wrap = DimensionBuilders.WrappedDimensionProp.Builder().build()

        // HEADER
        val headerRow = LayoutElementBuilders.Row.Builder()
            .apply {
                if (HEADER_DX_DP > 0f) addContent(spacerW(dp(HEADER_DX_DP)))
                addContent(
                    LayoutElementBuilders.Image.Builder()
                        .setResourceId(RES_ID_ICON)
                        .setWidth(dp(HEADER_ICON_SIZE_DP))
                        .setHeight(dp(HEADER_ICON_SIZE_DP))
                        .build()
                )
                addContent(spacerW(dp(HEADER_ICON_TEXT_GAP_DP)))
                addContent(
                    LayoutElementBuilders.Text.Builder()
                        .setText(getSpotText())
                        .setFontStyle(
                            LayoutElementBuilders.FontStyle.Builder()
                                .setSize(sp(HEADER_TEXT_SP))
                                .setColor(COLOR_TEXT)
                                .build()
                        )
                        .build()
                )
                if (HEADER_DX_DP < 0f) addContent(spacerW(dp(-HEADER_DX_DP)))
            }
            .build()

        val headerBox = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(kotlin.math.max(0f, HEADER_DY_DP)))
                            .setBottom(dp(kotlin.math.max(0f, -HEADER_DY_DP)))
                            .build()
                    )
                    .build()
            )
            .setWidth(wrap)
            .setHeight(wrap)
            .addContent(headerRow)
            .build()

        // RECT s gridom + skalom
        val rectImg = LayoutElementBuilders.Image.Builder()
            .setResourceId(RES_ID_RECT)
            .setWidth(DimensionBuilders.dp(RECT_W_PX.toFloat()))
            .setHeight(DimensionBuilders.dp(RECT_H_PX.toFloat()))
            .build()

        val midBox = LayoutElementBuilders.Box.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(rectImg)
            .build()

        // FOOTER
        val footerText = LayoutElementBuilders.Text.Builder()
            .setText(getFooterText())
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(FOOTER_TEXT_SP))
                    .setColor(COLOR_MUTED)
                    .build()
            )
            .build()

        val footerRow = LayoutElementBuilders.Row.Builder()
            .apply {
                if (FOOTER_DX_DP > 0f) addContent(spacerW(dp(FOOTER_DX_DP)))
                addContent(footerText)
                if (FOOTER_DX_DP < 0f) addContent(spacerW(dp(-FOOTER_DX_DP)))
            }
            .build()

        val footerBox = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(dp(kotlin.math.max(0f, FOOTER_DY_DP)))
                            .setBottom(dp(kotlin.math.max(0f, -FOOTER_DY_DP)))
                            .build()
                    )
                    .build()
            )
            .setWidth(wrap)
            .setHeight(wrap)
            .addContent(footerRow)
            .build()

        // ROOT
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headerBox)
            .addContent(spacerH(dp(HEADER_TO_RECT_SPACING_DP)))
            .addContent(midBox)
            .addContent(spacerH(dp(RECT_TO_FOOTER_SPACING_DP)))
            .addContent(footerBox)
            .build()

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(column)
            .build()
    }

    private fun spacerW(w: DimensionBuilders.DpProp) =
        LayoutElementBuilders.Spacer.Builder().setWidth(w).build()

    private fun spacerH(h: DimensionBuilders.DpProp) =
        LayoutElementBuilders.Spacer.Builder().setHeight(h).build()

    private fun getSpotText(): String = "€0.00"
    private fun getFooterText(): String = "Dnevni graf"

    // ---------- Rendering i cache ----------
    private fun ensureResources(ctx: Context) {
        if (ImageCache.rectPng == null) {
            ImageCache.rectPng = ChartRenderer.renderRectWithGridAndScalePng(
                widthPx  = RECT_W_PX,
                heightPx = RECT_H_PX,
                rectWpx  = RECT_W_PX,
                rectHpx  = RECT_H_PX,
                strokePx = RECT_STROKE_PX,
                rectColor = RECT_COLOR,
                gridHeightPx = GRID_BAND_H_PX,
                gridGapPx    = GRID_GAP_H_PX,
                gridColor    = GRID_COLOR_ARGB,
                scaleY       = SCALE_Y_PX,
                scaleColor   = SCALE_MAIN_COLOR,
                tickCount    = SCALE_TICK_COUNT,
                tickLenPx    = SCALE_TICK_LEN_PX,
                textSizePx   = SCALE_FONT_SIZE_PX,
                textColor    = SCALE_TEXT_COLOR,
                textGapV     = SCALE_TEXT_GAP_V
            )
        }
        if (ImageCache.iconPng == null) {
            ImageCache.iconPng = rasterizeDrawableToPng(
                ctx = ctx,
                resId = R.drawable.ic_gold_market_open,
                targetPx = dpToPx(ctx, HEADER_ICON_SIZE_DP),
                tintArgb = 0xFFFF7A00.toInt()
            )
        }
        if (ImageCache.version == null) {
            ImageCache.version = System.currentTimeMillis().toString()
        }
    }

    private fun rasterizeDrawableToPng(
        ctx: Context,
        resId: Int,
        targetPx: Int,
        tintArgb: Int? = null
    ): ByteArray? {
        val d: Drawable = ResourcesCompat.getDrawable(ctx.resources, resId, ctx.theme)
            ?: return null
        val dr = d.mutate()
        if (tintArgb != null) {
            DrawableCompat.setTint(dr, tintArgb)
        }
        val bmp = Bitmap.createBitmap(targetPx, targetPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        dr.setBounds(0, 0, targetPx, targetPx)
        dr.draw(c)
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun dpToPx(ctx: Context, dp: Float): Int {
        val d = ctx.resources.displayMetrics.density
        return kotlin.math.max(1, (dp * d).toInt())
    }

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(SparklineTileService::class.java)
        }
    }
}

/** Jednostavni in-memory cache PNG-ova i verzije. */
private object ImageCache {
    @Volatile var rectPng: ByteArray? = null
    @Volatile var iconPng: ByteArray? = null
    @Volatile var version: String? = null
}
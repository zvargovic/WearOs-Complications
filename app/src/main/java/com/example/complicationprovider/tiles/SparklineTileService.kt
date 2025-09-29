package com.example.complicationprovider.tiles

// Tiles
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.ResourceBuilders as TilesRes
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// ProtoLayout (layout/timeline)
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL
import androidx.core.graphics.drawable.DrawableCompat

// <-- VAŽNO: app R (radi drawabla ic_gold_market_open)
import com.example.complicationprovider.R

// (opcionalno za realne podatke – možeš naknadno povezati)
// import com.example.complicationprovider.data.SettingsRepo
// import kotlinx.coroutines.flow.first
// import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

/**
 * Tile s 3 zone:
 *  1) Header: ikona + spot tekst (podesivi dx/dy + razmak ikona↔tekst)
 *  2) Sredina: PNG pravokutnik točne veličine (px), centriran bez skaliranja
 *  3) Footer: tekst (podesivi dx/dy)
 */
class SparklineTileService : TileService() {

    // ---------- PODEŠAVANJE ----------
    // Pravokutnik (PNG) – veličina u *px*
    private var RECT_W_PX = 180
    private var RECT_H_PX = 100
    private var RECT_STROKE_PX = 1f
    private var RECT_COLOR = 0x00FF7A00.toInt()

    // GRID (unutar pravokutnika) – PODESIVO
    private var GRID_BAND_H_PX = 7     // visina jedne trake
    private var GRID_GAP_H_PX  = 8     // razmak između traka
    private var GRID_COLOR_ARGB = 0x557E5400.toInt() // poluprozirna tamno-narančasta

    // SKALA (donja linija + crtice + oznake)
    private var SCALE_Y_FROM_TOP_PX = 80
    private var SCALE_MAIN_STROKE_PX = 1f
    private var SCALE_COLOR = 0xFFFF7A00.toInt()
    private var SCALE_TICKS = 5
    private var SCALE_TICK_LEN_PX = 5
    private var SCALE_TICK_STROKE_PX = 1f
    private var SCALE_LABELS = listOf("0", "6", "12", "18", "(h)")
    private var SCALE_LABEL_SIZE_PX = 8f
    private var SCALE_LABEL_COLOR = 0xFFFFFFFF.toInt()
    private var SCALE_LABEL_GAP_Y_PX = 2
    private var SCALE_EDGE_LABEL_INSET_PX = 2

    // ==== BAROVI – SVE PODESIVO OVDJE ====
    private var BAR_ALPHA_FULL = 0xFF          // 100% vidljiv
    private var BAR_ALPHA_STUB = 0x55          // “no data” stub ~33% vidljiv
    private var BAR_COLOR_RGB  = 0xFFFF7A00.toInt()      // boja barova (RGB bez alfe)
    private var BAR_MIN_PX     = 2             // min visina kad ima podatak
    private var BAR_STUB_PX    = 2             // visina kad nema podatka (null)
    private var BAR_BASELINE_GAP_PX = 1f       // razmak bara od linije skale (da se ne dodiruju)

    // Header (ikona + tekst)
    private var HEADER_ICON_SIZE_DP = 12f
    private var HEADER_ICON_TEXT_GAP_DP = 10f
    private var HEADER_TEXT_SP = 12f
    private var HEADER_DX_DP = -80f   // +desno / -lijevo
    private var HEADER_DY_DP = 1f     // +dolje  / -gore

    // Razmak između headera i pravokutnika
    private var HEADER_TO_RECT_SPACING_DP = 8f

    // Footer
    private var FOOTER_TEXT_SP = 12f
    private var FOOTER_DX_DP = 0f
    private var FOOTER_DY_DP = 0f
    private var RECT_TO_FOOTER_SPACING_DP = 10f

    // Boje teksta
    private val COLOR_TEXT = ColorBuilders.argb(0xFFFFFFFF.toInt())
    private val COLOR_MUTED = ColorBuilders.argb(0xFFB0B0B0.toInt())

    // ID-evi u Resources mapi
    private val RES_ID_RECT = "rect_png"
    private val RES_ID_ICON = "icon_png"

    // Verzija resourcesa – kad je promijenimo, host invalidira cache
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
                // 1) pravokutnik (PNG s gridom, skalom i barovima)
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
                // 2) ikona (PNG raster s poznatom px veličinom)
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

    // ---------- Layout (3 zone) ----------
    private fun buildLayout(): LayoutElementBuilders.Layout {
        val dp = { v: Float -> DimensionBuilders.dp(v) }
        val sp = { v: Float -> DimensionBuilders.SpProp.Builder().setValue(v).build() }
        val wrap = DimensionBuilders.WrappedDimensionProp.Builder().build()

        // HEADER (Row: [Icon] [gap] [Text]) + DX/DY
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

        // SREDINA – PNG pravokutnik, točne dimenzije, centriran
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

        // FOOTER – tekst s DX/DY pomakom
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
                if (FOOTER_DX_DP > 0f) addContent(spacerW(DimensionBuilders.dp(FOOTER_DX_DP)))
                addContent(footerText)
                if (FOOTER_DX_DP < 0f) addContent(spacerW(DimensionBuilders.dp(-FOOTER_DX_DP)))
            }
            .build()

        val footerBox = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setTop(DimensionBuilders.dp(kotlin.math.max(0f, FOOTER_DY_DP)))
                            .setBottom(DimensionBuilders.dp(kotlin.math.max(0f, -FOOTER_DY_DP)))
                            .build()
                    )
                    .build()
            )
            .setWidth(wrap)
            .setHeight(wrap)
            .addContent(footerRow)
            .build()

        // ROOT: Column (header, spacer, middle, spacer, footer)
        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(headerBox)
            .addContent(spacerH(DimensionBuilders.dp(HEADER_TO_RECT_SPACING_DP)))
            .addContent(midBox)
            .addContent(spacerH(DimensionBuilders.dp(RECT_TO_FOOTER_SPACING_DP)))
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

    private fun getSpotText(): String {
        // TODO: zamijeni stvarnim podatkom iz repozitorija
        return "€0.00"
    }

    private fun getFooterText(): String = "Dnevni graf"

    // ---------- Rendering i cache ----------
    private fun ensureResources(ctx: Context) {
        if (ImageCache.rectPng == null) {
            // 1) pripremi 48-slot seriju (placeholder – slobodno ćeš zamijeniti realnim podacima)
            val (series, dayMax) = build48SeriesPlaceholder()

            // 2) nacrtaj: okvir + grid + skala + barovi
            ImageCache.rectPng = ChartRenderer.renderRectWithGridScaleAndBarsPng(
                widthPx  = RECT_W_PX,
                heightPx = RECT_H_PX,
                rectWpx  = RECT_W_PX,
                rectHpx  = RECT_H_PX,
                // okvir
                strokePx = RECT_STROKE_PX,
                rectColor = RECT_COLOR,
                // grid
                gridHeightPx = GRID_BAND_H_PX,
                gridGapPx    = GRID_GAP_H_PX,
                gridColor    = GRID_COLOR_ARGB,
                // skala
                scaleYFromRectTopPx = SCALE_Y_FROM_TOP_PX,
                scaleMainStrokePx   = SCALE_MAIN_STROKE_PX,
                scaleColor          = SCALE_COLOR,
                scaleTicks          = SCALE_TICKS,
                scaleTickLenPx      = SCALE_TICK_LEN_PX,
                scaleTickStrokePx   = SCALE_TICK_STROKE_PX,
                labels              = SCALE_LABELS,
                labelTextSizePx     = SCALE_LABEL_SIZE_PX,
                labelColor          = SCALE_LABEL_COLOR,
                labelGapYPx         = SCALE_LABEL_GAP_Y_PX,
                edgeLabelInsetPx    = SCALE_EDGE_LABEL_INSET_PX,
                // barovi (SVE iz podesivih varijabli!)
                series              = series,
                dayMax              = dayMax,
                barAlphaFull        = BAR_ALPHA_FULL,
                barAlphaStub        = BAR_ALPHA_STUB,
                barColorRgb         = BAR_COLOR_RGB,
                barMinPx            = BAR_MIN_PX,
                barStubPx           = BAR_STUB_PX,
                barBaselineGapPx    = BAR_BASELINE_GAP_PX
            )
        }
        if (ImageCache.iconPng == null) {
            ImageCache.iconPng = rasterizeDrawableToPng(
                ctx = ctx,
                resId = R.drawable.ic_gold_market_open,
                targetPx = dpToPx(ctx, HEADER_ICON_SIZE_DP),
                tintArgb = 0xFFFF7A00.toInt() // NARANČASTA
            )
        }
        if (ImageCache.version == null) {
            ImageCache.version = System.currentTimeMillis().toString()
        }
        Log.d(
            "SparklineTile3Zones",
            "rect PNG ready len=${ImageCache.rectPng?.size ?: 0} ver=${ImageCache.version}"
        )
    }

    /**
     * Placeholder serija od 48 slotova (30 min) s par praznina (null) da vidiš "stubove".
     * Zamijeni kasnije stvarnim bucketiranjem na 48 polusatnih prozora.
     */
    private fun build48SeriesPlaceholder(): Pair<List<Double?>, Double?> {
        val out = MutableList<Double?>(48) { null }
        // neka sinusoidna forma + rupe
        for (i in 0 until 48) {
            if (i % 7 == 0) {
                out[i] = null // “nema fetcha”
            } else {
                val v = 100.0 + 20.0 * kotlin.math.sin(i / 48.0 * Math.PI * 4)
                out[i] = v
            }
        }
        val maxVal = out.filterNotNull().maxOrNull()
        return out to maxVal
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
        return max(1, (dp * d).toInt())
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
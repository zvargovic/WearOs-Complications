package com.example.complicationprovider.tiles

// Tiles
import android.content.Context
import android.util.Log
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// ProtoLayout 1.3.0 – layout / timeline
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders as ProtoTL

// VAŽNO: resources iz TILES paketa (ne protolayout!)
import androidx.wear.tiles.ResourceBuilders as TilesRes

// App podaci
import com.example.complicationprovider.data.SettingsRepo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SparklineTileService : TileService() {

    private val TAG = "SparklineTile"

    private val PNG_W = 280
    private val PNG_H = 120
    private val RES_ID = "spark_png"

    // verzija resursa – host invalidira cache kad se promijeni
    private val RES_VER: String
        get() = SparklinePngCache.version ?: "1"

    private fun c(argb: Int) = ColorBuilders.argb(argb)
    private fun dp(v: Float) = DimensionBuilders.dp(v)
    private fun sp(v: Float) = DimensionBuilders.SpProp.Builder().setValue(v).build()

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {

        Log.d(TAG, "onTileRequest() — ensurePngCached, current version=${SparklinePngCache.version}, bytes=${SparklinePngCache.bytes?.size ?: 0}")
        ensurePngCached(applicationContext)

        val layout = buildTileLayout()

        val timeline = ProtoTL.Timeline.Builder()
            .addTimelineEntry(
                ProtoTL.TimelineEntry.Builder()
                    .setLayout(layout)
                    .build()
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VER)      // MORA biti ista kao u onResourcesRequest
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000)
            .build()

        Log.d(TAG, "onTileRequest() — returning tile with resourcesVersion=$RES_VER")
        return Futures.immediateFuture(tile)
    }

    override fun onResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<TilesRes.Resources> {

        Log.d(TAG, "onResourcesRequest() — start, requested version=${requestParams.version}")
        ensurePngCached(applicationContext)

        val resBuilder = TilesRes.Resources.Builder()
            .setVersion(RES_VER)               // MORA se poklapati s Tile.resourcesVersion

        val bytes = SparklinePngCache.bytes
        if (bytes == null) {
            Log.w(TAG, "onResourcesRequest() — PNG BYTES = null → gost nema što poslužiti")
        } else {
            Log.d(TAG, "onResourcesRequest() — PNG BYTES len=${bytes.size}, mapping RES_ID=$RES_ID, version=$RES_VER")

            // Inline PNG -> wrap u ImageResource, pa mapiranje na ID koji layout koristi
            val inline = TilesRes.InlineImageResource.Builder()
                .setData(bytes)
                .setWidthPx(PNG_W)        // ← OVO JE KLJUČNO (bez ovoga host ponekad javi width/height = 0)
                .setHeightPx(PNG_H)
                .build()

            val imgRes = TilesRes.ImageResource.Builder()
                .setInlineResource(inline)
                .build()

            resBuilder.addIdToImageMapping(RES_ID, imgRes)
        }

        val res = resBuilder.build()
        Log.d(TAG, "onResourcesRequest() — returning Resources with version=$RES_VER, hasImage=${bytes != null}")
        return Futures.immediateFuture(res)
    }

    /** Jednostavna kartica s naslovom i inline PNG-om. */
    private fun buildTileLayout(): LayoutElementBuilders.Layout {
        val cardBg   = c(0xFF121212.toInt())
        val titleCol = c(0xFFE0E0E0.toInt())

        val title = LayoutElementBuilders.Text.Builder()
            .setText("Dnevni graf")
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(13f))
                    .setWeight(
                        LayoutElementBuilders.FontWeightProp.Builder()
                            .setValue(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                            .build()
                    )
                    .setColor(titleCol)
                    .build()
            )
            .build()

        val image = LayoutElementBuilders.Image.Builder()
            .setResourceId(RES_ID)                // → mora imati mapping u onResourcesRequest
            .setWidth(dp(PNG_W.toFloat()))
            .setHeight(dp(PNG_H.toFloat()))
            .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FIT)
            .build()

        val column = LayoutElementBuilders.Column.Builder()
            .addContent(title)
            .addContent(
                LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build()
            )
            .addContent(image)
            .build()

        val card = LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(cardBg)
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(dp(22f))
                                    .build()
                            )
                            .build()
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(dp(14f))
                            .build()
                    )
                    .build()
            )
            .addContent(column)
            .build()

        return LayoutElementBuilders.Layout.Builder()
            .setRoot(card)
            .build()
    }

    /** Ako PNG nije u kešu – izgradi ga iz današnjih vrijednosti (EUR). */
    private fun ensurePngCached(ctx: Context) {
        if (SparklinePngCache.bytes != null) {
            Log.d(TAG, "ensurePngCached() — bytes already present len=${SparklinePngCache.bytes?.size} ver=${SparklinePngCache.version}")
            return
        }

        Log.d(TAG, "ensurePngCached() — building TODAY series (EUR)")
        val seriesToday = buildTodaySeries(ctx, maxPoints = 100)

        // fallback ako je prazno
        val safeSeries = when {
            seriesToday.size >= 2 -> seriesToday
            seriesToday.size == 1 -> List(20) { seriesToday.first() }
            else -> {
                val lastKnown = runBlocking { SettingsRepo(ctx).historyFlow.first().lastOrNull()?.eur }
                List(20) { (lastKnown ?: 0.0) }
            }
        }

        val png = ChartRenderer.renderSparklinePng(
            series = safeSeries,
            widthPx = PNG_W,
            heightPx = PNG_H
        )

        SparklinePngCache.bytes = png
        SparklinePngCache.version = System.currentTimeMillis().toString()
        Log.d(TAG, "ensurePngCached() — PNG ready len=${png.size} newVersion=${SparklinePngCache.version}")
    }

    /** Izgradi listu današnjih EUR točaka iz SettingsRepo.history, downsample na maxPoints. */
    private fun buildTodaySeries(ctx: Context, maxPoints: Int): List<Double> {
        val repo = SettingsRepo(ctx)
        val all = runBlocking { repo.historyFlow.first() }

        // današnji početak (UTC)
        val startUtc = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli()

        // filtriraj današnje, pozitivne i konačne vrijednosti
        val todays = all.asSequence()
            .filter { it.ts >= startUtc }
            .map { it.eur }
            .filter { it > 0.0 && it.isFinite() }
            .toList()

        if (todays.isEmpty()) return emptyList()
        if (todays.size <= maxPoints) return todays

        // downsample na maxPoints: prosjek po “bucketu”
        val step = kotlin.math.ceil(todays.size.toDouble() / maxPoints).toInt().coerceAtLeast(1)
        return todays.chunked(step).map { chunk -> chunk.average() }
    }

    companion object {
        fun requestUpdate(context: Context) {
            TileService.getUpdater(context).requestUpdate(SparklineTileService::class.java)
        }
    }
}

/** Jednostavan in-memory cache PNG-a i njegove verzije. */
object SparklinePngCache {
    @Volatile var bytes: ByteArray? = null
    @Volatile var version: String? = null
}
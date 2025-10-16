// SparklineTileService.kt
package com.example.complicationprovider.tiles

import android.graphics.Bitmap
import android.util.Log
import androidx.wear.tiles.DimensionBuilders
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.ResourceBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.example.complicationprovider.data.SnapshotStore
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import kotlin.math.abs

class SparklineTileService : TileService() {

    companion object {
        private const val TAG = "SparklineTile"
        private const val IMG_ID = "chart_v10"   // isti ID u layoutu i resources
        fun requestUpdate(context: android.content.Context) {
            android.util.Log.d(TAG, "requestUpdate() → TileUpdater.requestUpdate")
            TileService.getUpdater(context).requestUpdate(SparklineTileService::class.java)
        }

        /** (opcionalno) počisti memory cache da prisilimo novi render PNG-a */
        fun invalidateCache() {
            TilePngCache.bytes = null
            TilePngCache.version = ""     // prazno umjesto null

        }
    }

    // ------------------------------------------------------------
    // LAYOUT: fiksne dp dimenzije + nova resourcesVersion
    // ------------------------------------------------------------
    override fun onTileRequest(req: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        Log.wtf(TAG, ">>> onTileRequest START")
        val freshness = 60_000L
        val ver = System.currentTimeMillis().toString()
        Log.d("SparkLineTileService", "onTileRequest ver=$ver")

        val image = LayoutElementBuilders.Image.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setResourceId(IMG_ID)
            .build()

        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(ChartDims.W_DP))
            .setHeight(DimensionBuilders.dp(ChartDims.H_DP))
            .addContent(image)
            .build()

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(root)
            .build()

        val entry = TimelineBuilders.TimelineEntry.Builder()
            .setLayout(layout)
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(entry)
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(ver)               // forsira onResourcesRequest
            .setFreshnessIntervalMillis(freshness)  // 60s
            .setTimeline(timeline)
            .build()

        Log.d(TAG, "LAYOUT uses IMG_ID=$IMG_ID, ver=$ver")
        Log.wtf(TAG, "<<< onTileRequest END (ver=$ver)")
        return Futures.immediateFuture(tile)
    }

    // ------------------------------------------------------------
    // RESOURCES: ensureOpen + cache + 2×2 fallback (isti IMG_ID)
    // ------------------------------------------------------------
    override fun onResourcesRequest(req: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> {
        // 0) Osiguraj OPEN ako nema prvih slotova danas (uzmi midnight ili jučer)
        runBlocking {
            SnapshotStore.ensureOpenFromMidnightOrYesterday(this@SparklineTileService, System.currentTimeMillis())
        }

        val ver = req.version ?: System.currentTimeMillis().toString()
        Log.d(TAG, "onResourcesRequest → req.version=$ver")

        // 1) Pokušaj memory cache
        var png = TilePngCache.bytes
        if (png == null) {
            // 2) Render sada (sinkrono)
            val cfg = ChartRenderer.Config(
                widthPx = ChartDims.W_PX,
                heightPx = ChartDims.H_PX
            )
            val d = runBlocking { SnapshotStore.get(this@SparklineTileService, cfg.slotsPerDay) }
            val series = ChartRenderer.Series(
                open = d.open,
                values = d.series,
                min = d.min,
                max = d.max,
                lastPrice = d.series.lastOrNull { it != null } ?: d.open
            )

            png = try {
                ChartRenderer.renderPNG(this, cfg, series) { v ->
                    if (abs(v) >= 1000) String.format("%,.2f €", v) else String.format("%.2f €", v)
                }.also { data ->
                    Log.d(TAG, "renderPNG → size=${data.size}B, open=${series.open}, last=${series.lastPrice}, min=${series.min}, max=${series.max}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "renderPNG ERROR", t)
                ByteArray(0)
            }

            if (png.isNotEmpty()) {
                TilePngCache.bytes = png
                TilePngCache.version = ver
            }
        } else {
            Log.d(TAG, "using cached PNG → size=${png.size}B, ver=${TilePngCache.version}")
        }

        // 3) Fallback: nikad prazan PNG
        val safePng = if (png.isNotEmpty()) png else {
            Log.w(TAG, "empty PNG → sending 2x2 placeholder")
            val bmp = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            ByteArrayOutputStream().apply {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
        }

        // 4) Mapiraj JEDINI isti ID + eksplicitne px dimenzije
        val res = ResourceBuilders.Resources.Builder()
            .setVersion(ver)
            .addIdToImageMapping(
                IMG_ID,
                ResourceBuilders.ImageResource.Builder()
                    .setInlineResource(
                        ResourceBuilders.InlineImageResource.Builder()
                            .setData(safePng)
                            .setWidthPx(ChartDims.W_PX)   // ← BITNO
                            .setHeightPx(ChartDims.H_PX)  // ← BITNO
                            .build()
                    )
                    .build()
            )
            .build()

        Log.d(TAG, "RESOURCES map IMG_ID=$IMG_ID, png=${safePng.size}B, ver=$ver")
        return Futures.immediateFuture(res)
    }
}
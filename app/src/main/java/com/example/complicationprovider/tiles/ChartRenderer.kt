package com.example.complicationprovider.tiles

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ChartRenderer {

    private const val TAG = "ChartRenderer"

    data class Config(
        // Dimenzije
        val widthPx: Int = ChartDims.W_PX,
        val heightPx: Int = ChartDims.H_PX,

        // Boje
        val bg: Int = 0xFF000000.toInt(),
        val line: Int = 0xFF22C55E.toInt(),
        val ref: Int = 0x33FFFFFF.toInt(),
        val headerText: Int = 0xFFFFFFFF.toInt(),
        val deltaPos: Int = 0xFF22C55E.toInt(),
        val deltaNeg: Int = 0xFFE0524D.toInt(),
        val axisText: Int = 0xFFBBBBBB.toInt(),

        // Min/Max boje
        val maxColor: Int = 0xFF22C55E.toInt(),
        val minColor: Int = 0xFFE0524D.toInt(),

        // Linija i točka
        val strokePx: Float = 4f,
        val dotRadiusPx: Float = 6f,
        val smoothingT: Float = 0.5f,

        // Ref linija
        val refStrokePx: Float = 2f,
        val refDash: FloatArray = floatArrayOf(10f, 10f),

        // Padding — povećan padB da stane RSI ispod grafa
        val padL: Float = 130f,
        val padR: Float = 130f,
        val padT: Float = 52f,
        val padB: Float = 40f,              // ← malo veći da stane RSI tekst

        // Tipografija
        val priceTextPx: Float = 28f,
        val deltaTextPx: Float = 16f,
        val sideTextPx: Float = 14f,
        val refLabelTextPx: Float = 14f,

        // Header pozicije
        val headerX: Float = 250f,
        val headerY: Float = 22f,
        val deltaX: Float = 250f,
        val deltaY: Float = 44f,

        val refLabelOffsetX: Float = 10f,
        val refLabelOffsetY: Float = -6f,

        val minLabelOffsetX: Float = 0f,
        val minLabelOffsetY: Float = -4f,
        val maxLabelOffsetX: Float = 0f,
        val maxLabelOffsetY: Float = 18f,

        val minMaxMinGapPx: Float = 50f,

        // Debug okvir plota
        val showPlotBounds: Boolean = true,

        // RSI prikaz
        val showRsi: Boolean = true,        // ← uključen prikaz
        val rsiTextPx: Float = 14f,
        val rsiColor: Int = 0xFFBBBBBB.toInt(),
        val rsiOffsetBelowAreaPx: Float = 16f, // udaljenost od donje crte area

        val slotsPerDay: Int = 48
    )

    data class Series(
        val open: Double?,
        val values: List<Double?>,
        val min: Double?,
        val max: Double?,
        val lastPrice: Double?,
        val rsi: Double? = null             // ← DODANO: RSI vrijednost (npr. RSI14)
    )

    private fun Paint.setupText(sizePx: Float, color: Int, isBold: Boolean = false) = apply {
        reset()
        this.color = color
        this.textSize = sizePx
        this.isAntiAlias = true
        this.typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    fun renderPNG(
        context: Context,
        cfg: Config,
        series: Series,
        onFormatPrice: (Double) -> String = { v ->
            if (abs(v) >= 1000) String.format("%,.2f", v) else String.format("%.2f", v)
        }
    ): ByteArray {
        Log.d(
            TAG,
            "render start → size=${cfg.widthPx}x${cfg.heightPx}, " +
                    "points=${series.values.count { it != null }}, " +
                    "open=${series.open}, last=${series.lastPrice}, " +
                    "min=${series.min}, max=${series.max}, rsi=${series.rsi}"
        )

        val bmp = Bitmap.createBitmap(cfg.widthPx, cfg.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cfg.bg)

        val area = RectF(
            cfg.padL,
            cfg.padT,
            cfg.widthPx - cfg.padR,
            cfg.heightPx - cfg.padB
        )
        Log.d(TAG, "plot area → $area (padL=${cfg.padL}, padT=${cfg.padT}, padR=${cfg.padR}, padB=${cfg.padB})")

        // Debug okvir oko area
        if (cfg.showPlotBounds) {
            val dbg = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            c.drawRect(area, dbg)
        }

        // scale
        val values = series.values
        val nonNull = values.filterNotNull()
        val minV = (series.min ?: nonNull.minOrNull()) ?: series.open ?: 0.0
        val maxV = (series.max ?: nonNull.maxOrNull()) ?: series.open ?: 1.0

        var lo = min(minV, series.open ?: minV)
        var hi = max(maxV, series.open ?: maxV)
        if (hi <= lo) {
            val op = series.open ?: 0.0
            lo = op - 1.0
            hi = op + 1.0
            Log.d(TAG, "span=0 → forcing symmetric window around OPEN: [$lo, $hi]")
        }
        val span = hi - lo
        Log.d(TAG, "scale → lo=$lo hi=$hi span=$span (nonNull=${nonNull.size})")

        fun xAt(i: Int): Float {
            val n = (values.size - 1).coerceAtLeast(1)
            return area.left + (i.toFloat() / n.toFloat()) * area.width()
        }
        fun yAt(v: Double): Float {
            val t = ((v - lo) / span).toFloat()
            return area.bottom - t * area.height()
        }

        // ref (OPEN)
        val refY = series.open?.let { yAt(it) }
        if (refY != null) {
            val refPaint = Paint().apply {
                color = cfg.ref
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = cfg.refStrokePx
                pathEffect = DashPathEffect(cfg.refDash, 0f)
            }
            val p = Path().apply {
                moveTo(area.left - 30f, refY)
                lineTo(area.right + 30f, refY)
            }
            c.drawPath(p, refPaint)

            val txt = onFormatPrice(series.open!!)
            val tPaint = Paint().setupText(cfg.refLabelTextPx, cfg.axisText, false)
            c.drawText(txt, area.left + cfg.refLabelOffsetX, refY + cfg.refLabelOffsetY, tPaint)
        }

        // sparkline
        val linePaint = Paint().apply {
            color = cfg.line
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = cfg.strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val path = Path()
        var started = false
        var drawnPts = 0
        for (i in values.indices) {
            val v = values[i] ?: continue
            val x = xAt(i)
            val y = yAt(v)
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                val i0 = (i - 1).coerceAtLeast(0)
                val i2 = (i + 1).coerceAtMost(values.lastIndex)
                val i3 = (i + 2).coerceAtMost(values.lastIndex)

                val v0 = values[i0] ?: v
                val v2 = values[i2] ?: v
                val v3 = values[i3] ?: v2

                val x0 = xAt(i0); val y0 = yAt(v0)
                val x2 = xAt(i2); val y2 = yAt(v2)
                val x3 = xAt(i3); val y3 = yAt(v3)

                val t = cfg.smoothingT
                val cp1x = x + (x2 - x0) * t * 0.5f
                val cp1y = y + (y2 - y0) * t * 0.5f
                val cp2x = x2 - (x3 - x) * t * 0.5f
                val cp2y = y2 - (y3 - y) * t * 0.5f
                path.cubicTo(cp1x, cp1y, cp2x, cp2y, x2, y2)
            }
            drawnPts++
        }
        if (started) c.drawPath(path, linePaint)

        // last dot
        val lastIdx = values.indexOfLast { it != null }
        if (lastIdx >= 0) {
            val lastV = values[lastIdx]!!
            val lastX = xAt(lastIdx)
            val lastY = yAt(lastV)
            val dotPaint = Paint().apply {
                color = cfg.line
                isAntiAlias = true
                style = Paint.Style.FILL
            }
            c.drawCircle(lastX, lastY, cfg.dotRadiusPx, dotPaint)
        }

        // Min/Max oznake
        run {
            val minVal = series.min
            val maxVal = series.max
            if (minVal != null || maxVal != null) {
                val tickPaintMin = Paint().apply {
                    color = cfg.minColor; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
                }
                val tickPaintMax = Paint().apply {
                    color = cfg.maxColor; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = 2f
                }
                val textPaintMin = Paint().setupText(cfg.sideTextPx, cfg.minColor, false)
                val textPaintMax = Paint().setupText(cfg.sideTextPx, cfg.maxColor, false)

                val labelX = area.right - 75f
                val tickL = area.right - 6f
                val tickR = area.right

                var yMin = minVal?.let { yAt(it) }
                var yMax = maxVal?.let { yAt(it) }

                if (yMin != null && yMax != null && abs(yMax - yMin) < cfg.minMaxMinGapPx) {
                    val need = cfg.minMaxMinGapPx - abs(yMax - yMin)
                    yMin = (yMin + need).coerceAtMost(area.bottom - 2f)
                }

                yMax?.let { y ->
                    c.drawLine(tickL, y, tickR, y, tickPaintMax)
                    c.drawText(onFormatPrice(maxVal!!), labelX + cfg.maxLabelOffsetX, y + cfg.maxLabelOffsetY, textPaintMax)
                }
                yMin?.let { y ->
                    c.drawLine(tickL, y, tickR, y, tickPaintMin)
                    c.drawText(onFormatPrice(minVal!!), labelX + cfg.minLabelOffsetX, y + cfg.minLabelOffsetY, textPaintMin)
                }
            }
        }

        // Header (cijena + delta)
        series.lastPrice?.let { cur ->
            val pricePaint = Paint().setupText(cfg.priceTextPx, cfg.headerText, true)
            c.drawText(onFormatPrice(cur), cfg.headerX, cfg.headerY, pricePaint)

            series.open?.let { op ->
                val delta = cur - op
                val pct = if (op != 0.0) (delta / op) * 100.0 else 0.0
                val deltaPaint = Paint().setupText(cfg.deltaTextPx, if (delta >= 0) cfg.deltaPos else cfg.deltaNeg, false)
                val deltaTxt = String.format("%+.2f%%   %s", pct, if (abs(delta) >= 1000) String.format("%,.2f", delta) else String.format("%.2f", delta))
                c.drawText(deltaTxt, cfg.deltaX, cfg.deltaY, deltaPaint)
            }
        }

        // === RSI ispod donje crte (u donjem paddingu) ===
        if (cfg.showRsi && series.rsi != null) {
            val rsiPaint = Paint().setupText(cfg.rsiTextPx, cfg.rsiColor, false)
            val rsiTxt = "RSI14: ${String.format("%.1f", series.rsi)}"
            val x = area.left                          // lijevo ispod grafa
            val baseline = (area.bottom + cfg.rsiOffsetBelowAreaPx)
                .coerceAtMost(cfg.heightPx - 2f)      // ne izađi iz slike
            c.drawText(rsiTxt, x, baseline, rsiPaint)
        }

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
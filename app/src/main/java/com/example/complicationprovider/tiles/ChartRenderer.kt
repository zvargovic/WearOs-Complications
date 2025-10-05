package com.example.complicationprovider.tiles

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Crta linijski graf:
 * - tamna pozadina
 * - zelena linija + točka na zadnjem uzorku
 * - isprekidana referentna crta (OPEN u 00:00) + labela s cijenom
 * - header (trenutna cijena, delta % i €)
 * - desno: Min/Max oznake (crvena/zelena) s auto-razmakom ako su preblizu
 *
 * Sve brojke, boje i margine su podešive preko [Config].
 * Render vraća PNG bajtove u zadanim px dimenzijama.
 */
object ChartRenderer {

    private const val TAG = "ChartRenderer"

    data class Config(
        // Dimenzije slike (px) – centralizirano preko ChartDims
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

        // Boje za Min/Max oznake (po defaultu: max = zelena linije, min = crvena)
        val maxColor: Int = 0xFF22C55E.toInt(),
        val minColor: Int = 0xFFE0524D.toInt(),

        // Linija i točka
        val strokePx: Float = 4f,
        val dotRadiusPx: Float = 6f,
        val smoothingT: Float = 0.5f, // Catmull-Rom napetost

        // Ref linija
        val refStrokePx: Float = 2f,
        val refDash: FloatArray = floatArrayOf(10f, 10f),

        // Padding unutar grafa
        val padL: Float = 130f,
        val padR: Float = 130f, // malo veći desno zbog min/max labela
        val padT: Float = 52f,  // mjesto za naslov/Δ
        val padB: Float = 20f,

        // Tipografija (px, jer crtamo direktno u Bitmap)
        val priceTextPx: Float = 28f,
        val deltaTextPx: Float = 16f,
        val sideTextPx: Float = 14f,
        val refLabelTextPx: Float = 14f,

        // Pozicije (offseti) labela
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

        // Minimalni vertikalni razmak između min i max labela (ako su preblizu)
        val minMaxMinGapPx: Float = 50f,

        // Debug: prikaži granice područja crtanja (area) kao bijeli pravokutnik 1px
        val showPlotBounds: Boolean = true,

        // Broj slotova (48 = svakih 30 min u danu)
        val slotsPerDay: Int = 48
    )

    data class Series(
        val open: Double?,
        val values: List<Double?>, // veličine slotsPerDay
        val min: Double?,
        val max: Double?,
        val lastPrice: Double?
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
                    "min=${series.min}, max=${series.max}"
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

        // --- DEBUG: nacrtaj granice area (bijeli pravokutnik 1px) ---
        if (cfg.showPlotBounds) {
            val dbg = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            c.drawRect(area, dbg)
        }

        // --- scale min/max ---
        val values = series.values
        val nonNull = values.filterNotNull()
        val minV = (series.min ?: nonNull.minOrNull()) ?: series.open ?: 0.0
        val maxV = (series.max ?: nonNull.maxOrNull()) ?: series.open ?: 1.0

        // Ako nema raspona, simetrični prozor oko OPEN-a (±1)
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

        // --- ref line (open) ---
        val refY = series.open?.let { yAt(it) }
        if (refY != null) {
            Log.d(TAG, "ref line @ y=$refY (open=${series.open})")
            val refPaint = Paint().apply {
                color = cfg.ref
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = cfg.refStrokePx
                pathEffect = DashPathEffect(cfg.refDash, 0f)
            }
            val p = Path().apply {
                moveTo(area.left - 30f, refY)      // pomak od lijevog ruba
                lineTo(area.right + 30f, refY)     // produžetak udesno (u padding)
            }
            c.drawPath(p, refPaint)

            // ref label (lijevo od linije)
            val txt = onFormatPrice(series.open!!)
            val tPaint = Paint().setupText(cfg.refLabelTextPx, cfg.axisText, false)
            c.drawText(
                txt,
                area.left + cfg.refLabelOffsetX,
                refY + cfg.refLabelOffsetY,
                tPaint
            )
        } else {
            Log.d(TAG, "ref line skipped (no open)")
        }

        // --- sparkline ---
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
                // Catmull–Rom approx (smooth)
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
        Log.d(TAG, "sparkline → started=$started, drawnPts=$drawnPts")
        if (started) c.drawPath(path, linePaint) else Log.d(TAG, "sparkline skipped (no points)")

        // --- last dot ---
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
            Log.d(TAG, "last dot @ index=$lastIdx x=%1.1f y=%1.1f value=%s".format(lastX, lastY, lastV))
        } else {
            Log.d(TAG, "last dot skipped (no non-null values)")
        }

        // === MIN / MAX OZNAKE NA DESNOM RUBU (obojano) ===
        run {
            val minVal = series.min
            val maxVal = series.max
            if (minVal != null || maxVal != null) {
                val tickPaintMin = Paint().apply {
                    color = cfg.minColor
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val tickPaintMax = Paint().apply {
                    color = cfg.maxColor
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                }
                val textPaintMin = Paint().setupText(cfg.sideTextPx, cfg.minColor, false)
                val textPaintMax = Paint().setupText(cfg.sideTextPx, cfg.maxColor, false)

                val labelX = area.right - 20f // pišemo u rezervirani desni padding
                val tickL = area.right - 6f
                val tickR = area.right

                var yMin = minVal?.let { yAt(it) }
                var yMax = maxVal?.let { yAt(it) }

                // Ako su preblizu, razmakni – pomakni MIN prema dolje
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

                Log.d(TAG, "min/max labels → min=$minVal@${yMin ?: "-"}  max=$maxVal@${yMax ?: "-"}")
            } else {
                Log.d(TAG, "min/max labels skipped (no values)")
            }
        }
        // === KRAJ MIN/MAX BLOKA ===

        // --- header texts ---
        series.lastPrice?.let { cur ->
            val pricePaint = Paint().setupText(cfg.priceTextPx, cfg.headerText, true)
            c.drawText(onFormatPrice(cur), cfg.headerX, cfg.headerY, pricePaint)

            series.open?.let { op ->
                val delta = cur - op
                val pct = if (op != 0.0) (delta / op) * 100.0 else 0.0
                val deltaPaint = Paint().setupText(
                    cfg.deltaTextPx,
                    if (delta >= 0) cfg.deltaPos else cfg.deltaNeg,
                    false
                )
                val deltaTxt = String.format(
                    "%+.2f%%   %s",
                    pct,
                    if (abs(delta) >= 1000) String.format("%,.2f", delta) else String.format("%.2f", delta)
                )
                c.drawText(deltaTxt, cfg.deltaX, cfg.deltaY, deltaPaint)
                Log.d(TAG, "header → cur=$cur, delta=$delta ($deltaTxt)")
            }
        } ?: Log.d(TAG, "header skipped (no lastPrice)")

        // encode PNG
        val bos = java.io.ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val out = bos.toByteArray()
        Log.d(TAG, "render done → pngSize=${out.size}B, compressed=$ok")
        return out
    }
}
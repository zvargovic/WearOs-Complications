package com.example.complicationprovider.tiles

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

object ChartRenderer {

    private const val TAG = "ChartRenderer"

    data class Config(
        val widthPx: Int = ChartDims.W_PX,
        val heightPx: Int = ChartDims.H_PX,

        // Boje
        val bg: Int = 0xFF000000.toInt(),
        val line: Int = 0xFF22C55E.toInt(),
        val ref: Int = 0xFFFFFFFF.toInt(),
        val headerText: Int = 0xFFFFFFFF.toInt(),
        val deltaPos: Int = 0xFF22C55E.toInt(),
        val deltaNeg: Int = 0xFFE0524D.toInt(),
        val axisText: Int = 0xFFBBBBBB.toInt(),

        val maxColor: Int = 0xFF22C55E.toInt(),
        val minColor: Int = 0xFFE0524D.toInt(),

        // Linija i točka
        val strokePx: Float = 5f,
        val dotRadiusPx: Float = 6f,
        val smoothingT: Float = 1.0f,

        // Ref linija
        val refStrokePx: Float = 1f,
        val refDash: FloatArray = floatArrayOf(5f, 5f),

        // Padding (NE DIRAMO)
        val padL: Float = 130f,
        val padR: Float = 130f,
        val padT: Float = 100f,
        val padB: Float = 100f,

        // Tipografija
        val priceTextPx: Float = 40f,
        val deltaTextPx: Float = 24f,
        val sideTextPx: Float = 20f,
        val refLabelTextPx: Float = 20f,

        // Pozicije naslova
        val headerX: Float = 230f,
        val headerY: Float = 30f,
        val deltaX: Float = 230f,
        val deltaY: Float = 60f,

        val refLabelOffsetX: Float = 10f,
        val refLabelOffsetY: Float = -6f,

        val minLabelOffsetX: Float = 0f,
        val minLabelOffsetY: Float = -4f,
        val maxLabelOffsetX: Float = 0f,
        val maxLabelOffsetY: Float = 18f,

        val minMaxMinGapPx: Float = 50f,

        val showPlotBounds: Boolean = false,

        val slotsPerDay: Int = 288,

        // RSI
        val showRsi: Boolean = true,
        val rsiPeriod: Int = 14,
        val rsiTextPx: Float = 30f,
        val rsiNeutral: Int = 0xFFBBBBBB.toInt(),
        val rsiBull: Int = 0xFF22C55E.toInt(),
        val rsiBear: Int = 0xFFE0524D.toInt(),
        val rsiOffsetX: Float = 125f,
        val rsiOffsetY: Float = 35f,

        val refLabelRightInsetPx: Float = 72f,

        // ===== NEW: “combo” zaglađivanje SAMO za crtanje linije =====
        val smoothMonotone: Boolean = true,
        val smoothMedianWindow: Int = 3,
        val resampleStepPx: Float = 2f
    )

    data class Series(
        val open: Double?,
        val values: List<Double?>,
        val min: Double?,
        val max: Double?,
        val lastPrice: Double?,
        val rsi: Double? = null,

        // NEW: indeks prvog današnjeg 5-min slota (0..slotsPerDay-1)
        // Npr. ako je [SNAP][OPEN] imao slot5=264 → firstSlotInDay=264
        val firstSlotInDay: Int? = null
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
                    "min=${series.min}, max=${series.max}, rsi=${series.rsi}, " +
                    "firstSlotInDay=${series.firstSlotInDay}"
        )

        val bmp = Bitmap.createBitmap(cfg.widthPx, cfg.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cfg.bg)

        val area = RectF(cfg.padL, cfg.padT, cfg.widthPx - cfg.padR, cfg.heightPx - cfg.padB)

        if (cfg.showPlotBounds) {
            val dbg = Paint().apply {
                color = Color.WHITE
                isAntiAlias = true
                style = Paint.Style.STROKE
                strokeWidth = 1f
            }
            c.drawRect(area, dbg)
        }

        // --- scale min/max (NE DIRAMO LOGIKU) ---
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
        }
        val extra = max((hi - lo) * 0.01, 0.25)
        lo -= extra; hi += extra
        val span = hi - lo

        // === NOVO: x prema slotu u danu ===
        val slotsPerDay = cfg.slotsPerDay.coerceAtLeast(1)
        val firstSlot = (series.firstSlotInDay ?: 0).coerceIn(0, slotsPerDay - 1)

        fun xAtSlot(slotInDay: Int): Float {
            // slotInDay ∈ [0, slotsPerDay-1]
            val t = slotInDay.toFloat() / (slotsPerDay - 1).coerceAtLeast(1)
            return area.left + t * area.width()
        }
        fun yAt(v: Double): Float {
            val t = ((v - lo) / span).toFloat()
            return area.bottom - t * area.height()
        }

        // --- ref line + label (kao prije) ---
        series.open?.let { op ->
            val refY = yAt(op)
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

            val txt = onFormatPrice(op)
            val tPaint = Paint().setupText(cfg.refLabelTextPx, cfg.axisText, false)
            val textW = tPaint.measureText(txt)
            val guard = 12f
            val firstIdx = series.values.indexOfFirst { it != null }
            val firstX = if (firstIdx >= 0) xAtSlot(firstSlot) else Float.POSITIVE_INFINITY
            val leftX = area.left + cfg.refLabelOffsetX
            val needsRight = firstX < (leftX + textW + guard)
            val labelX = if (needsRight) {
                (area.right - cfg.refLabelRightInsetPx - textW).coerceAtLeast(area.left + 4f)
            } else {
                leftX.coerceIn(area.left + 4f, area.right - 4f - textW)
            }
            c.drawText(txt, labelX, refY + cfg.refLabelOffsetY, tPaint)
        }
        // ------------------ DRAW SERIES (combo cijev) ------------------
        val linePaint = Paint().apply {
            color = cfg.line
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = cfg.strokePx
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        // 1) Skupi (x,y) kroz nenull vrijednosti; x prema slotu u danu
        val xyRaw = ArrayList<PointF>(values.size)
        var k = 0 // ordinal nenull točke unutar dana
        for (i in values.indices) {
            val v = values[i] ?: continue
            val slotInDay = (firstSlot + k) % slotsPerDay
            xyRaw.add(PointF(xAtSlot(slotInDay), yAt(v)))
            k++
        }
        val filledCount = k
        val lastSlotInDay = if (filledCount > 0) (firstSlot + filledCount - 1) % slotsPerDay else firstSlot

        // Ako nema dovoljno točaka, preskoči crtanje
        if (xyRaw.size >= 2) {
            // 2) (Opcionalno) median filter u Y (samo vizualno)
            val xyFiltered = if (cfg.smoothMedianWindow >= 3) {
                val w = if (cfg.smoothMedianWindow % 2 == 1) cfg.smoothMedianWindow else cfg.smoothMedianWindow + 1
                medianFilterPoints(xyRaw, w)
            } else xyRaw

            // 3) (Opcionalno) resampling po ekranu
            val xySampled =
                if (cfg.resampleStepPx > 0f) resampleByXStep(xyFiltered, cfg.resampleStepPx) else xyFiltered

            // 4) Monotoni Hermite Bezier ili Catmull-Rom
            val path =
                if (cfg.smoothMonotone && xySampled.size >= 2) buildMonotoneBezierPath(xySampled)
                else buildCatmullRomPath(xySampled, cfg.smoothingT)

            c.drawPath(path, linePaint)
        }

        // --- last dot (x prema lastSlotInDay) ---
        run {
            if (filledCount > 0) {
                val lastV = values.last { it != null }!!
                val lastX = xAtSlot(lastSlotInDay)
                val lastY = yAt(lastV)
                val dotPaint = Paint().apply {
                    color = cfg.line
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                c.drawCircle(lastX, lastY, cfg.dotRadiusPx, dotPaint)
            }
        }

        // --- Min/Max vodilice + tekst (kao prije) ---
        run {
            val minVal = series.min
            val maxVal = series.max
            if (minVal != null || maxVal != null) {
                val textPaintMin = Paint().setupText(cfg.sideTextPx, cfg.minColor, false)
                val textPaintMax = Paint().setupText(cfg.sideTextPx, cfg.maxColor, false)
                val guideDash = cfg.refDash

                val yMin = minVal?.let { yAt(it) }
                val yMax = maxVal?.let { yAt(it) }

                yMax?.let { yy ->
                    val guideMax = Paint().apply {
                        color = cfg.maxColor
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                        pathEffect = DashPathEffect(guideDash, 0f)
                    }
                    val pMax = Path().apply {
                        moveTo(area.left - 30f, yy); lineTo(area.right + 30f, yy)
                    }
                    c.drawPath(pMax, guideMax)
                    c.drawText(onFormatPrice(maxVal!!), area.right - 60f + cfg.maxLabelOffsetX, yy + cfg.maxLabelOffsetY, textPaintMax)
                }
                yMin?.let { yy ->
                    val guideMin = Paint().apply {
                        color = cfg.minColor
                        isAntiAlias = true
                        style = Paint.Style.STROKE
                        strokeWidth = 1f
                        pathEffect = DashPathEffect(guideDash, 0f)
                    }
                    val pMin = Path().apply {
                        moveTo(area.left - 30f, yy); lineTo(area.right + 30f, yy)
                    }
                    c.drawPath(pMin, guideMin)
                    c.drawText(onFormatPrice(minVal!!), area.right - 60f + cfg.minLabelOffsetX, yy + cfg.minLabelOffsetY, textPaintMin)
                }
            }
        }
        // --- header (kao prije) ---
        series.lastPrice?.let { cur ->
            val pricePaint = Paint().setupText(cfg.priceTextPx, cfg.headerText, true)
            c.drawText(onFormatPrice(cur), cfg.headerX, cfg.headerY, pricePaint)
            series.open?.let { op ->
                val delta = cur - op
                val pct = if (op != 0.0) (delta / op) * 100.0 else 0.0
                val deltaPaint = Paint().setupText(cfg.deltaTextPx, if (delta >= 0) cfg.deltaPos else cfg.deltaNeg, false)
                val deltaTxt = String.format(
                    "%+.2f%%   %s",
                    pct,
                    if (abs(delta) >= 1000) String.format("%,.2f", delta) else String.format("%.2f", delta)
                )
                c.drawText(deltaTxt, cfg.deltaX, cfg.deltaY, deltaPaint)
            }
        }

        // --- RSI (kao prije) ---
        if (cfg.showRsi) {
            val rsi = series.rsi
            val text = if (rsi != null) "RSI(${cfg.rsiPeriod}) ${String.format("%.1f", rsi)}" else "RSI(${cfg.rsiPeriod}) —"
            val color = when {
                rsi == null -> cfg.rsiNeutral
                rsi >= 70.0 -> cfg.rsiBear
                rsi <= 30.0 -> cfg.rsiBull
                rsi >= 50.0 -> cfg.rsiBull
                else -> cfg.rsiBear
            }
            val p = Paint().setupText(cfg.rsiTextPx, color, false)
            val x = area.left + cfg.rsiOffsetX
            val y = area.bottom + cfg.rsiOffsetY + cfg.rsiTextPx
            c.drawText(text, x, y, p)
        }

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    // ===== Helpers: Catmull-Rom =====
    private fun buildCatmullRomPath(points: List<PointF>, tension: Float): Path {
        val path = Path()
        if (points.isEmpty()) return path
        path.moveTo(points[0].x, points[0].y)
        if (points.size == 1) return path

        for (i in 0 until points.size - 1) {
            val p0 = points[max(0, i - 1)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[min(points.size - 1, i + 2)]

            val t = tension * 0.5f
            val cp1x = p1.x + (p2.x - p0.x) * t
            val cp1y = p1.y + (p2.y - p0.y) * t
            val cp2x = p2.x - (p3.x - p1.x) * t
            val cp2y = p2.y - (p3.y - p1.y) * t

            path.cubicTo(cp1x, cp1y, cp2x, cp2y, p2.x, p2.y)
        }
        return path
    }

    // ===== NEW: Median filter =====
    private fun medianFilterPoints(points: List<PointF>, window: Int): List<PointF> {
        if (points.size <= 2 || window < 3) return points
        val w = if (window % 2 == 1) window else window + 1
        val r = w / 2
        val out = ArrayList<PointF>(points.size)
        for (i in points.indices) {
            if (i < r || i >= points.size - r) {
                out.add(points[i])
            } else {
                val slice = points.subList(i - r, i + r + 1).map { it.y }.sorted()
                val med = slice[r]
                out.add(PointF(points[i].x, med))
            }
        }
        return out
    }

    // ===== NEW: Resampling po X-koraku =====
    private fun resampleByXStep(points: List<PointF>, stepPx: Float): List<PointF> {
        if (points.size <= 2 || stepPx <= 0f) return points
        val out = ArrayList<PointF>(points.size)
        var nextX = points.first().x
        var last = points.first()
        out.add(last)
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x >= nextX) {
                out.add(p)
                nextX = p.x + stepPx
            }
            last = p
        }
        if (out.last() !== last) out.add(last)
        return out
    }

    // ===== NEW: Monotoni kubični Hermite (Fritsch–Carlson) → Bezier =====
    private fun buildMonotoneBezierPath(points: List<PointF>): Path {
        val n = points.size
        val path = Path()
        if (n == 0) return path
        if (n == 1) {
            path.moveTo(points[0].x, points[0].y)
            return path
        }

        val x = FloatArray(n) { points[it].x }
        val y = FloatArray(n) { points[it].y }
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]).coerceAtLeast(1e-6f) }
        val m = FloatArray(n)

        m[0] = d[0]
        m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            if (d[i - 1] * d[i] <= 0f) {
                m[i] = 0f
            } else {
                m[i] = (d[i - 1] + d[i]) / 2f
            }
        }

        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
            } else {
                val a = m[i] / d[i]
                val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * d[i]
                    m[i + 1] = t * b * d[i]
                }
            }
        }

        path.moveTo(x[0], y[0])
        for (i in 0 until n - 1) {
            val h = x[i + 1] - x[i]
            val c1x = x[i] + h / 3f
            val c1y = y[i] + m[i] * h / 3f
            val c2x = x[i + 1] - h / 3f
            val c2y = y[i + 1] - m[i + 1] * h / 3f
            path.cubicTo(c1x, c1y, c2x, c2y, x[i + 1], y[i + 1])
        }
        return path
    }
}
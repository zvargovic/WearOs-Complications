package com.example.complicationprovider.tiles
import android.content.Context
import android.graphics.*
import android.util.Log
import com.example.complicationprovider.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.time.*
import android.text.format.DateUtils
import androidx.core.content.ContextCompat
import android.graphics.drawable.Drawable
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
        val deltaX: Float = 255f,
        val deltaY: Float = 65f,

        val refLabelOffsetX: Float = 10f,
        val refLabelOffsetY: Float = -6f,

        val minLabelOffsetX: Float = 0f,
        val minLabelOffsetY: Float = -4f,
        val maxLabelOffsetX: Float = 0f,
        val maxLabelOffsetY: Float = 18f,

        val minMaxMinGapPx: Float = 60f,

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

        // ===== “combo” zaglađivanje SAMO za crtanje linije =====
        val smoothMonotone: Boolean = true,
        val smoothMedianWindow: Int = 3,
        val resampleStepPx: Float = 2f,

        // ===== NOVO: Market status (autodetekcija vremena) =====
        val marketAutofillStatus: Boolean = true,
        val marketTzId: String = "Europe/Zagreb",
        val openHour: Int = 2, val openMinute: Int = 0,
        val closeHour: Int = 2, val closeMinute: Int = 0,
        val marketClosedStringResId: Int = R.string.market_closed_open_in, // "Opens in %s"
        val marketClosedFallbackTemplate: String = "Market closed — opens in %s",

        // Render opcije za natpis (market status)
        val marketTextPx: Float = 24f,
        val marketTextColor: Int = 0xFFFF6600.toInt(),
        val marketTextBold: Boolean = true,
        val marketTextOffsetX: Float = 2f,
        val marketTextOffsetY: Float = -60f,
        val marketTextMarginPx: Float = -1000f,
        val marketTextStickBottom: Boolean = false,
        val marketTextBottomMarginPx: Float = 24f,
        val marketTextBgColor: Int = 0x66000000,
        val marketTextBgPadPx: Float = 8f,
        val marketTextShadow: Boolean = true,
        val closedTextGapPx: Float = 12f   // razmak od donjeg ruba ikone do VRHA teksta
    )

    data class Series(
        val open: Double?,
        val values: List<Double?>,
        val min: Double?,
        val max: Double?,
        val lastPrice: Double?,
        val rsi: Double? = null,
        val firstSlotInDay: Int? = null,
        val isMarketOpen: Boolean? = null,
        val marketStatusText: String? = null
    )

    private fun Paint.setupText(sizePx: Float, color: Int, isBold: Boolean = false) = apply {
        reset()
        this.color = color
        this.textSize = sizePx
        this.isAntiAlias = true
        this.typeface = if (isBold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    // ===== NOVO: lokalno vrijeme tržišta =====
    private fun isWeekday(d: LocalDate): Boolean = d.dayOfWeek.value in 1..5
    private fun sessionStartLocal(d: LocalDate, zone: ZoneId, openH: Int, openM: Int): ZonedDateTime =
        ZonedDateTime.of(d, LocalTime.of(openH, openM), zone)
    private fun sessionEndLocal(d: LocalDate, zone: ZoneId, closeH: Int, closeM: Int): ZonedDateTime =
        ZonedDateTime.of(d.plusDays(1), LocalTime.of(closeH, closeM), zone)

    private fun isMarketOpenNowLocal(
        now: ZonedDateTime,
        zone: ZoneId,
        openH: Int, openM: Int,
        closeH: Int, closeM: Int
    ): Boolean {
        val today = now.toLocalDate()
        val yday = today.minusDays(1)
        if (isWeekday(yday)) {
            val s = sessionStartLocal(yday, zone, openH, openM)
            val e = sessionEndLocal(yday, zone, closeH, closeM)
            if (!now.isBefore(s) && now.isBefore(e)) return true
        }
        if (isWeekday(today)) {
            val s = sessionStartLocal(today, zone, openH, openM)
            val e = sessionEndLocal(today, zone, closeH, closeM)
            if (!now.isBefore(s) && now.isBefore(e)) return true
        }
        return false
    }

    private fun nextOpenLocalMs(now: ZonedDateTime, zone: ZoneId, openH: Int, openM: Int): Long {
        var d = now.toLocalDate()
        while (true) {
            if (isWeekday(d)) {
                val s = sessionStartLocal(d, zone, openH, openM)
                if (now.isBefore(s)) return s.toInstant().toEpochMilli()
            }
            d = d.plusDays(1)
        }
    }

    private fun nextCloseLocalMs(
        now: ZonedDateTime,
        zone: ZoneId,
        openH: Int, openM: Int,
        closeH: Int, closeM: Int
    ): Long {
        val today = now.toLocalDate()
        val yday = today.minusDays(1)
        if (isWeekday(yday)) {
            val sY = sessionStartLocal(yday, zone, openH, openM)
            val eY = sessionEndLocal(yday, zone, closeH, closeM)
            if (!now.isBefore(sY) && now.isBefore(eY)) return eY.toInstant().toEpochMilli()
        }
        val eT = sessionEndLocal(today, zone, closeH, closeM)
        return eT.toInstant().toEpochMilli()
    }

    private fun lastCloseLocalMs(
        now: ZonedDateTime,
        zone: ZoneId,
        openH: Int, openM: Int,
        closeH: Int, closeM: Int
    ): Long {
        var d = now.toLocalDate()
        if (now.isBefore(sessionStartLocal(d, zone, openH, openM))) d = d.minusDays(1)
        while (!isWeekday(d)) d = d.minusDays(1)
        return sessionEndLocal(d, zone, closeH, closeM).toInstant().toEpochMilli()
    }
    fun renderPNG(
        context: Context,
        cfg: Config,
        series: Series,
        onFormatPrice: (Double) -> String = { v ->
            if (abs(v) >= 1000) String.format("%,.2f", v) else String.format("%.2f", v)
        }
    ): ByteArray {
        val zone = runCatching { ZoneId.of(cfg.marketTzId) }.getOrElse { ZoneId.systemDefault() }
        val nowLocal = ZonedDateTime.now(zone)

        // Autodetekcija statusa (isti algoritam kao komplikacija)
        val autoOpen = isMarketOpenNowLocal(
            now = nowLocal,
            zone = zone,
            openH = cfg.openHour, openM = cfg.openMinute,
            closeH = cfg.closeHour, closeM = cfg.closeMinute
        )
        val effectiveIsOpen = series.isMarketOpen ?: (if (cfg.marketAutofillStatus) autoOpen else true)

        val autoStatusText: String? =
            if (!effectiveIsOpen && cfg.marketAutofillStatus) {
                val nextOpenMs = nextOpenLocalMs(nowLocal, zone, cfg.openHour, cfg.openMinute)
                val deltaMs = nextOpenMs - System.currentTimeMillis()
                val deltaHuman = humanTimeTo(deltaMs)
                runCatching {
                    context.getString(cfg.marketClosedStringResId, deltaHuman)
                }.getOrElse {
                    String.format(cfg.marketClosedFallbackTemplate, deltaHuman)
                }
            } else null
        val statusToDraw = series.marketStatusText ?: autoStatusText

        Log.d(
            TAG,
            "render start → size=${cfg.widthPx}x${cfg.heightPx}, " +
                    "points=${series.values.count { it != null }}, " +
                    "open=${series.open}, last=${series.lastPrice}, " +
                    "min=${series.min}, max=${series.max}, rsi=${series.rsi}, " +
                    "firstSlotInDay=${series.firstSlotInDay}, " +
                    "isOpen(effective)=$effectiveIsOpen, status='$statusToDraw'"
        )

        val bmp = Bitmap.createBitmap(cfg.widthPx, cfg.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cfg.bg)
        val area = RectF(cfg.padL, cfg.padT, cfg.widthPx - cfg.padR, cfg.heightPx - cfg.padB)

        if (cfg.showPlotBounds) {
            val dbg = Paint().apply {
                color = Color.WHITE; isAntiAlias = true
                style = Paint.Style.STROKE; strokeWidth = 1f
            }
            c.drawRect(area, dbg)
        }

        // Ako je zatvoreno, i status postoji → nacrtaj NATPIS u grafu (kao prije), ali i dalje renderiramo graf.
        // (Stvarni “no-chart” closed izgled radi nova funkcija renderClosedPNG; vidi dolje.)
        // --- scale min/max ---
        val values = series.values
        val nonNull = values.filterNotNull()
        val minV = (series.min ?: nonNull.minOrNull()) ?: series.open ?: 0.0
        val maxV = (series.max ?: nonNull.maxOrNull()) ?: series.open ?: 1.0
        var lo = min(minV, series.open ?: minV)
        var hi = max(maxV, series.open ?: maxV)
        if (hi <= lo) { val op = series.open ?: 0.0; lo = op - 1.0; hi = op + 1.0 }
        val extra = max((hi - lo) * 0.01, 0.25); lo -= extra; hi += extra
        val span = hi - lo

        // === x/y helperi ===
        val slotsPerDay = cfg.slotsPerDay.coerceAtLeast(1)
        val firstSlot = (series.firstSlotInDay ?: 0).coerceIn(0, slotsPerDay - 1)
        fun xAtSlot(slotInDay: Int): Float {
            val t = slotInDay.toFloat() / (slotsPerDay - 1).coerceAtLeast(1)
            return area.left + t * area.width()
        }
        fun yAt(v: Double): Float {
            val t = ((v - lo) / span).toFloat()
            return area.bottom - t * area.height()
        }

        val yMinVal: Float? = series.min?.let { yAt(it) }
        val yMaxVal: Float? = series.max?.let { yAt(it) }
        val refY: Float? = series.open?.let { yAt(it) }

        // --- REF linija + label ---
        series.open?.let { op ->
            val refPaint = Paint().apply {
                color = cfg.ref; isAntiAlias = true
                style = Paint.Style.STROKE; strokeWidth = cfg.refStrokePx
                pathEffect = DashPathEffect(cfg.refDash, 0f)
            }
            val py = Path().apply { moveTo(area.left - 30f, refY!!); lineTo(area.right + 30f, refY) }
            c.drawPath(py, refPaint)

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
            refY?.let { c.drawText(txt, labelX, it + cfg.refLabelOffsetY, tPaint) }
        }

        // ------------------ DRAW SERIES ------------------
        val linePaint = Paint().apply {
            color = cfg.line; isAntiAlias = true
            style = Paint.Style.STROKE; strokeWidth = cfg.strokePx
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }

        val xyRaw = ArrayList<PointF>(values.size)
        var k = 0
        for (i in values.indices) {
            val v = values[i] ?: continue
            val slotInDay = (firstSlot + k) % slotsPerDay
            xyRaw.add(PointF(xAtSlot(slotInDay), yAt(v)))
            k++
        }
        val filledCount = k
        val lastSlotInDay = if (filledCount > 0) (firstSlot + filledCount - 1) % slotsPerDay else firstSlot

        // Path
        if (xyRaw.size >= 2) {
            val xyFiltered =
                if (cfg.smoothMedianWindow >= 3) {
                    val w = if (cfg.smoothMedianWindow % 2 == 1)
                        cfg.smoothMedianWindow else cfg.smoothMedianWindow + 1
                    medianFilterPoints(xyRaw, w)
                } else xyRaw

            val xySampled =
                if (cfg.resampleStepPx > 0f)
                    resampleByXStep(xyFiltered, cfg.resampleStepPx) else xyFiltered

            val path =
                if (cfg.smoothMonotone && xySampled.size >= 2)
                    buildMonotoneBezierPath(xySampled)
                else
                    buildCatmullRomPath(xySampled, cfg.smoothingT)

            c.drawPath(path, linePaint)
        }

// Zadnja stvarna točka iz serije (standardna dot)
        val lastVInSeries = values.lastOrNull { it != null }
        if (filledCount > 0 && lastVInSeries != null) {
            val lastX = xAtSlot(lastSlotInDay)
            val lastY = yAt(lastVInSeries)
            val dotPaint = Paint().apply { color = cfg.line; isAntiAlias = true; style = Paint.Style.FILL }
            c.drawCircle(lastX, lastY, cfg.dotRadiusPx, dotPaint)
        }

// "Ghost" točka na desnom rubu ako lastPrice (gLast) != zadnjem sampleu
        series.lastPrice?.let { lp ->
            val eps = 1e-6
            val needsGhost = (lastVInSeries == null) || kotlin.math.abs(lp - lastVInSeries) > eps
            if (needsGhost) {
                val ghostPaint = Paint().apply { color = cfg.line; isAntiAlias = true; style = Paint.Style.FILL }
                val ghostX = area.right
                val ghostY = yAt(lp)
                c.drawCircle(ghostX, ghostY, cfg.dotRadiusPx, ghostPaint)
            }
        }
// --- Min/Max vodilice + tekst --- (ostatak koda ostaje)
        run {
            val minVal = series.min
            val maxVal = series.max
            if (minVal != null || maxVal != null) {

                val tMin = Paint().setupText(cfg.sideTextPx, cfg.minColor, false)
                val tMax = Paint().setupText(cfg.sideTextPx, cfg.maxColor, false)
                val guideDash = cfg.refDash

                val yMin = minVal?.let { yAt(it) }
                val yMax = maxVal?.let { yAt(it) }

                val epsilonPx = 0.75f
                if (yMin != null && yMax != null && kotlin.math.abs(yMax - yMin) < epsilonPx) {
                    val y = (yMin + yMax) * 0.5f
                    val guide = Paint().apply {
                        color = cfg.maxColor; isAntiAlias = true
                        style = Paint.Style.STROKE; strokeWidth = 1f
                        pathEffect = DashPathEffect(guideDash, 0f)
                    }
                    val p = Path().apply { moveTo(area.left - 30f, y); lineTo(area.right + 30f, y) }
                    c.drawPath(p, guide)
                    val vToShow = maxVal ?: minVal!!
                    c.drawText(onFormatPrice(vToShow), area.right - 60f + cfg.maxLabelOffsetX, y + cfg.maxLabelOffsetY, tMax)
                } else {
                    val minGapPx = cfg.minMaxMinGapPx
                    // MAX
                    yMax?.let { yy ->
                        val guideMax = Paint().apply {
                            color = cfg.maxColor; isAntiAlias = true
                            style = Paint.Style.STROKE; strokeWidth = 1f
                            pathEffect = DashPathEffect(guideDash, 0f)
                        }
                        val pMax = Path().apply { moveTo(area.left - 30f, yy); lineTo(area.right + 30f, yy) }
                        c.drawPath(pMax, guideMax)

                        var yLbl = yy + cfg.maxLabelOffsetY
                        if (yMin != null) {
                            val dy = kotlin.math.abs(yy - yMin)
                            if (dy < minGapPx) {
                                val push = (minGapPx - dy) / 2f
                                yLbl = (yy - push) + cfg.maxLabelOffsetY
                            }
                        }
                        val xLbl = area.right - 60f + cfg.maxLabelOffsetX
                        c.drawText(onFormatPrice(maxVal!!), xLbl, yLbl, tMax)
                    }
                    // MIN
                    yMin?.let { yy ->
                        val guideMin = Paint().apply {
                            color = cfg.minColor; isAntiAlias = true
                            style = Paint.Style.STROKE; strokeWidth = 1f
                            pathEffect = DashPathEffect(guideDash, 0f)
                        }
                        val pMin = Path().apply { moveTo(area.left - 30f, yy); lineTo(area.right + 30f, yy) }
                        c.drawPath(pMin, guideMin)

                        var yLbl = yy + cfg.minLabelOffsetY
                        if (yMax != null) {
                            val dy = kotlin.math.abs(yMax - yy)
                            if (dy < minGapPx) {
                                val push = (minGapPx - dy) / 2f
                                yLbl = (yy + push) + cfg.minLabelOffsetY
                            }
                        }
                        val xLbl = area.right - 60f + cfg.minLabelOffsetX
                        c.drawText(onFormatPrice(minVal!!), xLbl, yLbl, tMin)
                    }
                }
            }
        }

        // --- header (cijena + delta) ---
        series.lastPrice?.let { cur ->
            val pricePaint = Paint().setupText(cfg.priceTextPx, cfg.headerText, true)
            c.drawText(onFormatPrice(cur), cfg.headerX, cfg.headerY, pricePaint)
            series.open?.let { op ->
                val delta = cur - op
                val pct = if (op != 0.0) (delta / op) * 100.0 else 0.0
                val deltaPaint = Paint().setupText(cfg.deltaTextPx, if (delta >= 0) cfg.deltaPos else cfg.deltaNeg, false)
                val deltaTxt = String.format("%+.2f%%   %s", pct,
                    if (abs(delta) >= 1000) String.format("%,.2f", delta) else String.format("%.2f", delta))
                c.drawText(deltaTxt, cfg.deltaX, cfg.deltaY, deltaPaint)
            }
        }

        // --- RSI ---
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

        // --- (legacy) market status u grafu, ako želiš ostaviti overlay teksta ---
        if (!effectiveIsOpen && !statusToDraw.isNullOrBlank()) {
            val text = statusToDraw!!

            val tp = Paint().setupText(cfg.marketTextPx, cfg.marketTextColor, cfg.marketTextBold).apply {
                textAlign = Paint.Align.LEFT
                if (cfg.marketTextShadow) setShadowLayer(3f, 0f, 0f, 0x80000000.toInt())
            }

            val cx = (cfg.widthPx - cfg.padL - cfg.padR) / 2f + cfg.padL + cfg.marketTextOffsetX
            val cy = (cfg.heightPx - cfg.padT - cfg.padB) / 2f + cfg.padT + cfg.marketTextOffsetY

            val fm = tp.fontMetrics
            val textW = tp.measureText(text)
            val pad = cfg.marketTextBgPadPx

            val left = cx - textW / 2f - pad
            val right = cx + textW / 2f + pad
            val top = cy + fm.ascent - pad
            val bottom = cy + fm.descent + pad

            val bgPaint = Paint().apply { color = cfg.marketTextBgColor; isAntiAlias = true; style = Paint.Style.FILL }
            c.drawRoundRect(RectF(left, top, right, bottom), 10f, 10f, bgPaint)

            val xLeft = left + pad
            c.drawText(text, xLeft, cy, tp)

            Log.d(TAG, "marketText draw xLeft=$xLeft, cy=$cy")
        }

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    /** ======================== NOVO: CLOSED LOOK (bez grafa) ======================== */
    // === POTPUNO ZAMJENI POSTOJEĆU FUNKCIJU renderClosedPNG OVIM KODOM ===
    fun renderClosedPNG(
        context: Context,
        cfg: Config,
        iconResId: Int,
        statusTextOverride: String? = null
    ): ByteArray {
        val zone = runCatching { ZoneId.of(cfg.marketTzId) }.getOrElse { ZoneId.systemDefault() }
        val now = ZonedDateTime.now(zone)
        val nextOpenMs = nextOpenLocalMs(now, zone, cfg.openHour, cfg.openMinute)
        val deltaHuman = humanTimeTo(nextOpenMs - System.currentTimeMillis())

        val statusText = statusTextOverride ?: runCatching {
            context.getString(cfg.marketClosedStringResId, deltaHuman)
        }.getOrElse {
            String.format(cfg.marketClosedFallbackTemplate, deltaHuman)
        }

        val bmp = Bitmap.createBitmap(cfg.widthPx, cfg.heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(cfg.bg)

        // --- IKONA U CENTRU + TEKST ISPOD IKONE ---
        val drawable = ContextCompat.getDrawable(context, iconResId)
        if (drawable != null) {
            // centar canvasa (za luk)
            val cxCanvas = cfg.widthPx / 2f
            val cyCanvas = cfg.heightPx / 2f

            // centar ikone (blago podignut)
            val cxIcon = cxCanvas
            val cyIcon = cyCanvas - (cfg.heightPx * 0.01f)
            val iconSize = (min(cfg.widthPx, cfg.heightPx) * 0.60f).toInt()

            // crtanje ikone
            val left   = (cxIcon - iconSize / 2f).toInt()
            val top    = (cyIcon - iconSize / 2f).toInt()
            val right  = (cxIcon + iconSize / 2f).toInt()
            val bottom = (cyIcon + iconSize / 2f).toInt()
            drawable.setBounds(left, top, right, bottom)
            drawable.alpha = 255
            drawable.draw(c)

            // --- STATUS TEKST NA DOLJNJEM LUKU (mirror) ---
            val text = statusText
            val tp = Paint().setupText(cfg.marketTextPx, cfg.marketTextColor, cfg.marketTextBold).apply {
                textAlign = Paint.Align.LEFT
                if (cfg.marketTextShadow) setShadowLayer(3f, 0f, 0f, 0x80000000.toInt())
                letterSpacing = 0f
            }

            val inset = 40f
            val r = (min(cfg.widthPx, cfg.heightPx) / 2f) - inset

            val textW = tp.measureText(text)
            val padPx = 40f
            val desiredSweepDeg = Math.toDegrees(((textW + padPx) / r).toDouble()).toFloat()

            // NEGATIVAN sweep = “zrcalno”, i centrirano oko 270°
            val sweepDeg = -desiredSweepDeg.coerceIn(120f, 210f)
            val startDeg = 90f + (kotlin.math.abs(sweepDeg) / 2f)

            val arcRect = RectF(cxCanvas - r, cyCanvas - r, cxCanvas + r, cyCanvas + r)
            val arc = Path().apply { addArc(arcRect, startDeg, sweepDeg) }

            val arcLen = (Math.toRadians(kotlin.math.abs(sweepDeg).toDouble()) * r).toFloat()
            val hOffset = ((arcLen - textW) / 2f).coerceAtLeast(0f)
            val fm = tp.fontMetrics
            val vOffset = -fm.ascent + 2f

            c.drawTextOnPath(text, arc, hOffset, vOffset, tp)
        }

        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun humanTimeTo(deltaMs: Long): String {
        val d = kotlin.math.max(0L, deltaMs)
        val days = d / DateUtils.DAY_IN_MILLIS
        val h = (d % DateUtils.DAY_IN_MILLIS) / DateUtils.HOUR_IN_MILLIS
        val m = (d % DateUtils.HOUR_IN_MILLIS) / DateUtils.MINUTE_IN_MILLIS
        return when {
            days > 0 -> "${days}d ${h}h"
            h > 0    -> "${h}h ${m}m"
            else     -> "${m}m"
        }
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

    // ===== Median filter =====
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

    // ===== Resampling po X-koraku =====
    private fun resampleByXStep(points: List<PointF>, stepPx: Float): List<PointF> {
        if (points.size <= 2 || stepPx <= 0f) return points
        val out = ArrayList<PointF>(points.size)
        var nextX = points.first().x
        var last = points.first()
        out.add(last)
        for (i in 1 until points.size) {
            val p = points[i]
            if (p.x >= nextX) { out.add(p); nextX = p.x + stepPx }
            last = p
        }
        if (out.last() !== last) out.add(last)
        return out
    }

    // ===== Monotoni kubični Hermite (Fritsch–Carlson) → Bezier =====
    private fun buildMonotoneBezierPath(points: List<PointF>): Path {
        val n = points.size
        val path = Path()
        if (n == 0) return path
        if (n == 1) { path.moveTo(points[0].x, points[0].y); return path }

        val x = FloatArray(n) { points[it].x }
        val y = FloatArray(n) { points[it].y }
        val d = FloatArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]).coerceAtLeast(1e-6f) }
        val m = FloatArray(n)

        m[0] = d[0]; m[n - 1] = d[n - 2]
        for (i in 1 until n - 1) {
            m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
        for (i in 0 until n - 1) {
            if (d[i] == 0f) { m[i] = 0f; m[i + 1] = 0f } else {
                val a = m[i] / d[i]; val b = m[i + 1] / d[i]
                val s = a * a + b * b
                if (s > 9f) {
                    val t = 3f / kotlin.math.sqrt(s)
                    m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]
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
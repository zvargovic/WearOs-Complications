package com.example.complicationprovider.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * Crta pomoćne PNG-ove u točnim *piksel* dimenzijama.
 */
object ChartRenderer {

    // ==================== POSTOJEĆE FUNKCIJE (ostavljene) ====================

    /** Osnovni okvir. */
    fun renderRectPng(
        widthPx: Int,
        heightPx: Int,
        rectWpx: Int,
        rectHpx: Int,
        strokePx: Float = 1f,
        color: Int = 0xFFFF7A00.toInt()
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            this.color = color
        }
        c.drawRect(left, top, right, bottom, p)

        return toPng(bmp)
    }

    /** Okvir + grid trake. */
    fun renderRectWithGridPng(
        widthPx: Int,
        heightPx: Int,
        rectWpx: Int,
        rectHpx: Int,
        strokePx: Float = 1f,
        rectColor: Int = 0xFFFF7A00.toInt(),
        gridHeightPx: Int = 20,
        gridGapPx: Int = 20,
        gridColor: Int = 0x553F2A00.toInt()
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = gridColor
        }
        var y = top
        repeat(6) {
            val bandBottom = (y + gridHeightPx).coerceAtMost(bottom)
            if (bandBottom > y) c.drawRect(left, y, right, bandBottom, fill)
            y = (bandBottom + gridGapPx).coerceAtMost(bottom)
        }

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            color = rectColor
        }
        c.drawRect(left, top, right, bottom, stroke)

        return toPng(bmp)
    }

    /**
     * Okvir + grid + donja skala s jednom horizont. linijom, 5 kratkih okomitih crtica i oznakama ispod.
     */
    fun renderRectWithGridAndScalePng(
        widthPx: Int,
        heightPx: Int,
        rectWpx: Int,
        rectHpx: Int,
        strokePx: Float = 1f,
        rectColor: Int = 0xFFFF7A00.toInt(),
        gridHeightPx: Int = 20,
        gridGapPx: Int = 20,
        gridColor: Int = 0x553F2A00.toInt(),
        // skala
        scaleYFromRectTopPx: Int = 88,
        scaleMainStrokePx: Float = 1f,
        scaleColor: Int = 0xFFFF7A00.toInt(),
        scaleTicks: Int = 5,
        scaleTickLenPx: Int = 5,
        scaleTickStrokePx: Float = 1f,
        labels: List<String> = listOf("0", "6", "12", "18", "(h)"),
        labelTextSizePx: Float = 12f,
        labelColor: Int = 0xFFFFFFFF.toInt(),
        labelGapYPx: Int = 2,
        edgeLabelInsetPx: Int = 6
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        // grid
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = gridColor
        }
        var y = top
        repeat(6) {
            val bandBottom = (y + gridHeightPx).coerceAtMost(bottom)
            if (bandBottom > y) c.drawRect(left, y, right, bandBottom, fill)
            y = (bandBottom + gridGapPx).coerceAtMost(bottom)
        }

        // glavna linija skale
        val scaleY = (top + scaleYFromRectTopPx).coerceIn(top, bottom)
        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scaleColor
            strokeWidth = scaleMainStrokePx
            style = Paint.Style.STROKE
        }
        c.drawLine(left, scaleY, right, scaleY, main)

        // crtice
        val ticks = max(2, scaleTicks)
        val dx = (rectWpx.toFloat() / (ticks - 1).toFloat())
        val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scaleColor
            strokeWidth = scaleTickStrokePx
            style = Paint.Style.STROKE
        }
        for (i in 0 until ticks) {
            val x = left + i * dx
            c.drawLine(x, scaleY, x, (scaleY + scaleTickLenPx), tickP)
        }

        // labels
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = labelTextSizePx
        }
        val bounds = Rect()
        for (i in labels.indices) {
            val lbl = labels[i]
            textP.getTextBounds(lbl, 0, lbl.length, bounds)
            val xBase = left + i * dx
            val x = when (i) {
                0 -> xBase + edgeLabelInsetPx
                labels.lastIndex -> xBase - edgeLabelInsetPx - bounds.width()
                else -> xBase - bounds.width() / 2f
            }
            val yLab = scaleY + scaleTickLenPx + labelGapYPx + bounds.height()
            c.drawText(lbl, x, yLab, textP)
        }

        // okvir
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            color = rectColor
        }
        c.drawRect(left, top, right, bottom, stroke)

        return toPng(bmp)
    }

    // ==================== NOVO: BARS ====================

    /**
     * Okvir + grid + skala + 48 barova (slot = 30 min).
     */
    fun renderRectWithGridScaleAndBarsPng(
        widthPx: Int,
        heightPx: Int,
        rectWpx: Int,
        rectHpx: Int,
        strokePx: Float = 1f,
        rectColor: Int = 0xFFFF7A00.toInt(),
        // grid
        gridHeightPx: Int = 20,
        gridGapPx: Int = 20,
        gridColor: Int = 0x553F2A00.toInt(),
        // skala
        scaleYFromRectTopPx: Int = 88,
        scaleMainStrokePx: Float = 1f,
        scaleColor: Int = 0xFFFF7A00.toInt(),
        scaleTicks: Int = 5,
        scaleTickLenPx: Int = 5,
        scaleTickStrokePx: Float = 1f,
        labels: List<String> = listOf("0", "6", "12", "18", "(h)"),
        labelTextSizePx: Float = 12f,
        labelColor: Int = 0xFFFFFFFF.toInt(),
        labelGapYPx: Int = 2,
        edgeLabelInsetPx: Int = 6,
        // barovi
        series: List<Double?> = emptyList(),
        dayMax: Double? = null,
        barAlphaFull: Int = 0xFF,
        barAlphaStub: Int = 0x55,
        barColorRgb: Int = 0x3F2A00,
        barMinPx: Int = 2,
        barStubPx: Int = 2,
        // razmak između linije skale i dna bara
        barBaselineGapPx: Float = 1f
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        // GRID
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = gridColor
        }
        var y = top
        repeat(6) {
            val bandBottom = (y + gridHeightPx).coerceAtMost(bottom)
            if (bandBottom > y) c.drawRect(left, y, right, bandBottom, fill)
            y = (bandBottom + gridGapPx).coerceAtMost(bottom)
        }

        // SKALA
        val scaleY = (top + scaleYFromRectTopPx).coerceIn(top, bottom)
        val main = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scaleColor
            strokeWidth = scaleMainStrokePx
            style = Paint.Style.STROKE
        }
        c.drawLine(left, scaleY, right, scaleY, main)

        val ticks = max(2, scaleTicks)
        val dxTick = (rectWpx.toFloat() / (ticks - 1).toFloat())
        val tickP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = scaleColor
            strokeWidth = scaleTickStrokePx
            style = Paint.Style.STROKE
        }
        for (i in 0 until ticks) {
            val xTick = left + i * dxTick
            c.drawLine(xTick, scaleY, xTick, (scaleY + scaleTickLenPx), tickP)
        }

        // labels – JEDNA zajednička bazna linija (da sve budu poravnate)
        val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = labelTextSizePx
        }
        val fm = textP.fontMetrics
        val baseLine = scaleY + scaleTickLenPx + labelGapYPx - fm.ascent

        val bounds = Rect()
        for (i in labels.indices) {
            val lbl = labels[i]
            textP.getTextBounds(lbl, 0, lbl.length, bounds)

            val xBase = left + i * dxTick
            val x = when (i) {
                0 -> xBase + edgeLabelInsetPx
                labels.lastIndex -> xBase - edgeLabelInsetPx - bounds.width()
                else -> xBase - bounds.width() / 2f
            }
            c.drawText(lbl, x, baseLine, textP)
        }

        // BAROVI – baza na liniji skale
        val slotW = rectWpx.toFloat() / 48f
        val barW = max(1f, slotW * 0.6f)
        val halfBar = barW / 2f
        val usableTop = top + 1f
        val usableBottom = (scaleY - barBaselineGapPx).coerceAtLeast(usableTop + 1f)
        val usableHeight = (usableBottom - usableTop).coerceAtLeast(1f)

        val maxVal = (dayMax ?: series.filterNotNull().maxOrNull()) ?: 0.0
        val fullColor = (barAlphaFull shl 24) or barColorRgb
        val stubColor = (barAlphaStub shl 24) or barColorRgb

        val pBar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until 48) {
            val v = if (i < series.size) series[i] else null
            val cx = left + (i + 0.5f) * slotW
            if (v != null && maxVal > 0.0) {
                val norm = (v / maxVal).coerceIn(0.0, 1.0).toFloat()
                val hBar = max(barMinPx.toFloat(), norm * usableHeight)
                pBar.color = fullColor
                c.drawRect(cx - halfBar, usableBottom - hBar, cx + halfBar, usableBottom, pBar)
            } else {
                pBar.color = stubColor
                val hBar = barStubPx.toFloat()
                c.drawRect(cx - halfBar, usableBottom - hBar, cx + halfBar, usableBottom, pBar)
            }
        }

        // OKVIR
        val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            color = rectColor
        }
        c.drawRect(left, top, right, bottom, strokeP)

        return toPng(bmp)
    }

    // =======================================================================

    private fun toPng(bmp: Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
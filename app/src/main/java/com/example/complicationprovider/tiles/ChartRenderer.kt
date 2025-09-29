package com.example.complicationprovider.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Crta pomoćne PNG-ove u točnim *piksel* dimenzijama.
 */
object ChartRenderer {

    /**
     * Minimalni okvir/pravokutnik u centru canvasa.
     */
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

    /**
     * Okvir + horizontalne *grid* trake unutar pravokutnika (broj traka se određuje
     * s visinom trake i razmakom; crtaju se od vrha prema dnu dok ima mjesta).
     */
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
        while (y < bottom) {
            val bandBottom = min(bottom, y + gridHeightPx)
            if (bandBottom > y) c.drawRect(left, y, right, bandBottom, fill)
            y = min(bottom, bandBottom + gridGapPx)
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
     * Okvir + grid + DONJA SKALA (glavna horizontalna linija, okomite crtice i brojevi).
     *
     * [scaleY] je Y unutar pravokutnika (ishodište = GORNJI LIJEVI KUT pravokutnika).
     * Sve dimenzije/boje su u *px* ARGB i lako podesive.
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
        // --- skala ---
        scaleY: Int = 88,
        scaleColor: Int = 0xFFFF7A00.toInt(),
        tickCount: Int = 5,
        tickLenPx: Int = 5,
        textSizePx: Float = 12f,
        textColor: Int = 0xFFFFFFFF.toInt(),
        textGapV: Int = 2
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        // Pravokutnik centriran
        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        // 1) GRID (trake)
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = gridColor
        }
        var y = top
        while (y < bottom) {
            val bandBottom = min(bottom, y + gridHeightPx)
            if (bandBottom > y) c.drawRect(left, y, right, bandBottom, gridPaint)
            y = min(bottom, bandBottom + gridGapPx)
        }

        // 2) Okvir
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            strokeCap = Paint.Cap.BUTT
            strokeJoin = Paint.Join.MITER
            color = rectColor
        }
        c.drawRect(left, top, right, bottom, frame)

        // 3) SKALA: glavna linija, crtice, tekst
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, scale(1f))
            color = scaleColor
        }
        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, scale(1f))
            color = scaleColor
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textSize = textSizePx
            color = textColor
            textAlign = Paint.Align.CENTER
        }

        // Y pozicija skale (unutar recta!), poravnata na 0.5 za oštru 1px liniju
        val yLine = clamp(top, bottom, top + scaleY.toFloat())
        val crispY = round(yLine) + 0.5f

        // Glavna linija
        c.drawLine(left, crispY, right, crispY, linePaint)

        // Ticks
        val n = max(2, tickCount)
        val dx = (right - left) / (n - 1)
        val tickTop = crispY - tickLenPx
        val tickBottom = crispY

        for (i in 0 until n) {
            val x = left + i * dx
            c.drawLine(x, tickTop, x, tickBottom, tickPaint)
        }

        // Tekstualne oznake (default 0,6,12,18,(h) za 5 crtica)
        val labels: List<String> = when (n) {
            5 -> listOf("0", "6", "12", "18", "(h)")
            else -> (0 until n).map { it.toString() }
        }

        val textBaseline = min(bottom - 1f, crispY + textGapV + textPaint.textSize)
        for (i in 0 until n) {
            var x = left + i * dx
            // malo uvuci prvu i zadnju da ne "bježe" izvan ruba
            val edgeInset = 6f
            if (i == 0) x += edgeInset
            if (i == n - 1) x -= edgeInset
            val label = labels[i]
            c.drawText(label, x, textBaseline, textPaint)
        }

        return toPng(bmp)
    }

    private fun clamp(minV: Float, maxV: Float, v: Float): Float =
        max(minV, min(maxV, v))

    private fun scale(v: Float) = v // ostavljeno ako zatreba kasnije

    private fun toPng(bmp: Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
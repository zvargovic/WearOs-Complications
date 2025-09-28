package com.example.complicationprovider.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min

/**
 * Renderer koji vraća PNG (byte[]) za Tiles InlineImageResource.
 * - Ako ima serije: crta sparkline s višebojnom linijom (zeleno ↑ / crveno ↓) + grid.
 * - Ako nema serije: nacrta debug dijagonalu i "NO DATA" tako da uvijek vidiš nešto.
 */
object ChartRenderer {

    fun renderSparklinePng(
        series: List<Double>,
        widthPx: Int,
        heightPx: Int
    ): ByteArray {
        val w = max(10, widthPx)
        val h = max(10, heightPx)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        // Margine za crtanje
        val left   = 8f
        val right  = (w - 8).toFloat()
        val top    = 6f
        val bottom = (h - 6).toFloat()
        val plotW  = right - left
        val plotH  = bottom - top

        // Paintovi
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(64, 255, 255, 255)
            strokeWidth = 1f
            style = Paint.Style.STROKE
        }
        val up = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 214, 107)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val down = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(240, 84, 84)
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val debugText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = max(10f, h * 0.16f)
        }

        // Grid (3 horizontalne linije)
        val midY = top + plotH / 2f
        c.drawLine(left, top,    right, top,    grid)
        c.drawLine(left, midY,   right, midY,   grid)
        c.drawLine(left, bottom, right, bottom, grid)

        if (series.isEmpty()) {
            // DEBUG fallback: dijagonala + NO DATA
            val dbg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.CYAN
                strokeWidth = 3f
            }
            c.drawLine(left, bottom, right, top, dbg)
            c.drawText("NO DATA", left + 6f, top + debugText.textSize + 4f, debugText)
            return toPng(bmp)
        }

        // Normalizacija vrijednosti
        val minV = series.minOrNull() ?: 0.0
        val maxV = series.maxOrNull() ?: 1.0
        val span = (maxV - minV).let { if (it <= 0.0) 1.0 else it }

        val n = series.size.coerceAtLeast(2)
        fun x(i: Int) = left + plotW * (i.toFloat() / (n - 1).toFloat())
        fun y(v: Double) = bottom - ((v - minV) / span).toFloat() * plotH

        var lastX = x(0)
        var lastY = y(series.first())

        for (i in 1 until n) {
            val nx = x(i)
            val ny = y(series[i.coerceAtMost(series.lastIndex)])
            val p = if (ny <= lastY) up else down
            c.drawLine(lastX, lastY, nx, ny, p)
            lastX = nx
            lastY = ny
        }

        return toPng(bmp)
    }

    private fun toPng(bmp: Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
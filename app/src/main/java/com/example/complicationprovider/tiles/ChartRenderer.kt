package com.example.complicationprovider.tiles

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.max

/**
 * Crta pomoćne PNG-ove u točnim *piksel* dimenzijama.
 */
object ChartRenderer {

    /**
     * Postojeća funkcija – ostavljena netaknuta.
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
     * NOVO: okvir + 3 horizontalne grid-trake unutar pravokutnika.
     * Grid je definiran ciklusom: [gridHeightPx] pa [gridGapPx], ponovljeno 3x.
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
        gridColor: Int = 0x553F2A00.toInt() // poluprozirna tamno-narančasta
    ): ByteArray {
        val w = max(1, widthPx)
        val h = max(1, heightPx)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT)

        // Okvir centriran
        val left   = (w - rectWpx) / 2f
        val top    = (h - rectHpx) / 2f
        val right  = left + rectWpx
        val bottom = top + rectHpx

        // Grid trake (3x): traka -> razmak -> traka -> razmak -> traka
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = gridColor
        }
        var y = top
        repeat(6) {
            // traka
            val bandBottom = (y + gridHeightPx).coerceAtMost(bottom)
            if (bandBottom > y) {
                c.drawRect(left, y, right, bandBottom, fill)
            }
            // skok na sljedeći "y" (razmak)
            y = (bandBottom + gridGapPx).coerceAtMost(bottom)
        }

        // Okvir preko svega, da grid bude "ispod"
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

    private fun toPng(bmp: Bitmap): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }
}
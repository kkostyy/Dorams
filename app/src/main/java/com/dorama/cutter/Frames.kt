package com.dorama.cutter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.media3.effect.BitmapOverlay

/** Накладка на весь кадр 1080x1920: интро / плашка части / финальный экран. */
class PartOverlay(
    private val intro: Bitmap,
    private val main: Bitmap,
    private val end: Bitmap,
    private val introEndUs: Long,
    private val endStartUs: Long
) : BitmapOverlay() {
    override fun getBitmap(presentationTimeUs: Long): Bitmap = when {
        presentationTimeUs < introEndUs -> intro
        presentationTimeUs >= endStartUs -> end
        else -> main
    }
}

/** Рисование накладок (кадр 1080x1920). */
object Frames {
    private const val W = 1080
    private const val H = 1920
    private val red = Color.argb(217, 230, 46, 77)

    private fun paint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun boxH(size: Float): Float {
        val fm = paint(size).fontMetrics
        return fm.descent - fm.ascent + 2 * size * 0.3f
    }

    /** Рисует плашку с текстом, возвращает Y её нижнего края. */
    private fun box(c: Canvas, text: String, size: Float, bg: Int, x: Float, top: Float, centered: Boolean): Float {
        val p = paint(size)
        val pad = size * 0.3f
        val w = p.measureText(text)
        val fm = p.fontMetrics
        val left = if (centered) x - w / 2 - pad else x
        val bottom = top + (fm.descent - fm.ascent) + 2 * pad
        c.drawRect(left, top, left + w + 2 * pad, bottom, Paint().apply { color = bg })
        c.drawText(text, left + pad, top + pad - fm.ascent, p)
        return bottom
    }

    private fun wrap(text: String, size: Float, maxW: Float): List<String> {
        val p = paint(size)
        val lines = ArrayList<String>()
        var cur = ""
        for (w in text.split(" ").filter { it.isNotEmpty() }) {
            val t = if (cur.isEmpty()) w else "$cur $w"
            if (cur.isEmpty() || p.measureText(t) <= maxW) cur = t else { lines.add(cur); cur = w }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return lines
    }

    /** Финальный экран: end1 (с переносом строк) и end2; низ блока — на [bottom]. */
    private fun endBlock(c: Canvas, end1: String, end2: String?, cx: Float, bottom: Float, maxW: Float) {
        val lines = wrap(end1, 58f, maxW)
        val h = lines.size * (boxH(58f) + 6f) + (if (end2 != null) boxH(44f) + 6f else 0f)
        var y = bottom - h
        for (l in lines) y = box(c, l, 58f, red, cx, y, true) + 6f
        if (end2 != null) box(c, end2, 44f, red, cx, y, true)
    }

    /** Плашка «ЧАСТЬ i/n» и финальный экран в координатах кадра шириной [w]. */
    private fun layer(c: Canvas, i: Int, n: Int, end1: String?, end2: String?, w: Float, bottom: Float, labelTop: Float) {
        box(c, "ЧАСТЬ $i/$n", 54f, Color.argb(140, 0, 0, 0), 40f, labelTop, false)
        if (end1 != null) endBlock(c, end1, end2, w / 2, bottom, w - 160f)
    }

    /**
     * [landscape] — исходник горизонтальный и повёрнут на 90°: подписи рисуются в повёрнутых
     * координатах 1920x1080, а интро просит повернуть телефон. Вертикальное видео — без поворота.
     */
    fun make(i: Int, n: Int, end1: String?, end2: String?, hook: String?, intro: Boolean, landscape: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (intro) {
            var y: Float
            if (landscape) {
                c.drawColor(Color.argb(140, 0, 0, 0))
                y = box(c, "ПОВЕРНИТЕ ЭКРАН", 84f, Color.argb(230, 230, 46, 77), 540f, 810f, true) + 20f
            } else {
                c.drawColor(Color.argb(70, 0, 0, 0))
                y = 1150f
            }
            y = box(c, "Часть $i из $n", 44f, Color.argb(180, 0, 0, 0), 540f, y, true) + 20f
            if (hook != null) {
                for (line in wrap(hook.uppercase(), 46f, 900f)) {
                    y = box(c, line, 46f, Color.argb(200, 0, 0, 0), 540f, y, true) + 8f
                }
            }
        }
        if (landscape) {
            c.save()
            c.translate(W.toFloat(), 0f)
            c.rotate(90f)
            layer(c, i, n, end1, end2, 1920f, 1000f, 40f)
            c.restore()
        } else {
            // снизу оставляем место под подпись и кнопки TikTok
            layer(c, i, n, end1, end2, 1080f, H - 480f, 140f)
        }
        return bmp
    }
}

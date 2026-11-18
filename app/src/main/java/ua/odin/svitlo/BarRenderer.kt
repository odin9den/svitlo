package ua.odin.svitlo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.time.Instant

/** Малює шкалу на 24 години: від години тому і на добу вперед. */
object BarRenderer {
    const val COLOR_ON = 0xFFFFC107.toInt()      // світло є
    const val COLOR_OFF = 0xFF5F6672.toInt()     // світла немає
    const val COLOR_MAYBE = 0xFFB08D3A.toInt()   // можливе відключення
    const val COLOR_UNKNOWN = 0x40888888         // графіка ще немає

    const val SLOTS = 48                         // 48 × 30 хв = 24 год

    fun color(h: Half): Int = when (h) {
        Half.ON -> COLOR_ON
        Half.OFF -> COLOR_OFF
        Half.MAYBE -> COLOR_MAYBE
        Half.UNKNOWN -> COLOR_UNKNOWN
    }

    fun windowStart(s: Schedule, now: Instant): Instant = s.slotStart(now).minusSeconds(3600)

    fun render(s: Schedule, now: Instant, width: Int, height: Int, labelColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val start = windowStart(s, now)

        val barTop = 4f
        val barBottom = height * 0.58f
        val radius = (barBottom - barTop) / 2.6f
        val slotW = width.toFloat() / SLOTS
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        // Скруглена форма всієї шкали
        c.save()
        c.clipPath(Path().apply {
            addRoundRect(RectF(0f, barTop, width.toFloat(), barBottom), radius, radius, Path.Direction.CW)
        })
        var i = 0
        while (i < SLOTS) {
            val h = s.halfAt(start.plusSeconds(i * 1800L))
            var j = i
            while (j + 1 < SLOTS && s.halfAt(start.plusSeconds((j + 1) * 1800L)) == h) j++
            fill.color = color(h)
            val gap = if (j + 1 < SLOTS) 2f else 0f   // тонкий проміжок між періодами
            c.drawRect(i * slotW, barTop, (j + 1) * slotW - gap, barBottom, fill)
            i = j + 1
        }
        c.restore()

        // Маркер «зараз»
        val x = ((now.epochSecond - start.epochSecond) / 1800f) * slotW
        fill.color = Color.WHITE
        val marker = RectF(x - 3f, barTop - 2f, x + 3f, barBottom + 2f)
        c.drawRoundRect(marker, 3f, 3f, fill)
        fill.style = Paint.Style.STROKE
        fill.strokeWidth = 1.5f
        fill.color = 0x99000000.toInt()
        c.drawRoundRect(marker, 3f, 3f, fill)
        fill.style = Paint.Style.FILL

        // Підписи годин кожні 3 години
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textSize = height * 0.30f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        for (k in 0..SLOTS) {
            val z = start.plusSeconds(k * 1800L).atZone(KYIV)
            if (z.minute == 0 && z.hour % 3 == 0) {
                val tx = (k * slotW).coerceIn(text.textSize, width - text.textSize)
                c.drawText("%02d".format(z.hour), tx, height - 4f, text)
            }
        }
        return bmp
    }
}

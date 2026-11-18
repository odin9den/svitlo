package ua.odin.svitlo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.time.Instant
import java.time.LocalDate

/** Шкала доби 00–24: кольорові проміжки, поділки щогодини, маркер «зараз». */
class DayTimelineView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {

    private var schedule = Schedule(emptyMap())
    private var day: LocalDate = LocalDate.now(KYIV)
    private var now: Instant? = null

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = context.getColor(R.color.text2)
    }
    private val clip = Path()
    private val rect = RectF()

    fun bind(s: Schedule, day: LocalDate, now: Instant) {
        schedule = s
        this.day = day
        this.now = now
        invalidate()
    }

    private fun colorFor(h: Half) = context.getColor(
        when (h) {
            Half.ON -> R.color.on
            Half.OFF -> R.color.off
            Half.MAYBE -> R.color.maybe
            Half.UNKNOWN -> R.color.unknown
        },
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (64 * d).toInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val pad = 2 * d
        val top = 9 * d
        val bottom = top + 30 * d
        val r = 10 * d
        val slotW = (w - 2 * pad) / 48f
        val start = day.atStartOfDay(KYIV).toInstant()

        // Проміжки
        c.save()
        clip.reset()
        rect.set(pad, top, w - pad, bottom)
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        c.clipPath(clip)
        var i = 0
        while (i < 48) {
            val h = schedule.halfAt(start.plusSeconds(i * 1800L))
            var j = i
            while (j + 1 < 48 && schedule.halfAt(start.plusSeconds((j + 1) * 1800L)) == h) j++
            fill.color = colorFor(h)
            val gap = if (j + 1 < 48) 2 * d else 0f
            c.drawRect(pad + i * slotW, top, pad + (j + 1) * slotW - gap, bottom, fill)
            i = j + 1
        }
        c.restore()

        // Поділки й підписи
        fill.color = context.getColor(R.color.text2)
        val tickTop = bottom + 4 * d
        for (hr in 0..24) {
            val x = pad + hr * 2 * slotW
            val major = hr % 3 == 0
            val len = if (major) 5 * d else 2.5f * d
            fill.alpha = if (major) 200 else 110
            c.drawRect(x - 0.5f * d, tickTop, x + 0.5f * d, tickTop + len, fill)
            if (major) {
                val tx = x.coerceIn(label.textSize * 0.9f, w - label.textSize * 0.9f)
                c.drawText("%02d".format(hr), tx, tickTop + 5 * d + label.textSize + 1 * d, label)
            }
        }
        fill.alpha = 255

        // Маркер «зараз»
        now?.let { n ->
            val sec = n.epochSecond - start.epochSecond
            if (sec in 0 until 86_400) {
                val x = pad + sec / 1800f * slotW
                fill.color = context.getColor(R.color.text)
                rect.set(x - 1.5f * d, top - 5 * d, x + 1.5f * d, bottom + 3 * d)
                c.drawRoundRect(rect, 1.5f * d, 1.5f * d, fill)
                c.drawCircle(x, top - 5 * d, 4 * d, fill)
            }
        }
    }
}

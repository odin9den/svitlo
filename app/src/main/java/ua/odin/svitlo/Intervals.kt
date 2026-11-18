package ua.odin.svitlo

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class Interval(val start: Instant, val end: Instant, val half: Half) {
    val minutes: Long get() = Duration.between(start, end).toMinutes()
    fun contains(t: Instant) = !t.isBefore(start) && t.isBefore(end)
}

object Intervals {
    /** Суцільні проміжки однакового стану в межах доби. */
    fun forDay(s: Schedule, day: LocalDate): List<Interval> {
        val start = day.atStartOfDay(KYIV).toInstant()
        val res = mutableListOf<Interval>()
        var i = 0
        while (i < 48) {
            val h = s.halfAt(start.plusSeconds(i * 1800L))
            var j = i
            while (j + 1 < 48 && s.halfAt(start.plusSeconds((j + 1) * 1800L)) == h) j++
            res += Interval(start.plusSeconds(i * 1800L), start.plusSeconds((j + 1) * 1800L), h)
            i = j + 1
        }
        return res
    }

    fun hasData(list: List<Interval>) = list.any { it.half != Half.UNKNOWN }

    fun total(list: List<Interval>, h: Half) = list.filter { it.half == h }.sumOf { it.minutes }

    /** "4 год", "30 хв", "1 год 30 хв" */
    fun dur(min: Long): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "$h год $m хв"
            h > 0 -> "$h год"
            else -> "$m хв"
        }
    }

    /** "через 1 год 12 хв" */
    fun relative(from: Instant, to: Instant): String {
        val min = (Duration.between(from, to).seconds + 59) / 60
        return if (min <= 1) "за хвилину" else "через ${dur(min)}"
    }

    /** Кінець доби показуємо як 24:00, а не 00:00. */
    fun hmEnd(t: Instant, day: LocalDate): String =
        if (t.atZone(KYIV).toLocalDate().isAfter(day)) "24:00" else StatusCalc.hm(t)
}

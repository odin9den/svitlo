package ua.odin.svitlo

import java.time.Instant
import java.time.LocalDate

/** Скільки хвилин світла / без світла за добу. */
data class DayTotal(val on: Long, val off: Long)

object Stats {
    /** Підсумки по кожній добі зі знімка ДТЕК (сьогодні й завтра). */
    fun dayTotals(snap: Snapshot): Map<LocalDate, DayTotal> {
        val sched = Schedule.from(snap)
        return snap.days.keys
            .map { Instant.ofEpochSecond(it).atZone(KYIV).toLocalDate() }
            .associateWith { day ->
                val list = Intervals.forDay(sched, day)
                DayTotal(
                    on = Intervals.total(list, Half.ON),
                    off = Intervals.total(list, Half.OFF) + Intervals.total(list, Half.MAYBE),
                )
            }
            .filterValues { it.on + it.off > 0 }
    }
}

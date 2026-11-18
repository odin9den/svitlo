package ua.odin.svitlo

import java.time.Instant
import java.time.ZoneId

val KYIV: ZoneId = ZoneId.of("Europe/Kyiv")

/** Стан одного 30-хвилинного проміжку. */
enum class Half { ON, OFF, MAYBE, UNKNOWN }

/** Поточне відключення за адресою (відповідь ДТЕК getHomeNum). */
data class Outage(val subType: String, val start: String, val end: String) {
    val isActive: Boolean get() = subType.isNotBlank()
    val isEmergency: Boolean
        get() = isActive &&
            !subType.contains("Стабілізац", ignoreCase = true) &&
            !subType.contains("графік", ignoreCase = true)
}

/** Збережений знімок даних ДТЕК для нашої черги. */
data class Snapshot(
    val group: String,                 // напр. "GPV1.1"
    val days: Map<Long, String>,       // початок доби (epoch sec) -> 24 значення через кому
    val update: String,                // коли ДТЕК оновив графік
    val fetchedAt: Long,               // коли ми його забрали (ms)
    val outage: Outage?,
)

class Schedule(private val days: Map<Long, List<Half>>) {

    fun halfAt(t: Instant): Half {
        val z = t.atZone(KYIV)
        val dayStart = z.toLocalDate().atStartOfDay(KYIV).toEpochSecond()
        val list = days[dayStart] ?: return Half.UNKNOWN
        val idx = z.hour * 2 + if (z.minute >= 30) 1 else 0
        return list.getOrElse(idx) { Half.UNKNOWN }
    }

    fun slotStart(t: Instant): Instant {
        val z = t.atZone(KYIV)
        return z.withMinute(if (z.minute >= 30) 30 else 0).withSecond(0).withNano(0).toInstant()
    }

    /** Момент, коли стан зміниться. null — якщо далі графіка немає або змін не буде. */
    fun nextChange(now: Instant): Instant? {
        val cur = halfAt(now)
        var t = slotStart(now)
        repeat(96) {
            t = t.plusSeconds(1800)
            val h = halfAt(t)
            if (h != cur) return if (h == Half.UNKNOWN) null else t
        }
        return null
    }

    companion object {
        /** yes / no / first / second (+ maybe, mfirst, msecond) -> дві половини години. */
        private fun halves(v: String): List<Half> = when (v) {
            "yes" -> listOf(Half.ON, Half.ON)
            "no" -> listOf(Half.OFF, Half.OFF)
            "first" -> listOf(Half.OFF, Half.ON)      // немає світла перші 30 хв
            "second" -> listOf(Half.ON, Half.OFF)     // немає світла другі 30 хв
            "maybe" -> listOf(Half.MAYBE, Half.MAYBE)
            "mfirst" -> listOf(Half.MAYBE, Half.ON)
            "msecond" -> listOf(Half.ON, Half.MAYBE)
            else -> listOf(Half.UNKNOWN, Half.UNKNOWN)
        }

        fun from(snap: Snapshot): Schedule =
            Schedule(snap.days.mapValues { (_, s) -> s.split(",").flatMap { halves(it.trim()) } })
    }
}

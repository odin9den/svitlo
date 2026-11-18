package ua.odin.svitlo

import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class Status(
    val title: String,
    val text: String,
    val chip: String?,          // коротко для чипа біля годинника: "до 16:30"
    val until: Instant?,        // до якого моменту діє поточний стан
    val current: Half,
)

object StatusCalc {
    private val HM = DateTimeFormatter.ofPattern("HH:mm")
    private val DTEK = DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy")

    fun hm(t: Instant): String = HM.format(t.atZone(KYIV))

    /** "о 16:30" або "завтра о 06:30" */
    fun at(t: Instant, now: Instant): String {
        val d = t.atZone(KYIV).toLocalDate()
        val today = now.atZone(KYIV).toLocalDate()
        return when (d) {
            today -> "о ${hm(t)}"
            today.plusDays(1) -> "завтра о ${hm(t)}"
            else -> "${d.dayOfMonth}.${"%02d".format(d.monthValue)} о ${hm(t)}"
        }
    }

    fun of(snap: Snapshot?, now: Instant): Status {
        if (snap == null) {
            return Status("Графік ще не завантажено", "Відкрийте застосунок, щоб оновити", null, null, Half.UNKNOWN)
        }
        val sched = Schedule.from(snap)
        val cur = sched.halfAt(now)
        val next = sched.nextChange(now)

        // Поточне відключення за адресою важливіше за графік (аварійні не потрапляють у графік).
        val o = snap.outage
        if (o != null && o.isActive) {
            val end = runCatching { LocalDateTime.parse(o.end, DTEK).atZone(KYIV).toInstant() }.getOrNull()
            if (end != null && end.isAfter(now)) {
                if (o.isEmergency) {
                    return Status("Аварійне відключення", "Орієнтовно до ${hm(end)}", "до ${hm(end)}", end, Half.OFF)
                }
                if (cur != Half.OFF) {
                    return Status("Світла немає", "За даними ДТЕК — до ${hm(end)}", "до ${hm(end)}", end, Half.OFF)
                }
            }
        }

        val chip = next?.let { "до ${hm(it)}" }
        return when (cur) {
            Half.ON -> Status(
                "Світло є",
                next?.let { "Вимкнуть ${at(it, now)}" } ?: "Відключень за графіком не видно",
                chip, next, cur,
            )
            Half.OFF -> Status(
                "Світла немає",
                next?.let { "Увімкнуть ${at(it, now)}" } ?: "Час увімкнення невідомий",
                chip, next, cur,
            )
            Half.MAYBE -> Status(
                "Можливе відключення",
                next?.let { "До ${hm(it)}" } ?: "",
                chip, next, cur,
            )
            Half.UNKNOWN -> Status("Графіка ще немає", "ДТЕК оновив: ${snap.update}", null, null, cur)
        }
    }
}

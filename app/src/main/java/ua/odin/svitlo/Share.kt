package ua.odin.svitlo

import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** «Поділитися графіком» — текст для чату сусідів чи родини. */
object Share {
    private val fmt = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("uk"))
    const val LINK = "https://github.com/odin9den/svitlo"

    fun text(ctx: Context, snap: Snapshot): String {
        val s = Schedule.from(snap)
        val today = LocalDate.now(KYIV)
        val sb = StringBuilder()
        sb.append("💡 Графік світла — ${Store.street(ctx)}, ${Store.house(ctx)}")
        sb.append(" (черга ${snap.group.removePrefix("GPV")})\n")
        for ((name, day) in listOf("Сьогодні" to today, "Завтра" to today.plusDays(1))) {
            val list = Intervals.forDay(s, day)
            if (!Intervals.hasData(list)) continue
            sb.append("\n$name, ${fmt.format(day)} — світло ${Intervals.dur(Intervals.total(list, Half.ON))}\n")
            for (iv in list) {
                val mark = when (iv.half) {
                    Half.ON -> "🟡"
                    Half.OFF -> "⚫"
                    Half.MAYBE -> "🟠"
                    Half.UNKNOWN -> continue
                }
                val what = when (iv.half) {
                    Half.ON -> "є"
                    Half.OFF -> "немає"
                    else -> "можливо"
                }
                sb.append("$mark ${StatusCalc.hm(iv.start)}–${Intervals.hmEnd(iv.end, day)} $what\n")
            }
        }
        sb.append("\nЗастосунок «Світло» для Android: $LINK")
        return sb.toString()
    }

    fun open(ctx: Context, snap: Snapshot) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text(ctx, snap))
        }
        ctx.startActivity(Intent.createChooser(send, "Поділитися графіком"))
    }
}

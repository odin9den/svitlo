package ua.odin.svitlo

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate

/** Нагадування перед вимиканням/увімкненням і сповіщення про зміни графіка. */
object Reminders {
    const val CHANNEL = "alerts"
    private const val ID_REMINDER = 60
    private const val ID_CHANGE = 61

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Нагадування", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Попередження перед відключенням і про зміни графіка"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    /** Тихий режим: з 23:00 до 07:00. */
    fun isQuiet(ctx: Context, t: Instant = Instant.now()): Boolean {
        if (!Store.quietNight(ctx)) return false
        val h = t.atZone(KYIV).hour
        return h >= 23 || h < 7
    }

    private fun pending(ctx: Context, kind: String? = null, at: Long = 0): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
        if (kind != null) i.putExtra("kind", kind).putExtra("at", at)
        return PendingIntent.getBroadcast(
            ctx, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Ставить будильник на найближче нагадування (одне за раз). */
    fun schedule(ctx: Context, snap: Snapshot?, now: Instant) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx))
        if (snap == null) return
        val wantOff = Store.remindOff(ctx)
        val wantOn = Store.remindOn(ctx)
        if (!wantOff && !wantOn) return

        val lead = Store.leadMinutes(ctx) * 60L
        val s = Schedule.from(snap)
        var t = s.slotStart(now)
        var prev = s.halfAt(t)
        repeat(96) {
            t = t.plusSeconds(1800)
            val h = s.halfAt(t)
            if (h != prev) {
                val kind = when {
                    h == Half.OFF && prev != Half.OFF && wantOff -> "off"
                    h == Half.ON && prev == Half.OFF && wantOn -> "on"
                    else -> null
                }
                val at = t.minusSeconds(lead)
                if (kind != null && at.isAfter(now)) {
                    am.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(ctx, kind, t.toEpochMilli()),
                    )
                    return
                }
            }
            prev = h
        }
    }

    private fun canPost(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Показати нагадування, якщо подія ще актуальна за поточним графіком. */
    fun fire(ctx: Context, kind: String, eventAt: Instant) {
        val snap = Store.load(ctx) ?: return
        val s = Schedule.from(snap)
        val expected = if (kind == "off") Half.OFF else Half.ON
        val stillValid = s.halfAt(eventAt) == expected && s.halfAt(eventAt.minusSeconds(1800)) != expected
        if (!stillValid || isQuiet(ctx) || !canPost(ctx)) return
        ensureChannel(ctx)

        val now = Instant.now()
        val mins = ((eventAt.epochSecond - now.epochSecond + 59) / 60).coerceAtLeast(1)
        val title = if (kind == "off") "Світло вимкнуть о ${StatusCalc.hm(eventAt)}"
        else "Світло увімкнуть о ${StatusCalc.hm(eventAt)}"
        val text = if (kind == "off") "Через ${Intervals.dur(mins)}. Встигніть зарядити телефон і павербанк."
        else "Через ${Intervals.dur(mins)}."

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter((mins * 60_000L).coerceAtLeast(60_000L))
            .setSilent(!Store.reminderSound(ctx))
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_REMINDER, n)
    }

    /** Порівнює старий і новий графік; повідомляє про новий графік на завтра або зміни. */
    fun checkChanges(ctx: Context, old: Snapshot?, new: Snapshot) {
        if (old == null || old.group != new.group || !Store.notifyChanges(ctx) || !canPost(ctx)) return
        val now = Instant.now()
        val today = now.atZone(KYIV).toLocalDate()
        val tomorrow = today.plusDays(1)
        val sNew = Schedule.from(new)
        val sOld = Schedule.from(old)

        fun key(d: LocalDate) = d.atStartOfDay(KYIV).toEpochSecond()
        fun hasData(s: String?) = s != null && s.split(",").any { it == "yes" || it == "no" || it == "first" || it == "second" }

        var title: String? = null
        var text: String? = null

        // 1) Графік на сьогодні змінився (порівнюємо лише майбутні проміжки)
        val start = sNew.slotStart(now)
        val endOfDay = tomorrow.atStartOfDay(KYIV).toInstant()
        var t = start
        var todayChanged = false
        while (t.isBefore(endOfDay)) {
            if (sOld.halfAt(t) != sNew.halfAt(t) && sOld.halfAt(t) != Half.UNKNOWN) { todayChanged = true; break }
            t = t.plusSeconds(1800)
        }
        if (todayChanged) {
            title = "Графік на сьогодні змінився"
            val st = StatusCalc.of(new, now)
            text = listOfNotNull(st.title, st.text.takeIf { it.isNotBlank() }).joinToString(" · ")
        }

        // 2) З'явився графік на завтра або він змінився
        val oldTom = old.days[key(tomorrow)]
        val newTom = new.days[key(tomorrow)]
        if (title == null && hasData(newTom)) {
            val list = Intervals.forDay(sNew, tomorrow)
            val on = Intervals.total(list, Half.ON)
            val firstOff = list.firstOrNull { it.half == Half.OFF }
            val summary = "Світло буде ${Intervals.dur(on)}" +
                (firstOff?.let { ". Перше відключення о ${StatusCalc.hm(it.start)}" } ?: ". Відключень не заплановано")
            if (!hasData(oldTom)) {
                title = "Опубліковано графік на завтра"; text = summary
            } else if (oldTom != newTom) {
                title = "Графік на завтра змінився"; text = summary
            }
        }

        if (title == null) return
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(isQuiet(ctx) || !Store.reminderSound(ctx))
            .setAutoCancel(true)
            .setContentIntent(openApp(ctx))
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(ID_CHANGE, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind")
        val at = intent.getLongExtra("at", 0)
        if (kind != null && at > 0) Reminders.fire(ctx, kind, Instant.ofEpochMilli(at))
        // наступне нагадування
        Reminders.schedule(ctx, Store.load(ctx), Instant.now().plusSeconds(1))
    }
}

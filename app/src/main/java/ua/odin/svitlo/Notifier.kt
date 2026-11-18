package ua.odin.svitlo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RemoteViews
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant

object Notifier {
    private const val CHANNEL = "schedule"
    private const val ID = 52

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Графік світла", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Постійне сповіщення з графіком відключень"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)
    }

    /** Якщо сповіщення змахнули — DismissReceiver одразу публікує його знову. */
    private fun restore(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 2, Intent(ctx, DismissReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun post(ctx: Context, snap: Snapshot?, now: Instant) {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        ensureChannel(ctx)
        val st = StatusCalc.of(snap, now)
        val sched = snap?.let { Schedule.from(it) } ?: Schedule(emptyMap())
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            buildLiveUpdate(ctx, sched, st, now, pi)
        } else {
            buildClassic(ctx, sched, st, now, pi)
        }
        ctx.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    /**
     * Android 16 / One UI 8: Live Update. Шкала дня як кольорові сегменти,
     * коротке "до 16:30" — у чипі біля годинника та на заблокованому екрані.
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildLiveUpdate(
        ctx: Context, sched: Schedule, st: Status, now: Instant, pi: PendingIntent,
    ): Notification {
        val start = BarRenderer.windowStart(sched, now)
        val segments = mutableListOf<Notification.ProgressStyle.Segment>()
        var runHalf: Half? = null
        var runMinutes = 0
        for (i in 0 until BarRenderer.SLOTS) {
            val h = sched.halfAt(start.plusSeconds(i * 1800L))
            if (h == runHalf) {
                runMinutes += 30
            } else {
                runHalf?.let { segments += Notification.ProgressStyle.Segment(runMinutes).setColor(BarRenderer.color(it)) }
                runHalf = h
                runMinutes = 30
            }
        }
        runHalf?.let { segments += Notification.ProgressStyle.Segment(runMinutes).setColor(BarRenderer.color(it)) }

        val progress = ((now.epochSecond - start.epochSecond) / 60).toInt()
        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgressSegments(segments)
            .setProgress(progress)

        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(st.title)
            .setContentText(st.text)
            .setStyle(style)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(pi)
            .setDeleteIntent(restore(ctx))
        st.chip?.let { b.setShortCriticalText(it) }
        st.until?.let {
            b.setWhen(it.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        // Запит на Live Update (те саме, що NotificationCompat.setRequestPromotedOngoing).
        b.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        return b.build()
    }

    /** Android 15 і старше: звичайне постійне сповіщення з картинкою шкали. */
    private fun buildClassic(
        ctx: Context, sched: Schedule, st: Status, now: Instant, pi: PendingIntent,
    ): Notification {
        val bar = BarRenderer.render(sched, now, 900, 100, 0xFF8A8F98.toInt())
        val rv = RemoteViews(ctx.packageName, R.layout.notif_bar).apply {
            setTextViewText(R.id.title, st.title)
            setTextViewText(R.id.text, st.text)
            setImageViewBitmap(R.id.bar, bar)
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle(st.title)
            .setContentText(st.text)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(rv)
            .setCustomBigContentView(rv)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(pi)
            .setDeleteIntent(restore(ctx))
        st.until?.let {
            b.setWhen(it.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        return b.build()
    }
}

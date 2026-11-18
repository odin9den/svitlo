package ua.odin.svitlo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.time.Instant

class ScheduleWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx, Store.load(ctx), Instant.now())
    }

    companion object {
        fun updateAll(ctx: Context, snap: Snapshot?, now: Instant) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val cn = ComponentName(ctx, ScheduleWidget::class.java)
            if (mgr.getAppWidgetIds(cn).isEmpty()) return

            val st = StatusCalc.of(snap, now)
            val sched = snap?.let { Schedule.from(it) } ?: Schedule(emptyMap())
            val bar = BarRenderer.render(sched, now, 900, 100, 0x99FFFFFF.toInt())
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val rv = RemoteViews(ctx.packageName, R.layout.widget).apply {
                setTextViewText(R.id.title, st.title)
                setTextViewText(R.id.text, st.text)
                setImageViewBitmap(R.id.bar, bar)
                setOnClickPendingIntent(R.id.root, pi)
            }
            mgr.updateAppWidget(cn, rv)
        }
    }
}

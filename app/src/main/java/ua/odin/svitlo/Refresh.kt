package ua.odin.svitlo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.TimeUnit

object Refresher {
    /** Забрати свіжі дані з ДТЕК і перемалювати все. */
    suspend fun refresh(ctx: Context): Result<Snapshot> = withContext(Dispatchers.IO) {
        val addr = Store.active(ctx)
        if (addr == null) {
            runCatching { DtekClient.refreshStreets(ctx, Region.KYIV) }
            Renderer.renderAll(ctx)
            return@withContext Result.failure(IllegalStateException("Адресу не вказано"))
        }
        val old = Store.load(ctx)
        val r = runCatching { DtekClient.fetch(ctx, addr, Store.group(ctx)) }
        // Поки чекали ДТЕК, користувач міг перемкнути адресу — тоді цей графік уже чужий
        if (Store.active(ctx)?.id != addr.id) return@withContext r
        r.onSuccess {
            Store.save(ctx, it)
            runCatching { Reminders.checkChanges(ctx, old, it) }
            Store.saveHistory(ctx, Stats.dayTotals(it))
            Store.setError(ctx, null)
        }.onFailure {
            Store.setError(ctx, it.message ?: it.javaClass.simpleName)
        }
        Renderer.renderAll(ctx)
        r
    }

    fun schedulePeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun refreshNow(ctx: Context) {
        WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<RefreshWorker>().build())
    }
}

object Renderer {
    /** Перемалювати сповіщення і віджет з кешу (без мережі). */
    fun renderAll(ctx: Context) {
        val snap = Store.load(ctx)
        val now = Instant.now()
        Notifier.post(ctx, snap, now)
        ScheduleWidget.updateAll(ctx, snap, now)
        scheduleTick(ctx, StatusCalc.of(snap, now).until)
        runCatching { Reminders.schedule(ctx, snap, now) }
    }

    /** Будильник на момент зміни стану, щоб сповіщення перемкнулось вчасно навіть офлайн. */
    private fun scheduleTick(ctx: Context, at: Instant?) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.cancel(pi)
        if (at != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli() + 5_000, pi)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Refresher.refresh(applicationContext)
        return Result.success()
    }
}

/** Сповіщення змахнули або натиснули «Очистити все» — повертаємо його. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Renderer.renderAll(ctx)
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Renderer.renderAll(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Renderer.renderAll(ctx)
        Refresher.schedulePeriodic(ctx)
        Refresher.refreshNow(ctx)
    }
}

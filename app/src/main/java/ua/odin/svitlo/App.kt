package ua.odin.svitlo

import android.app.Application
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Записуємо збій, щоб показати його при наступному запуску.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("svitlo", Context.MODE_PRIVATE).edit()
                    .putString("crash", e.stackTraceToString().take(1500))
                    .commit()
            }
            previous?.uncaughtException(t, e)
        }
        Notifier.ensureChannel(this)
        Refresher.schedulePeriodic(this)
    }
}

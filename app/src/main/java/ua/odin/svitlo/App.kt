package ua.odin.svitlo

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
        Refresher.schedulePeriodic(this)
    }
}

package dev.mdm.android

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Settings first (the queue reads the speed mode), then the list and the library: they
        // start with the process, whoever started it (the screen, a share, the service coming
        // back after the system stopped the app, the accessibility service).
        Prefs.init(this)
        Downloads.start(this)
    }
}

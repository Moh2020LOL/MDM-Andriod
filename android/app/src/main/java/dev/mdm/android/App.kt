package dev.mdm.android

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The list and the library start with the process, whoever started it (the screen, a
        // share, the service coming back after the system stopped the app).
        Downloads.start(this)
    }
}

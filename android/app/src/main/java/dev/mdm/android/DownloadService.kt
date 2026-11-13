package dev.mdm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while anything downloads (Android ends background apps otherwise), with
 * the notification that says so. It watches the list [Downloads] keeps and stops itself when
 * nothing is running.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
        startForegroundCompat(notification(0, 0))
        // A partial wake lock: the CPU stays on for the connections while the screen sleeps.
        // Taken for ten minutes at a time and renewed below while downloads run, so it can't
        // run out in the middle of a long download (or stay held if the app is killed).
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mdm:downloads")

        scope.launch {
            Downloads.items.collectLatest { list ->
                val running = list.filter { it.status == Status.RUNNING }
                if (running.isEmpty()) {
                    stopSelf()
                } else {
                    wake?.takeIf { !it.isHeld }?.acquire(10 * 60 * 1000L)
                    nm.notify(ID, notification(running.size, running.sumOf { it.speed }))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /**
     * Android 15 ends a `dataSync` service after about six hours a day and waits for it to stop
     * within seconds, or the app crashes. This service runs as `specialUse` on Android 14+ (no such
     * limit, see startForegroundCompat); this is the safety net for a system that applies it anyway.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(count: Int, speed: Long): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_summary, count, formatBytes(speed)))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            // A download manager runs as long as there is something to download: not a "data sync".
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 1

        fun ensureRunning(context: Context) {
            val i = Intent(context, DownloadService::class.java)
            // Android 12+ refuses a foreground service started from the background (the app came
            // back by itself, say): the downloads then run until the system stops the process.
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            }
        }
    }
}

fun formatBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) {
        v /= 1024
        i++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", v, units[i])
}

package dev.mdm.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * OPTIONAL (Settings → "Floating button"): a small round button that stays above every app. Tap it
 * and the link in the clipboard is analyzed and added ([ClipActivity]); drag it anywhere. It needs
 * "display over other apps" and a foreground service, so Android keeps it alive. Off by default.
 */
class BubbleService : Service() {
    private var view: View? = null
    private var wm: WindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.bubble_channel), NotificationManager.IMPORTANCE_MIN),
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.bubble_notification))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        if (!Permissions.canOverlay(this) || !addBubble()) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        super.onDestroy()
    }

    private fun addBubble(): Boolean {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val size = dp(52)
        val v = TextView(this).apply {
            text = "⬇"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E88E5"))
            }
            elevation = dp(6).toFloat()
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.x = dp(8)
        lp.y = dp(240)
        var downX = 0f
        var downY = 0f
        var moved = false
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8)) moved = true
                    if (moved) {
                        lp.x += dx.toInt()
                        lp.y += dy.toInt()
                        downX = e.rawX
                        downY = e.rawY
                        runCatching { manager.updateViewLayout(v, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Reading the clipboard needs a window with focus: a tiny transparent screen does it.
                        startActivity(Intent(this, ClipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    true
                }
                else -> false
            }
        }
        return try {
            manager.addView(v, lp)
            wm = manager
            view = v
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL = "bubble"
        private const val ID = 2

        /** Starts or stops the button to match the setting and the permission. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val i = Intent(app, BubbleService::class.java)
            if (Prefs.bubbleEnabled.value && Permissions.canOverlay(app)) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
                }
            } else {
                app.stopService(i)
            }
        }
    }
}

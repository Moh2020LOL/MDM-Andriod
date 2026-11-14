package dev.mdm.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * The small floating window over other apps: "Video found: Download". Shown when a video link is
 * recognised ([LinkWatcherService] in browsers, or the in-app browser while the app is behind).
 * The person can switch it off (Prefs.overlayEnabled) and it needs the "display over other apps"
 * permission (Permissions.canOverlay); without it [show] does nothing.
 *
 * Plain Android views, no Compose: a Compose window needs a lifecycle owner a floating window lacks.
 * Drag it anywhere; it hides itself after [AUTO_HIDE_MS] or with the cross.
 */
object Overlay {
    private const val AUTO_HIDE_MS = 15_000L

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private var shownUrl: String? = null

    /** [url]: a direct media link (video = false) or a video page (video = true, for yt-dlp). */
    fun show(context: Context, url: String, video: Boolean, referer: String? = null) {
        val app = context.applicationContext
        if (!Prefs.overlayEnabled.value || !Permissions.canOverlay(app)) return
        handler.post {
            if (shownUrl == url && view != null) return@post
            dismissNow()
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = build(app, url, video, referer)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            // Absolute right (not END): the floating window doesn't flip with the language.
            lp.gravity = Gravity.TOP or Gravity.RIGHT
            lp.x = dp(app, 12)
            lp.y = dp(app, 140)
            try {
                wm.addView(v, lp)
            } catch (e: Exception) {
                return@post
            }
            windowManager = wm
            view = v
            shownUrl = url
            attachDrag(v, lp, wm)
            handler.postDelayed({ dismissNow() }, AUTO_HIDE_MS)
        }
    }

    fun dismiss() {
        handler.post { dismissNow() }
    }

    private fun dismissNow() {
        handler.removeCallbacksAndMessages(null)
        val v = view
        if (v != null) {
            runCatching { windowManager?.removeView(v) }
        }
        view = null
        shownUrl = null
    }

    private fun build(app: Context, url: String, video: Boolean, referer: String?): View {
        val pad = dp(app, 10)
        val row = LinearLayout(app).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E88E5"))
                cornerRadius = dp(app, 28).toFloat()
            }
            elevation = dp(app, 6).toFloat()
        }
        fun label(text: String, bold: Boolean = false) = TextView(app).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(pad, 0, pad, 0)
        }
        row.addView(label(app.getString(R.string.overlay_found)))
        val download = label(app.getString(R.string.overlay_download), bold = true).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1565C0"))
                cornerRadius = dp(app, 20).toFloat()
            }
            setPadding(dp(app, 14), dp(app, 6), dp(app, 14), dp(app, 6))
            setOnClickListener {
                dismissNow()
                if (video) {
                    // A page: the app opens with the link so the person picks the quality.
                    app.startActivity(
                        android.content.Intent(app, MainActivity::class.java)
                            .setAction(android.content.Intent.ACTION_SEND)
                            .setType("text/plain")
                            .putExtra(android.content.Intent.EXTRA_TEXT, url)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    Downloads.add(url, video = false, referer = referer)
                    Toast.makeText(app, R.string.overlay_added, Toast.LENGTH_SHORT).show()
                }
            }
        }
        row.addView(download)
        val close = label("✕", bold = true).apply { setOnClickListener { dismissNow() } }
        row.addView(close)
        return row
    }

    private fun attachDrag(v: View, lp: WindowManager.LayoutParams, wm: WindowManager) {
        var lastX = 0f
        var lastY = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Gravity RIGHT: x is the distance from the right edge, so moving right shrinks it.
                    lp.x -= (e.rawX - lastX).toInt()
                    lp.y += (e.rawY - lastY).toInt()
                    lastX = e.rawX
                    lastY = e.rawY
                    runCatching { wm.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}

package dev.mdm.android

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Video pages: yt-dlp (inside the app, through youtubedl-android) finds the stream's direct
 * address, and MDM's engine downloads it with many connections: the speed-up stays.
 *
 * On the desktop MDM runs yt-dlp as a separate program (crates/tools); a phone can't, so the
 * library brings Python, yt-dlp and ffmpeg inside the APK.
 *
 * Limit: the address is ONE file with sound and picture together (YouTube serves those up to
 * 720p; the higher qualities are separate picture and sound streams that need ffmpeg to join).
 */
object VideoResolver {
    data class Found(val url: String, val title: String, val ext: String)

    @Volatile
    private var ready = false

    private fun ensureReady(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            YoutubeDL.getInstance().init(context.applicationContext)
            ready = true
        }
        // Sites change, and so does yt-dlp: ask for the newest, once per start, and don't wait
        // for it if there is no network.
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext) }
    }

    /** Blocks (the first call unpacks yt-dlp): never on the main thread. Throws with yt-dlp's own message. */
    fun resolve(context: Context, page: String): Found {
        ensureReady(context)
        val request = YoutubeDLRequest(page).apply {
            addOption("-f", "best[ext=mp4]/best")
            addOption("--no-playlist")
        }
        val info = YoutubeDL.getInstance().getInfo(request)
        val url = info.url ?: error("yt-dlp gave no address for this page")
        return Found(url, info.title ?: page, info.ext ?: "mp4")
    }
}

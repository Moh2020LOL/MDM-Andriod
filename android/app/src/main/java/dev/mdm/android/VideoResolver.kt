package dev.mdm.android

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

/**
 * Video pages: yt-dlp (inside the app, through youtubedl-android) finds the stream's direct
 * address, and MDM's engine downloads it with many connections: the speed-up stays.
 *
 * On the desktop MDM runs yt-dlp as a separate program (crates/tools); a phone can't, so the
 * library brings Python, yt-dlp and ffmpeg inside the APK.
 *
 * Why this asks yt-dlp for its JSON itself (`--dump-single-json`) instead of using the library's
 * VideoInfo: the stream's address is only good with the headers yt-dlp used to find it (User-Agent,
 * Referer, ...). Sites such as YouTube answer 403 to the bare address. The JSON carries them
 * (`http_headers`) and they go to the engine along with the address.
 *
 * yt-dlp goes stale within weeks, so it updates itself in the background at the first use, and a
 * failure to resolve is retried ONCE after an update finished.
 *
 * Limit: the address is ONE file with sound and picture together (YouTube serves those up to
 * 720p; the higher qualities are separate picture and sound streams that need ffmpeg to join).
 */
object VideoResolver {
    data class Found(
        val url: String,
        val title: String,
        val ext: String,
        /** What yt-dlp sent to the site: the engine sends the same with the stream's address. */
        val headers: Map<String, String>,
    )

    @Volatile
    private var ready = false
    private val updateStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    private val updateDone = java.util.concurrent.CountDownLatch(1)

    private fun ensureReady(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            YoutubeDL.getInstance().init(context.applicationContext)
            ready = true
        }
        // Don't make the first video wait for the update: it runs on its own thread.
        val app = context.applicationContext
        Thread({ updateNow(app) }, "mdm-ytdlp-update").apply { isDaemon = true }.start()
    }

    /** Updates yt-dlp once per process (the first caller does the work, the others return at once). */
    private fun updateNow(app: Context) {
        if (!updateStarted.compareAndSet(false, true)) return
        try {
            // Nightly first: YouTube changes faster than stable releases; stable if that fails.
            val yt = YoutubeDL.getInstance()
            runCatching { yt.updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY) }
                .recoverCatching { yt.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
        } finally {
            updateDone.countDown()
        }
    }

    /** Returns when the one update of this process has finished (at most two minutes). */
    private fun waitForUpdate(app: Context) {
        updateNow(app) // runs it here when nothing started it yet
        updateDone.await(120, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Blocks (the first call unpacks yt-dlp): never on the main thread. Throws with yt-dlp's own message. */
    fun resolve(context: Context, page: String): Found {
        val app = context.applicationContext
        ensureReady(app)
        return try {
            fetch(page)
        } catch (first: Exception) {
            // Most "yt-dlp can't do this site" failures are an old yt-dlp: update, then try once more.
            waitForUpdate(app)
            fetch(page)
        }
    }

    private fun fetch(page: String): Found {
        val request = YoutubeDLRequest(page).apply {
            addOption("--dump-single-json")
            addOption("--no-playlist")
            addOption("--no-warnings")
            addOption("-f", "best[ext=mp4]/best")
        }
        // (request, null, null): the call the library's own getInfo makes; `execute(request)`
        // alone fits two of its overloads.
        val out = YoutubeDL.getInstance().execute(request, null, null).out
        // yt-dlp can print other lines before the JSON; the JSON is the last object.
        val start = out.indexOf('{')
        if (start < 0) error("yt-dlp gave no answer for this page")
        val info = JSONObject(out.substring(start))
        val url = info.optString("url").ifEmpty { null }
            ?: error("yt-dlp found no single stream for this page")
        return Found(
            url = url,
            title = info.optString("title").ifEmpty { page },
            ext = info.optString("ext").ifEmpty { "mp4" },
            headers = headersOf(info.optJSONObject("http_headers")),
        )
    }

    /** yt-dlp's request headers minus the ones the engine decides itself. */
    private fun headersOf(h: JSONObject?): Map<String, String> {
        if (h == null) return emptyMap()
        val skip = setOf("accept-encoding", "host", "content-length", "range")
        val map = LinkedHashMap<String, String>()
        for (k in h.keys()) {
            if (k.lowercase() in skip) continue
            val v = h.optString(k)
            if (v.isNotEmpty()) map[k] = v
        }
        return map
    }
}

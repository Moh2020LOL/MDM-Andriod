package dev.mdm.android

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Video pages. WHY THIS REPLACED THE OLD "yt-dlp finds the address, our engine downloads it":
 * the old way kept failing ("yt-dlp failed", format errors) because
 *  - it asked for ONE file with picture and sound (`best[ext=mp4]`), which many sites (and every
 *    YouTube quality above 360p/720p) do not offer: "Requested format is not available";
 *  - a stream address is only good with the exact headers yt-dlp used, and expires;
 *  - when yt-dlp could not read a site at all there was no second try.
 * Now yt-dlp downloads AND merges (ffmpeg, inside the APK) by itself, and a failure walks a
 * ladder instead of stopping:
 *   1. yt-dlp, best video + best audio, merged to mp4 (or MP3 for "audio only");
 *   2. update yt-dlp (it goes stale within weeks) and try again;
 *   3. other YouTube "clients" (some need no JavaScript, which this app's yt-dlp does not have);
 *   4. one single file, no merging (`-f b`);
 *   5. the Rust page analyzer ([Native] "analyze"): reads the page's own source for a file,
 *      an HLS/DASH stream or an embedded player, whatever the site, and downloads that.
 * Every failure is explained in words ([explain]); yt-dlp's raw text is kept under "Details".
 */
object VideoEngine {
    sealed class Outcome {
        /** yt-dlp wrote a finished file. */
        data class File(val path: java.io.File) : Outcome()

        /** A plain file the analyzer found: the HTTP engine downloads it with [headers]. */
        data class Direct(val url: String, val headers: Map<String, String>, val title: String?) : Outcome()

        data class Failed(val short: String, val detail: String) : Outcome()

        /** The person paused or removed it. */
        object Stopped : Outcome()
    }

    /** What the screen shows while it runs; [pct] 0..100, sizes in bytes (0: unknown). */
    class Progress(val pct: Float, val total: Long, val speed: Long, val title: String?)

    @Volatile
    private var ready = false
    private val updateStarted = AtomicBoolean(false)
    private val updateDone = CountDownLatch(1)
    private val stopped = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun ensureReady(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            YoutubeDL.getInstance().init(context.applicationContext)
            ready = true
        }
        val app = context.applicationContext
        Thread({ updateNow(app) }, "mdm-ytdlp-update").apply { isDaemon = true }.start()
    }

    private fun updateNow(app: Context) {
        if (!updateStarted.compareAndSet(false, true)) return
        try {
            val yt = YoutubeDL.getInstance()
            runCatching { yt.updateYoutubeDL(app, YoutubeDL.UpdateChannel.NIGHTLY) }
                .recoverCatching { yt.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
        } finally {
            updateDone.countDown()
        }
    }

    private fun waitForUpdate(app: Context) {
        updateNow(app)
        updateDone.await(120, TimeUnit.SECONDS)
    }

    /** Stops a running download (pause or remove); yt-dlp continues from its .part file next time. */
    fun stop(key: String) {
        stopped += key
        runCatching { YoutubeDL.getInstance().destroyProcessById(key) }
    }

    /** Blocks (never on the main thread). Returns what [Downloads] should do next. */
    fun run(
        context: Context,
        key: String,
        page: String,
        dir: File,
        audio: Boolean,
        referer: String?,
        onProgress: (Progress) -> Unit,
    ): Outcome {
        val app = context.applicationContext
        stopped -= key
        runCatching { ensureReady(app) }.onFailure {
            return Outcome.Failed(explain(it.message.orEmpty()), "yt-dlp could not start: ${it.message}")
        }
        dir.mkdirs()
        val log = StringBuilder()
        fun attempt(label: String, extra: List<String>, url: String = page, headers: Map<String, String> = emptyMap()): File? {
            if (key in stopped) return null
            return try {
                ytdlp(key, url, dir, audio, extra, headers.ifEmpty { referer?.let { mapOf("Referer" to it) } ?: emptyMap() }, onProgress)
            } catch (e: Exception) {
                if (key in stopped) return null
                log.append("[$label] ").append(e.message.orEmpty().takeLast(1500)).append("\n\n")
                null
            }
        }

        // 1 and 2: the plain try, then again after an update.
        attempt("default", emptyList())?.let { return Outcome.File(it) }
        if (key in stopped) return Outcome.Stopped
        waitForUpdate(app)
        attempt("after update", emptyList())?.let { return Outcome.File(it) }
        if (key in stopped) return Outcome.Stopped
        // 3: other players (no JavaScript needed) for YouTube.
        attempt("other clients", listOf("--extractor-args", "youtube:player_client=tv,android_vr,web_safari"))?.let { return Outcome.File(it) }
        if (key in stopped) return Outcome.Stopped
        // 4: one file only, no merging.
        attempt("single file", listOf("-f", "b/best"), headers = emptyMap())?.let { return Outcome.File(it) }
        if (key in stopped) return Outcome.Stopped

        // 5: the page analyzer: for sites yt-dlp has no extractor for (or lost).
        val found = Native.invoke("analyze", JSONObject().put("url", page).apply {
            referer?.let { put("headers", JSONObject().put("Referer", it)) }
        })
        val err = found.optString("error").ifEmpty { null }
        if (err != null) log.append("[analyzer] ").append(err).append("\n")
        val cands = found.optJSONArray("candidates")
        val hdr = found.optJSONObject("headers")
        val headers = LinkedHashMap<String, String>().apply { hdr?.keys()?.forEach { put(it, hdr.optString(it)) } }
        val title = found.optString("title").ifEmpty { null }
        if (cands != null) {
            for (i in 0 until cands.length()) {
                if (key in stopped) return Outcome.Stopped
                val c = cands.getJSONObject(i)
                val url = c.getString("url")
                when (c.optString("kind")) {
                    "file" -> return Outcome.Direct(url, headers, title)
                    "hls", "dash", "page" ->
                        attempt("analyzer ${c.optString("kind")}", emptyList(), url = url, headers = headers)?.let { return Outcome.File(it) }
                }
            }
            if (cands.length() == 0) log.append("[analyzer] no media found in the page's source\n")
        }
        if (key in stopped) return Outcome.Stopped
        val detail = log.toString().trim().ifEmpty { "no details" }
        return Outcome.Failed(explain(detail), detail)
    }

    private val PROGRESS = Regex("""(\d+(?:\.\d+)?)%\s+of\s+~?\s*([\d.]+)\s*(B|KiB|MiB|GiB)(?:\s+at\s+([\d.]+)\s*(B|KiB|MiB|GiB)/s)?""")

    private fun unit(v: Double, u: String): Long = (v * when (u) {
        "KiB" -> 1024.0
        "MiB" -> 1024.0 * 1024
        "GiB" -> 1024.0 * 1024 * 1024
        else -> 1.0
    }).toLong()

    private fun ytdlp(
        key: String,
        url: String,
        dir: File,
        audio: Boolean,
        extra: List<String>,
        headers: Map<String, String>,
        onProgress: (Progress) -> Unit,
    ): File {
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--newline")
            addOption("--retries", "5")
            addOption("--fragment-retries", "10")
            addOption("--concurrent-fragments", "4")
            addOption("--socket-timeout", "30")
            addOption("-o", dir.absolutePath + "/%(title).120B [%(id)s].%(ext)s")
            // The finished file's path, printed once everything (merging, conversion) is done.
            addOption("--print", "after_move:filepath")
            addOption("--no-simulate")
            if (audio) {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
            } else {
                addOption("-f", "bv*[height<=?1080]+ba/b[height<=?1080]/b")
                addOption("--merge-output-format", "mp4")
            }
            headers.forEach { (k, v) -> addOption("--add-header", "$k:$v") }
            var i = 0
            while (i < extra.size) {
                // Pairs ("-f", "b") replace the default of the same flag; single flags are added.
                val flag = extra[i]
                if (i + 1 < extra.size && !extra[i + 1].startsWith("-")) {
                    addOption(flag, extra[i + 1])
                    i += 2
                } else {
                    addOption(flag)
                    i += 1
                }
            }
        }
        var title: String? = null
        val response = YoutubeDL.getInstance().execute(request, key) { _, _, line ->
            PROGRESS.find(line)?.let { m ->
                val total = unit(m.groupValues[2].toDouble(), m.groupValues[3])
                val speed = if (m.groupValues[4].isNotEmpty()) unit(m.groupValues[4].toDouble(), m.groupValues[5]) else 0L
                onProgress(Progress(m.groupValues[1].toFloat(), total, speed, title))
            }
            if (line.contains("Destination:")) title = line.substringAfter("Destination:").trim().substringAfterLast('/')
        }
        val out = response.out.lines().map { it.trim() }.filter { it.startsWith("/") }
        val path = out.asReversed().map(::File).firstOrNull { it.isFile }
            ?: error("yt-dlp finished but no file was written")
        return path
    }

    // ---- explaining failures ----

    private fun ar() = Locale.getDefault().language == "ar"

    /** A short plain sentence for yt-dlp's technical text (the raw text stays under "Details"). */
    fun explain(raw: String): String {
        val r = raw.lowercase()
        val (en, arabic) = when {
            "sign in to confirm" in r || "not a bot" in r || "login required" in r || "log in" in r && "cookies" in r ->
                "The site wants you signed in or says you are a bot. Open the page in the built-in browser, sign in, then try again." to
                    "الموقع يطلب تسجيل الدخول أو يعتبرك روبوت. افتح الصفحة في المتصفح الداخلي وسجّل دخولك ثم أعد المحاولة."
            "requested format is not available" in r || "n challenge" in r || "javascript runtime" in r || "nsig" in r ->
                "This site (usually YouTube) changed how it serves video, and no way to read it worked this time. Try again later, or open it in the built-in browser." to
                    "الموقع (غالباً يوتيوب) غيّر طريقة عرض الفيديو ولم تنجح أي طريقة للقراءة هذه المرة. جرّب لاحقاً أو افتحه في المتصفح الداخلي."
            "http error 403" in r || "403" in r && "forbidden" in r ->
                "The site refused the download (403). Open the page in the built-in browser first, then try again." to
                    "الموقع رفض التحميل (403). افتح الصفحة في المتصفح الداخلي أولاً ثم أعد المحاولة."
            "http error 404" in r || "not found" in r && "404" in r ->
                "The video is gone or the link is wrong (404)." to "الفيديو محذوف أو الرابط غير صحيح (404)."
            "private video" in r || "members-only" in r || "premium" in r ->
                "This video is private or for members only." to "هذا الفيديو خاص أو للأعضاء فقط."
            "drm" in r || "protected" in r ->
                "This video is protected (DRM) and cannot be downloaded." to "هذا الفيديو محمي (DRM) ولا يمكن تحميله."
            "unsupported url" in r || "no media found" in r || "no video formats" in r ->
                "No video was found on this page. If it plays in the built-in browser, the app will offer it there." to
                    "ما لقيت فيديو بهذه الصفحة. إذا كان بيشتغل بالمتصفح الداخلي رح يعرضه التطبيق هناك."
            "unable to download" in r || "timed out" in r || "name or service not known" in r || "network is unreachable" in r || "unable to resolve" in r ->
                "No internet connection, or the site did not answer." to "ما في اتصال بالإنترنت أو الموقع ما ردّ."
            "ffmpeg" in r ->
                "Joining picture and sound failed (ffmpeg). Try again; if it repeats, send the Details to the developer." to
                    "فشل دمج الصورة والصوت (ffmpeg). أعد المحاولة، وإذا تكرر ابعت التفاصيل للمطوّر."
            else ->
                "The download failed. Tap Details to see why, and Retry." to "فشل التحميل. اضغط «التفاصيل» لمعرفة السبب ثم أعد المحاولة."
        }
        return if (ar()) arabic else en
    }
}

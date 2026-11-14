package dev.mdm.android

import android.content.Context
import android.os.Environment
import android.webkit.CookieManager
import dev.mdm.android.Native.error
import dev.mdm.android.Native.text
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The downloads list and what runs it: one place that asks the Rust library (every half second)
 * how each running download is, keeps the list on disk so it survives the app's process, and
 * keeps the queue: at most [Prefs.maxSimultaneous] run at once, the rest wait (Status.QUEUED),
 * oldest first. The speed mode's limit is shared between the running downloads.
 */
object Downloads {
    // Chrome on Android, as sites expect a phone's browser to introduce itself.
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items

    private lateinit var app: Context
    private lateinit var listFile: File
    private var started = false

    /** Where files are saved while downloading (Android 8-9 with permission: the public folder itself). */
    val saveDir: File
        get() = Files.legacyPublicDir(app)
            ?: app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: app.filesDir

    fun start(context: Context) {
        synchronized(this) {
            if (started) return
            started = true
        }
        app = context.applicationContext
        listFile = File(app.filesDir, "downloads.json")
        scope.launch {
            Native.invoke("init", JSONObject().put("user_agent", USER_AGENT))
            // What was running when the app's process ended starts again (a download resumes
            // from its state file).
            _items.value = load().map {
                if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED, speed = 0, engineId = 0) else it
            }
            schedule()
            poll()
        }
        // A new speed mode or snail mode changes the limits of what already runs.
        scope.launch { Prefs.speedMode.collect { schedule() } }
        scope.launch { Prefs.snail.collect { applyLimits() } }
    }

    // ---- adding ----

    /**
     * What the person typed, pasted, shared or caught in the browser. [video]: a web page for yt-dlp.
     * [referer]: the page a browser download came from.
     */
    fun add(text: String, video: Boolean = false, referer: String? = null, audio: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            val key = UUID.randomUUID().toString()
            val dir = saveDir.absolutePath
            val item = if (video) {
                Item(key, Kind.VIDEO, t, page = t, dir = dir, title = t, referer = referer, audio = audio)
            } else {
                Item(
                    key, Kind.HTTP, t, dir = dir, referer = referer,
                    title = t.substringBefore('?').substringAfterLast('/').ifEmpty { t },
                )
            }
            _items.update { listOf(item) + it }
            save()
            schedule()
        }
    }

    // ---- the queue ----

    /** Starts queued downloads until [Prefs.maxSimultaneous] run; marks them running right away. */
    private fun schedule() {
        val max = Prefs.maxSimultaneous()
        val toStart = mutableListOf<Item>()
        _items.update { list ->
            toStart.clear()
            var running = list.count { it.status == Status.RUNNING }
            val keys = HashSet<String>()
            // The list is newest first: the oldest waiting download goes first.
            for (candidate in list.asReversed()) {
                if (running >= max) break
                if (candidate.status == Status.QUEUED) {
                    keys += candidate.key
                    running++
                }
            }
            list.map { item ->
                if (item.key in keys) {
                    val started = item.copy(status = Status.RUNNING, error = null)
                    toStart += started
                    started
                } else {
                    item
                }
            }
        }
        if (toStart.isNotEmpty()) DownloadService.ensureRunning(app)
        toStart.forEach { item -> scope.launch { begin(item) } }
        applyLimits()
    }

    /** The speed mode's total limit, divided between the downloads that run (0: none). */
    private fun perDownloadLimit(): Long {
        val total = Prefs.totalLimitBps()
        if (total <= 0) return 0
        val running = _items.value.count { it.status == Status.RUNNING }.coerceAtLeast(1)
        return (total / running).coerceAtLeast(1_000)
    }

    private fun applyLimits() {
        val per = perDownloadLimit()
        _items.value.filter { it.status == Status.RUNNING && it.engineId != 0L }.forEach {
            Native.invoke("http_limit", JSONObject().put("id", it.engineId).put("bps", per))
        }
    }

    private fun stillRunning(key: String): Boolean =
        _items.value.any { it.key == key && it.status == Status.RUNNING }

    // ---- starting, pausing, resuming ----

    /** Starts (or starts again) what [item] is. [schedule] already marked it running. */
    private fun begin(item: Item) {
        when (item.kind) {
            Kind.VIDEO -> beginVideo(item)
            Kind.HTTP -> beginHttp(item, null, emptyMap())
        }
    }

    /**
     * A page with a video: [VideoEngine] walks its ladder (yt-dlp, update, other clients, single
     * file, the Rust page analyzer). A finished file is exported like any other; a plain file the
     * analyzer found goes to the HTTP engine; a failure is explained and kept with its details.
     */
    private fun beginVideo(item: Item) {
        var lastUi = 0L
        val outcome = VideoEngine.run(
            app, item.key, item.page ?: item.source, File(item.dir), item.audio, item.referer,
        ) { p ->
            val now = System.currentTimeMillis()
            if (now - lastUi < 400 || !stillRunning(item.key)) return@run
            lastUi = now
            change(item.key) {
                it.copy(
                    done = if (p.total > 0) (p.total * p.pct / 100f).toLong() else it.done,
                    total = if (p.total > 0) p.total else it.total,
                    speed = p.speed,
                    title = p.title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                )
            }
        }
        when (outcome) {
            is VideoEngine.Outcome.Stopped -> Unit // paused or removed: pause()/remove() did the rest
            is VideoEngine.Outcome.Failed -> if (stillRunning(item.key)) fail(item.key, outcome.short, outcome.detail)
            is VideoEngine.Outcome.File -> {
                val size = outcome.path.length()
                change(item.key) { it.copy(title = outcome.path.name, speed = 0, done = size, total = size) }
                finishExport(item.key, outcome.path.absolutePath, size)
            }
            is VideoEngine.Outcome.Direct -> {
                change(item.key) { it.copy(source = outcome.url, title = outcome.title ?: it.title) }
                beginHttp(item.copy(source = outcome.url), null, outcome.headers)
            }
        }
    }

    /** [extra]: what yt-dlp sent to find a video's stream; the stream's address is only good with it. */
    private fun headersFor(item: Item, extra: Map<String, String>): JSONObject {
        val h = JSONObject()
        extra.forEach { (k, v) -> h.put(k, v) }
        if (!h.has("Referer")) item.referer?.let { h.put("Referer", it) }
        // Cookies the in-app browser holds for this site (a login, a "human check" already passed).
        val cookie = runCatching { CookieManager.getInstance().getCookie(item.source) }.getOrNull()
        if (!cookie.isNullOrEmpty() && !h.has("Cookie")) h.put("Cookie", cookie)
        return h
    }

    private fun beginHttp(item: Item, name: String?, extraHeaders: Map<String, String>) {
        // Paused while the address was being asked for: don't start.
        if (!stillRunning(item.key)) return
        val limit = perDownloadLimit()
        val headers = headersFor(item, extraHeaders)
        val state = item.stateFile
        val r = if (state != null && File(state).exists()) {
            Native.invoke(
                "http_resume",
                JSONObject().put("state_file", state).put("connections", 8).put("limit", limit).put("headers", headers),
            )
        } else {
            val args = JSONObject().put("url", item.source).put("dir", item.dir)
                .put("connections", 8).put("limit", limit).put("headers", headers)
            if (name != null) args.put("name", name)
            Native.invoke("http_start", args)
        }
        r.error()?.let { return fail(item.key, it) }
        change(item.key) { it.copy(engineId = r.getLong("id")) }
    }

    fun pause(key: String) {
        val item = _items.value.firstOrNull { it.key == key } ?: return
        // First the status, so the poll stops looking at it before the engine lets go of it.
        change(key) { it.copy(status = Status.PAUSED, speed = 0) }
        if (item.kind == Kind.VIDEO) VideoEngine.stop(key)
        scope.launch {
            if (item.engineId != 0L) {
                Native.invoke("http_cancel", JSONObject().put("id", item.engineId))
                // Wait for the workers to stop and the state to be written, then let go.
                Native.invoke("http_finish", JSONObject().put("id", item.engineId))
            }
            change(key) { it.copy(status = Status.PAUSED, speed = 0, engineId = 0) }
            save()
            schedule()
        }
    }

    fun resume(key: String) {
        scope.launch {
            change(key) { it.copy(status = Status.QUEUED, error = null, detail = null) }
            save()
            schedule()
        }
    }

    fun pauseAll() = _items.value.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
        .forEach { pause(it.key) }

    fun resumeAll() = _items.value.filter { it.status == Status.PAUSED || it.status == Status.FAILED }
        .forEach { resume(it.key) }

    fun remove(key: String, deleteFile: Boolean) {
        val item = _items.value.firstOrNull { it.key == key } ?: return
        // Out of the list first (same reason as in pause).
        _items.update { list -> list.filterNot { it.key == key } }
        if (item.kind == Kind.VIDEO) VideoEngine.stop(key)
        scope.launch {
            if (item.engineId != 0L) {
                Native.invoke("http_cancel", JSONObject().put("id", item.engineId))
                Native.invoke("http_finish", JSONObject().put("id", item.engineId))
            }
            if (deleteFile) {
                item.file?.let { File(it).delete() }
                item.uri?.let { Files.deletePublic(app, it) }
            }
            item.stateFile?.let { File(it).delete() }
            _items.update { list -> list.filterNot { it.key == key } }
            save()
            schedule()
        }
    }

    fun clearCompleted() {
        scope.launch {
            _items.update { list -> list.filterNot { it.status == Status.DONE } }
            save()
        }
    }

    // ---- watching ----

    private suspend fun poll() {
        var sinceSave = 0
        while (scope.isActive) {
            delay(500)
            _items.value.filter { it.status == Status.RUNNING }.forEach { tick(it) }
            if (++sinceSave >= 10) {
                sinceSave = 0
                save()
            }
        }
    }

    /** True while [item] is still the running download the poll saw (not paused, removed or restarted since). */
    private fun isCurrent(item: Item): Boolean =
        _items.value.any { it.key == item.key && it.status == Status.RUNNING && it.engineId == item.engineId }

    private fun tick(item: Item) {
        if (item.engineId == 0L) return // still starting (a video's address is being asked for), or being saved
        val s = Native.invoke("http_snapshot", JSONObject().put("id", item.engineId))
        // Pause or remove may have let go of the engine's download since the poll's list was taken:
        // "no such download" then is not a failure.
        if (!isCurrent(item)) return
        s.error()?.let { return fail(item.key, it) }
        change(item.key) {
            it.copy(
                done = s.optLong("downloaded"),
                total = s.optLong("size", it.total),
                speed = s.optLong("speed"),
                file = s.text("file").ifEmpty { it.file },
                stateFile = s.text("state_file").ifEmpty { it.stateFile },
            )
        }
        when (s.optString("state")) {
            "Done" -> {
                val r = Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                r.error()?.let { return fail(item.key, it) }
                val path = r.getString("path")
                val size = r.getLong("size")
                // The engine is let go: no more polling of this one (engineId 0), while the copy
                // into the public folder runs on its own coroutine, so a big file doesn't stop the
                // other downloads' progress.
                change(item.key) { it.copy(speed = 0, engineId = 0, done = size, total = size) }
                scope.launch { finishExport(item.key, path, size) }
            }
            "Failed" -> {
                Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                fail(item.key, s.text("error").ifEmpty { "The download failed (no reason given by the engine)" })
            }
        }
    }

    /** Android 10+: into the public Download/MDM folder, and the private copy goes. */
    private fun finishExport(key: String, path: String, size: Long) {
        val publicUri = Files.exportToPublicDownloads(app, File(path))
        if (publicUri != null) File(path).delete()
        change(key) {
            it.copy(
                status = Status.DONE, speed = 0, engineId = 0,
                file = if (publicUri != null) null else path,
                uri = publicUri?.toString(),
                done = size, total = size,
            )
        }
        save()
        schedule()
    }

    // ---- the list ----

    private fun change(key: String, f: (Item) -> Item) {
        _items.update { list -> list.map { if (it.key == key) f(it) else it } }
    }

    private fun fail(key: String, why: String, detail: String? = null) {
        change(key) { it.copy(status = Status.FAILED, speed = 0, engineId = 0, error = why, detail = detail ?: why) }
        save()
        schedule()
    }

    @Synchronized
    private fun save() {
        runCatching { listFile.writeText(Item.listToJson(_items.value)) }
    }

    private fun load(): List<Item> =
        runCatching { if (listFile.exists()) Item.listFromJson(listFile.readText()) else emptyList() }
            .getOrDefault(emptyList())
}

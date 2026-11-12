package dev.mdm.android

import android.content.Context
import android.os.Environment
import dev.mdm.android.Native.error
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
 * how each running download is, and keeps the list on disk so it survives the app's process.
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
    private var torrentsUp = false

    /** Where files are saved: the app's own Downloads folder (no storage permission needed). */
    val saveDir: File get() = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir

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
            // from its state file, a torrent from its resume data).
            _items.value = load().map {
                if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED, speed = 0) else it
            }
            _items.value.filter { it.status == Status.QUEUED }.forEach { begin(it) }
            poll()
        }
    }

    // ---- adding ----

    /** What the person typed, pasted or shared. [video]: a web page for yt-dlp. */
    fun add(text: String, video: Boolean = false) {
        val t = text.trim()
        if (t.isEmpty()) return
        scope.launch {
            val key = UUID.randomUUID().toString()
            val dir = saveDir.absolutePath
            val item = when {
                t.startsWith("magnet:", ignoreCase = true) ->
                    Item(key, Kind.TORRENT, t, dir = dir, title = magnetName(t))
                video -> Item(key, Kind.VIDEO, t, page = t, dir = dir, title = t)
                t.substringBefore('?').endsWith(".torrent", ignoreCase = true) &&
                    t.startsWith("http", ignoreCase = true) -> Item(key, Kind.TORRENT, t, dir = dir, title = t)
                else -> Item(key, Kind.HTTP, t, dir = dir, title = t.substringBefore('?').substringAfterLast('/').ifEmpty { t })
            }
            _items.update { listOf(item) + it }
            save()
            begin(item)
        }
    }

    /** A .torrent file the person picked: its bytes are kept in the app's folder, so it can start again later. */
    fun addTorrentFile(bytes: ByteArray, name: String) {
        scope.launch {
            val key = UUID.randomUUID().toString()
            val saved = File(File(app.filesDir, "torrents").apply { mkdirs() }, "$key.torrent")
            saved.writeBytes(bytes)
            val item = Item(key, Kind.TORRENT, saved.absolutePath, dir = saveDir.absolutePath, title = name)
            _items.update { listOf(item) + it }
            save()
            begin(item)
        }
    }

    private fun magnetName(magnet: String): String =
        Regex("[?&]dn=([^&]+)").find(magnet)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: magnet.take(60)

    // ---- starting, pausing, resuming ----

    /** Starts (or starts again) what [item] is; marks it running, or failed with the reason. */
    private fun begin(item: Item) {
        change(item.key) { it.copy(status = Status.RUNNING, error = null) }
        DownloadService.ensureRunning(app)
        when (item.kind) {
            Kind.TORRENT -> beginTorrent(item)
            Kind.VIDEO -> {
                // The stream's address is short-lived: always ask for a fresh one.
                val found = runCatching { VideoResolver.resolve(app, item.page ?: item.source) }
                    .getOrElse { return fail(item.key, it.message ?: "yt-dlp failed") }
                val name = found.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + "." + found.ext
                change(item.key) { it.copy(source = found.url, title = found.title) }
                beginHttp(item.copy(source = found.url), name)
            }
            Kind.HTTP -> beginHttp(item, null)
        }
    }

    private fun beginHttp(item: Item, name: String?) {
        val args = JSONObject().put("url", item.source).put("dir", item.dir).put("connections", 8)
        if (name != null) args.put("name", name)
        val state = item.stateFile
        val r = if (state != null && File(state).exists()) {
            Native.invoke("http_resume", JSONObject().put("state_file", state).put("connections", 8))
        } else {
            Native.invoke("http_start", args)
        }
        r.error()?.let { return fail(item.key, it) }
        change(item.key) { it.copy(engineId = r.getLong("id")) }
    }

    private fun beginTorrent(item: Item) {
        if (!torrentsUp) {
            val r = Native.invoke(
                "torrent_start",
                JSONObject().put("state_dir", File(app.filesDir, "torrent-state").absolutePath),
            )
            r.error()?.let { return fail(item.key, it) }
            torrentsUp = true
        }
        // Already in the session (its resume data was read at start): just let it run.
        if (item.hash != null && Native.invoke("torrent_resume", JSONObject().put("hash", item.hash)).error() == null) {
            return
        }
        // A magnet waits for the swarm's file list: this call can take a minute and a half.
        val r = Native.invoke(
            "torrent_add",
            JSONObject().put("source", item.source).put("dir", item.dir),
        )
        r.error()?.let { return fail(item.key, it) }
        change(item.key) { it.copy(hash = r.getString("hash"), title = r.getString("name"), total = r.getLong("total")) }
    }

    fun pause(key: String) {
        val item = _items.value.firstOrNull { it.key == key } ?: return
        scope.launch {
            when (item.kind) {
                Kind.TORRENT -> item.hash?.let { Native.invoke("torrent_pause", JSONObject().put("hash", it)) }
                else -> if (item.engineId != 0L) {
                    Native.invoke("http_cancel", JSONObject().put("id", item.engineId))
                    // Wait for the workers to stop and the state to be written, then let go.
                    Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                }
            }
            change(key) { it.copy(status = Status.PAUSED, speed = 0, engineId = 0) }
            save()
        }
    }

    fun resume(key: String) {
        val item = _items.value.firstOrNull { it.key == key } ?: return
        scope.launch { begin(item) }
    }

    fun remove(key: String, deleteFile: Boolean) {
        val item = _items.value.firstOrNull { it.key == key } ?: return
        scope.launch {
            when (item.kind) {
                Kind.TORRENT -> item.hash?.let {
                    Native.invoke("torrent_remove", JSONObject().put("hash", it).put("delete_files", deleteFile))
                }
                else -> {
                    if (item.engineId != 0L) {
                        Native.invoke("http_cancel", JSONObject().put("id", item.engineId))
                        Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                    }
                    if (deleteFile) item.file?.let { File(it).delete() }
                    item.stateFile?.let { File(it).delete() }
                }
            }
            _items.update { list -> list.filterNot { it.key == key } }
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

    private fun tick(item: Item) {
        if (item.kind == Kind.TORRENT) return tickTorrent(item)
        if (item.engineId == 0L) return // still starting (a video's address is being asked for)
        val s = Native.invoke("http_snapshot", JSONObject().put("id", item.engineId))
        s.error()?.let { return fail(item.key, it) }
        change(item.key) {
            it.copy(
                done = s.optLong("downloaded"),
                total = s.optLong("size", it.total),
                speed = s.optLong("speed"),
                file = s.optString("file").ifEmpty { it.file },
                stateFile = s.optString("state_file").ifEmpty { it.stateFile },
            )
        }
        when (s.optString("state")) {
            "Done" -> {
                val r = Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                r.error()?.let { return fail(item.key, it) }
                change(item.key) {
                    it.copy(status = Status.DONE, speed = 0, engineId = 0, file = r.getString("path"), done = r.getLong("size"), total = r.getLong("size"))
                }
                save()
            }
            "Failed" -> {
                Native.invoke("http_finish", JSONObject().put("id", item.engineId))
                fail(item.key, s.optString("error").ifEmpty { "failed" })
            }
        }
    }

    private fun tickTorrent(item: Item) {
        val hash = item.hash ?: return
        val s = Native.invoke("torrent_stats", JSONObject().put("hash", hash))
        s.error()?.let { return } // not in the session yet
        change(item.key) {
            it.copy(
                done = s.optLong("done"),
                total = s.optLong("total"),
                speed = s.optDouble("down_speed").toLong(),
                status = when (s.optString("phase")) {
                    "Finished" -> Status.DONE
                    "Error" -> Status.FAILED
                    "Paused" -> Status.PAUSED
                    else -> Status.RUNNING
                },
                error = s.optString("error").ifEmpty { null },
            )
        }
    }

    // ---- the list ----

    private fun change(key: String, f: (Item) -> Item) {
        _items.update { list -> list.map { if (it.key == key) f(it) else it } }
    }

    private fun fail(key: String, why: String) {
        change(key) { it.copy(status = Status.FAILED, speed = 0, error = why) }
        save()
    }

    @Synchronized
    private fun save() {
        runCatching { listFile.writeText(Item.listToJson(_items.value)) }
    }

    private fun load(): List<Item> =
        runCatching { if (listFile.exists()) Item.listFromJson(listFile.readText()) else emptyList() }
            .getOrDefault(emptyList())
}

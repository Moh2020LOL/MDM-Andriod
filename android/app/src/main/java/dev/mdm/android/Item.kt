package dev.mdm.android

import org.json.JSONArray
import org.json.JSONObject

/** HTTP/HTTPS/FTP/FTPS link, or a video page that yt-dlp turns into a direct stream. (Torrent: removed, see docs/android-reference.md.) */
enum class Kind { HTTP, VIDEO }

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** What the row's icon shows, from the file's extension. */
enum class FileType { VIDEO, AUDIO, ARCHIVE, IMAGE, DOCUMENT, APP, OTHER }

/** One line of the list. Immutable: the screen compares lists, the manager replaces items. */
data class Item(
    val key: String,
    val kind: Kind,
    /** The link; a video's direct stream address once yt-dlp found it. */
    val source: String,
    /** A video's page, to ask for a fresh stream address when the old one has expired. */
    val page: String? = null,
    val dir: String,
    val title: String,
    val status: Status = Status.QUEUED,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    /** The engine's id for the running download (0: none; ids don't outlive the app's process). */
    val engineId: Long = 0,
    /** What `http_resume` takes. */
    val stateFile: String? = null,
    /** The file in the app's own folder while downloading (and after, when it could not be exported). */
    val file: String? = null,
    /** The finished file's address in the public Downloads folder (Android 10+), when exported. */
    val uri: String? = null,
    /** The page a browser download came from: sent as Referer, many sites refuse without it. */
    val referer: String? = null,
    val added: Long = System.currentTimeMillis(),
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    val type: FileType get() = fileTypeOf(file?.substringAfterLast('/') ?: title)

    /** Seconds left at the current speed, or -1 when unknown. */
    val etaSeconds: Long get() = if (speed > 0 && total > done) (total - done) / speed else -1

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("kind", kind.name).put("source", source).put("page", page)
        .put("dir", dir).put("title", title).put("status", status.name)
        .put("done", done).put("total", total)
        .put("stateFile", stateFile).put("file", file).put("uri", uri).put("referer", referer)
        .put("added", added).put("error", error)

    companion object {
        fun fromJson(o: JSONObject): Item = Item(
            key = o.getString("key"),
            // A list saved by the first version may hold TORRENT: valueOf throws and the row is skipped.
            kind = Kind.valueOf(o.getString("kind")),
            source = o.getString("source"),
            page = o.optString("page").ifEmpty { null },
            dir = o.getString("dir"),
            title = o.getString("title"),
            status = Status.valueOf(o.getString("status")),
            done = o.optLong("done"),
            total = o.optLong("total"),
            stateFile = o.optString("stateFile").ifEmpty { null },
            file = o.optString("file").ifEmpty { null },
            uri = o.optString("uri").ifEmpty { null },
            referer = o.optString("referer").ifEmpty { null },
            added = o.optLong("added", System.currentTimeMillis()),
            error = o.optString("error").ifEmpty { null },
        )

        fun listFromJson(text: String): List<Item> {
            val arr = JSONArray(text)
            return (0 until arr.length()).mapNotNull {
                runCatching { fromJson(arr.getJSONObject(it)) }.getOrNull()
            }
        }

        fun listToJson(items: List<Item>): String =
            JSONArray().also { a -> items.forEach { a.put(it.toJson()) } }.toString()
    }
}

fun fileTypeOf(name: String): FileType = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "flv", "wmv", "ts" -> FileType.VIDEO
    "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "wma" -> FileType.AUDIO
    "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso" -> FileType.ARCHIVE
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileType.IMAGE
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub" -> FileType.DOCUMENT
    "apk", "xapk", "apks" -> FileType.APP
    else -> FileType.OTHER
}

package dev.mdm.android

import org.json.JSONArray
import org.json.JSONObject

enum class Kind { HTTP, TORRENT, VIDEO }

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED }

/** One line of the list. Immutable: the screen compares lists, the manager replaces items. */
data class Item(
    val key: String,
    val kind: Kind,
    /** The link, magnet link, or (a .torrent) the saved file's path; a video's direct stream address. */
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
    /** A torrent's info hash. */
    val hash: String? = null,
    /** What `http_resume` takes. */
    val stateFile: String? = null,
    val file: String? = null,
    val error: String? = null,
) {
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    fun toJson(): JSONObject = JSONObject()
        .put("key", key).put("kind", kind.name).put("source", source).put("page", page)
        .put("dir", dir).put("title", title).put("status", status.name)
        .put("done", done).put("total", total)
        .put("hash", hash).put("stateFile", stateFile).put("file", file).put("error", error)

    companion object {
        fun fromJson(o: JSONObject): Item = Item(
            key = o.getString("key"),
            kind = Kind.valueOf(o.getString("kind")),
            source = o.getString("source"),
            page = o.optString("page").ifEmpty { null },
            dir = o.getString("dir"),
            title = o.getString("title"),
            status = Status.valueOf(o.getString("status")),
            done = o.optLong("done"),
            total = o.optLong("total"),
            hash = o.optString("hash").ifEmpty { null },
            stateFile = o.optString("stateFile").ifEmpty { null },
            file = o.optString("file").ifEmpty { null },
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

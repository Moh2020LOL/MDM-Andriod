package dev.mdm.android

import android.net.Uri

/**
 * Decides whether a link is something worth offering to download as a video:
 *  - [directMedia]: the link IS a media file (.mp4, .webm, .mp3 ...): the engine downloads it as it is.
 *  - [videoPage]: the link is a page of a site yt-dlp understands (a YouTube watch page ...): handed
 *    to [VideoEngine], which downloads it (and falls back to the Rust page analyzer).
 * Used by the in-app browser, by [Overlay] through [LinkWatcherService], and by the share sheet.
 */
object VideoDetector {
    enum class Match { DIRECT, PAGE }

    private val mediaExt = setOf(
        "mp4", "m4v", "webm", "mkv", "mov", "avi", "3gp", "flv", "wmv",
        "mp3", "m4a", "aac", "ogg", "opus", "wav", "flac",
    )

    private val pages = listOf(
        // YouTube: watch pages, shorts, live, and the short links.
        Regex("""^https?://((www|m|music)\.)?youtube\.com/(watch\?([^#]*&)?v=|shorts/|live/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://youtu\.be/[\w-]{6,}""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?vimeo\.com/\d+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|m|web)\.)?facebook\.com/(watch/?\?([^#]*&)?v=|[^/?#]+/(videos|reel)/|reel/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://fb\.watch/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?instagram\.com/(reel|reels|p|tv)/[\w-]+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|m|vm|vt)\.)?tiktok\.com/(@[\w.]+/video/\d+|[\w-]{6,}/?$)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|mobile)\.)?(twitter|x)\.com/[^/?#]+/status/\d+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|m)\.)?dailymotion\.com/video/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://dai\.ly/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|old|new)\.)?reddit\.com/r/[^/?#]+/comments/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|m)\.)?twitch\.tv/(videos/\d+|[^/?#]+/clip/)""", RegexOption.IGNORE_CASE),
        Regex("""^https?://clips\.twitch\.tv/""", RegexOption.IGNORE_CASE),
        Regex("""^https?://(www\.)?streamable\.com/\w+""", RegexOption.IGNORE_CASE),
        Regex("""^https?://((www|m)\.)?bilibili\.com/video/""", RegexOption.IGNORE_CASE),
    )

    /** [mime]: the response's Content-Type when the caller knows it (a browser's request often doesn't). */
    fun directMedia(url: String, mime: String? = null): Boolean {
        if (mime != null && (mime.startsWith("video/") || mime.startsWith("audio/"))) return true
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase() ?: return false
        return path.substringAfterLast('.', "") in mediaExt
    }

    fun videoPage(url: String): Boolean = pages.any { it.containsMatchIn(url) }

    private val fileExt = setOf(
        "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "iso", "apk", "xapk", "pdf", "doc", "docx",
        "xls", "xlsx", "ppt", "pptx", "txt", "epub", "jpg", "jpeg", "png", "gif", "webp", "exe", "msi", "dmg",
    )

    /**
     * A link that is probably a web page (no file extension, or .html/.php ...): worth ANALYZING
     * for the media it holds, instead of downloading the page itself.
     */
    fun looksLikePage(url: String): Boolean {
        if (!url.startsWith("http", ignoreCase = true)) return false
        if (directMedia(url)) return false
        val path = runCatching { Uri.parse(url).path }.getOrNull()?.lowercase().orEmpty()
        val ext = path.substringAfterLast('.', "").takeIf { '/' !in it }.orEmpty()
        return ext.isEmpty() || ext in setOf("html", "htm", "php", "asp", "aspx", "jsp", "shtml") || (ext !in fileExt && ext.length > 5)
    }

    fun classify(url: String, mime: String? = null): Match? = when {
        !url.startsWith("http", ignoreCase = true) -> null
        videoPage(url) -> Match.PAGE
        directMedia(url, mime) -> Match.DIRECT
        else -> null
    }
}

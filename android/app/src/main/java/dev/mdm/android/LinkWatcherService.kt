package dev.mdm.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL. Watches the address bar of the phone's browsers and, when the page is a video page
 * (YouTube, Instagram, TikTok ... see [VideoDetector]) or a direct media file, shows [Overlay].
 * It only reads the browser's address bar text; it never reads page content, never types, never
 * clicks. The person switches it on in Android's Accessibility settings (Permissions.kt opens that
 * screen) and can switch the overlay off in the app's settings at any time.
 *
 * The address bars' view ids are the ones the browsers use today; a browser that renames its id
 * is simply not watched until [URL_BARS] is updated (docs/android-reference.md, "Known limits").
 */
class LinkWatcherService : AccessibilityService() {
    private var lastUrl = ""
    private var lastAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!Prefs.overlayEnabled.value) return
        val pkg = event.packageName?.toString() ?: return
        val barId = URL_BARS[pkg] ?: return
        val root = rootInActiveWindow ?: return
        val node = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull() ?: return
        val url = normalize(node.text?.toString()) ?: return
        val now = System.currentTimeMillis()
        // The browser fires many events for one page: the same address counts once a minute.
        if (url == lastUrl && now - lastAt < 60_000) return
        val match = VideoDetector.classify(url) ?: return
        lastUrl = url
        lastAt = now
        Overlay.show(this, url, video = match == VideoDetector.Match.PAGE)
    }

    override fun onInterrupt() {}

    companion object {
        /** Package name -> the id of its address bar. */
        val URL_BARS = mapOf(
            "com.android.chrome" to "com.android.chrome:id/url_bar",
            "com.chrome.beta" to "com.chrome.beta:id/url_bar",
            "org.mozilla.firefox" to "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix" to "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "com.microsoft.emmx" to "com.microsoft.emmx:id/url_bar",
            "com.brave.browser" to "com.brave.browser:id/url_bar",
            "com.opera.browser" to "com.opera.browser:id/url_field",
            "com.sec.android.app.sbrowser" to "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.kiwibrowser.browser" to "com.kiwibrowser.browser:id/url_bar",
            "com.vivaldi.browser" to "com.vivaldi.browser:id/url_bar",
        )

        /** The text of an address bar as a link, or null when it isn't one (a search being typed). */
        fun normalize(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty() || t.contains(' ') || !t.contains('.')) return null
            return if (t.contains("://")) t else "https://$t"
        }
    }
}

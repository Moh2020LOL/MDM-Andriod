package dev.mdm.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val analyzed = HashSet<String>()

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
        // The browser's known address bar first; when it renamed that id, any view of the page
        // whose id looks like an address bar (still only the address, never the page's content).
        val text = root.findAccessibilityNodeInfosByViewId(barId).firstOrNull()?.text?.toString()
            ?: findAddressText(root)
        val url = normalize(text) ?: return
        val now = System.currentTimeMillis()
        // The browser fires many events for one page: the same address counts once a minute.
        if (url == lastUrl && now - lastAt < 60_000) return
        val match = VideoDetector.classify(url)
        lastUrl = url
        lastAt = now
        if (match == null) {
            // Deep detection (optional, off by default): ANY page is read by the Rust analyzer,
            // so a site nobody wrote an extractor for still gets the "video found" window.
            if (Prefs.deepDetect.value && VideoDetector.looksLikePage(url)) deepCheck(url)
            return
        }
        Overlay.show(this, url, video = match == VideoDetector.Match.PAGE)
    }

    private fun deepCheck(url: String) {
        if (analyzed.size > 200) analyzed.clear()
        if (!analyzed.add(url)) return
        scope.launch {
            val r = Native.invoke("analyze", JSONObject().put("url", url))
            val c = r.optJSONArray("candidates")
            if (c != null && c.length() > 0) Overlay.show(applicationContext, url, video = true)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * Fallback for a renamed address bar: a breadth-first look (at most [MAX_NODES] views) for a
     * view whose id says it is the address (url, omnibar, location bar) and whose text is a link.
     */
    private fun findAddressText(root: AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_NODES) {
            val n = queue.removeFirst()
            seen++
            val id = n.viewIdResourceName?.lowercase()
            if (id != null && ADDRESS_HINTS.any { it in id }) {
                val t = n.text?.toString()
                if (normalize(t) != null) return t
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

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
            "org.mozilla.firefox_beta" to "org.mozilla.firefox_beta:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix.nightly" to "org.mozilla.fenix.nightly:id/mozac_browser_toolbar_url_view",
            "com.duckduckgo.mobile.android" to "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.sec.android.app.sbrowser.beta" to "com.sec.android.app.sbrowser.beta:id/location_bar_edit_text",
            "com.yandex.browser" to "com.yandex.browser:id/bro_omnibar_address_title_text",
        )

        private const val MAX_NODES = 400
        private val ADDRESS_HINTS = listOf("url", "omnibar", "location_bar", "address")

        /** The text of an address bar as a link, or null when it isn't one (a search being typed). */
        fun normalize(raw: String?): String? {
            val t = raw?.trim().orEmpty()
            if (t.isEmpty() || t.contains(' ') || !t.contains('.')) return null
            return if (t.contains("://")) t else "https://$t"
        }
    }
}

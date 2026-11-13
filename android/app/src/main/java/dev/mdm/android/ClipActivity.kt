package dev.mdm.android

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Toast

/**
 * A transparent screen that lives for a moment: Android 10+ lets only the app in front read the
 * clipboard, so the floating button opens this, reads the link, adds it (a web page is analyzed for
 * its video) and closes.
 */
class ClipActivity : Activity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(applicationContext)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        val text = getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val link = firstLink(text)
        if (link == null) {
            Toast.makeText(this, R.string.clip_no_link, Toast.LENGTH_LONG).show()
        } else {
            val page = VideoDetector.videoPage(link) || VideoDetector.looksLikePage(link)
            Downloads.add(link, video = page)
            Toast.makeText(this, R.string.overlay_added, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

private val LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** The first web link inside shared or copied text ("Title https://…" shares are common). */
fun firstLink(text: String): String? = LINK.find(text)?.value?.trimEnd('.', ',', ')', ']')

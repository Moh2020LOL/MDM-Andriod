package dev.mdm.android

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private enum class Route { LIST, SETTINGS, BROWSER, PERMISSIONS }

class MainActivity : ComponentActivity() {
    /** A link that arrived from outside (browser's share sheet, an ftp link): the add dialog opens with it. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val theme by Prefs.theme.collectAsStateWithLifecycle()
            val onboardingDone by Prefs.onboardingDone.collectAsStateWithLifecycle()
            var route by remember { mutableStateOf(if (onboardingDone) Route.LIST else Route.PERMISSIONS) }

            MdmTheme(theme) {
                BackHandler(enabled = route != Route.LIST && route != Route.BROWSER) {
                    // Leaving the guided permissions screen by "back" counts as done: it is not a trap.
                    if (route == Route.PERMISSIONS) Prefs.setOnboardingDone(true)
                    route = Route.LIST
                }
                when (route) {
                    Route.LIST -> DownloadsScreen(
                        onBrowser = { route = Route.BROWSER },
                        onSettings = { route = Route.SETTINGS },
                        prefill = incoming,
                        onPrefillHandled = { incoming = null },
                    )
                    Route.SETTINGS -> SettingsScreen(
                        onBack = { route = Route.LIST },
                        onPermissions = { route = Route.PERMISSIONS },
                    )
                    Route.BROWSER -> BrowserScreen(onClose = { route = Route.LIST })
                    Route.PERMISSIONS -> PermissionsScreen(onDone = {
                        Prefs.setOnboardingDone(true)
                        route = Route.LIST
                    })
                }
            }
        }
    }

    /** The link last offered from the clipboard, so the same one is not offered twice. */
    private var lastClip: String? = null

    override fun onResume() {
        super.onResume()
        BubbleService.sync(this)
    }

    // Android 10+ lets only the focused app read the clipboard: a copied link is offered right away.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || incoming != null) return
        val text = runCatching {
            getSystemService(ClipboardManager::class.java).primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()
        val link = firstLink(text) ?: return
        if (link != lastClip) {
            lastClip = link
            incoming = link
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> incoming = intent.data?.toString()
            Intent.ACTION_SEND -> incoming = intent.getStringExtra(Intent.EXTRA_TEXT)?.let { firstLink(it) ?: it }
        }
    }
}

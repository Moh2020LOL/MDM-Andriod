package dev.mdm.android

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> incoming = intent.data?.toString()
            Intent.ACTION_SEND -> incoming = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

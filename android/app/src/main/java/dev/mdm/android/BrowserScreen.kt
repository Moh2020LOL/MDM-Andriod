package dev.mdm.android

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A small built-in browser, the phone's counterpart of the desktop browser extension: while a page
 * loads it watches the requests, and offers
 *  - "Download video": the page is a video page (VideoDetector.videoPage) -> yt-dlp finds the stream;
 *  - "N media files found": the page itself requested .mp4/.webm/.mp3 ... files -> download one;
 * and any file the page offers as a download goes straight to the list.
 * This works with no special permission; the floating window over OTHER apps is [Overlay].
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onClose: () -> Unit) {
    var address by remember { mutableStateOf("https://www.google.com") }
    var pageUrl by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var showMedia by remember { mutableStateOf(false) }
    val media = remember { mutableStateListOf<String>() }
    var web by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current

    BackHandler {
        val w = web
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                TextField(
                    value = address,
                    onValueChange = { address = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { web?.loadUrl(toUrl(address)) }),
                )
            },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = androidx.compose.ui.graphics.Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MdmColors.Blue),
        )
        if (progress in 1..99) {
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            pageUrl = url
                            address = url
                            media.clear()
                        }

                        // Runs on a background thread: only look, and hand the result to the main thread.
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val u = request.url.toString()
                            if (VideoDetector.directMedia(u)) {
                                view.post { if (u !in media && media.size < 30) media.add(u) }
                            }
                            return null
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    // A file the page offers as a download goes to the list, with the page as Referer.
                    setDownloadListener { url, _, _, _, _ ->
                        Downloads.add(url, referer = this.url)
                    }
                    web = this
                    loadUrl(toUrl(address))
                }
            },
            onRelease = { it.destroy(); web = null },
        )
        val isVideoPage = pageUrl.isNotEmpty() && VideoDetector.videoPage(pageUrl)
        if (isVideoPage || media.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        if (isVideoPage) stringResource(R.string.browser_video_found)
                        else stringResource(R.string.browser_media_found, media.size),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Button(onClick = {
                        if (isVideoPage) {
                            Downloads.add(pageUrl, video = true)
                            android.widget.Toast.makeText(context, R.string.overlay_added, android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            showMedia = true
                        }
                    }) { Text(stringResource(R.string.overlay_download)) }
                }
            }
        }
    }

    if (showMedia) {
        AlertDialog(
            onDismissRequest = { showMedia = false },
            title = { Text(stringResource(R.string.browser_pick_media)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    media.forEach { u ->
                        TextButton(onClick = {
                            Downloads.add(u, referer = pageUrl)
                            showMedia = false
                        }) {
                            Text(
                                u.substringBefore('?').substringAfterLast('/').ifEmpty { u },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showMedia = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** What the address bar holds, as a loadable link: a link as it is, a bare site with https, anything else a search. */
private fun toUrl(text: String): String {
    val t = text.trim()
    return when {
        t.contains("://") -> t
        t.contains('.') && !t.contains(' ') -> "https://$t"
        else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }
}

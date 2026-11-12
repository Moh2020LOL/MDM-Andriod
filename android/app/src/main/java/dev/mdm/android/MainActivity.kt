package dev.mdm.android

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    /** A link that arrived from outside (browser, share sheet): the add dialog opens with it. */
    private var incoming by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent { MaterialTheme { Screen(incoming, onIncomingHandled = { incoming = null }) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme == "content" || data.scheme == "file") {
                    readTorrent(data)
                } else {
                    incoming = data.toString()
                }
            }
            Intent.ACTION_SEND -> incoming = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }

    private fun readTorrent(uri: Uri) {
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return
        Downloads.addTorrentFile(bytes, uri.lastPathSegment ?: "torrent")
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Screen(prefill: String?, onIncomingHandled: () -> Unit) {
        val items by Downloads.items.collectAsStateWithLifecycle()
        var adding by remember { mutableStateOf(false) }

        // Android 13+: the notification of a running download needs the person's yes.
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        remember {
            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            true
        }

        val pickTorrent = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readTorrent(it) }
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, null) }
            },
        ) { padding ->
            if (items.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                    Text(stringResource(R.string.empty))
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.key }) { ItemCard(it) }
                }
            }
        }

        if (adding || prefill != null) {
            AddDialog(
                initial = prefill.orEmpty(),
                onDismiss = { adding = false; onIncomingHandled() },
                onAdd = { text, video -> Downloads.add(text, video); adding = false; onIncomingHandled() },
                onPickTorrent = { pickTorrent.launch(arrayOf("*/*")); adding = false; onIncomingHandled() },
            )
        }
    }
}

@Composable
private fun AddDialog(
    initial: String,
    onDismiss: () -> Unit,
    onAdd: (String, Boolean) -> Unit,
    onPickTorrent: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var video by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.add_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = video, onCheckedChange = { video = it })
                    Text(stringResource(R.string.add_video))
                }
                TextButton(onClick = onPickTorrent) { Text(stringResource(R.string.open_torrent)) }
            }
        },
        confirmButton = { Button(onClick = { onAdd(text, video) }) { Text(stringResource(R.string.add_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ItemCard(item: Item) {
    var confirmRemove by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (item.status != Status.DONE || item.total > 0) {
                LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(statusLine(item), style = MaterialTheme.typography.bodySmall)
            item.error?.takeIf { item.status == Status.FAILED }?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    Status.RUNNING, Status.QUEUED ->
                        IconButton(onClick = { Downloads.pause(item.key) }) {
                            Icon(Icons.Filled.Pause, stringResource(R.string.pause))
                        }
                    Status.PAUSED, Status.FAILED ->
                        IconButton(onClick = { Downloads.resume(item.key) }) {
                            Icon(Icons.Filled.PlayArrow, stringResource(R.string.resume))
                        }
                    Status.DONE -> {}
                }
                IconButton(onClick = { confirmRemove = true }) {
                    Icon(Icons.Filled.Delete, stringResource(R.string.remove))
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { Downloads.remove(item.key, deleteFile = true); confirmRemove = false }) {
                    Text(stringResource(R.string.remove_with_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { Downloads.remove(item.key, deleteFile = false); confirmRemove = false }) {
                    Text(stringResource(R.string.remove))
                }
            },
        )
    }
}

@Composable
private fun statusLine(item: Item): String {
    val state = stringResource(
        when (item.status) {
            Status.QUEUED -> R.string.status_queued
            Status.RUNNING -> R.string.status_running
            Status.PAUSED -> R.string.status_paused
            Status.DONE -> R.string.status_done
            Status.FAILED -> R.string.status_failed
        },
    )
    val size = if (item.total > 0) stringResource(R.string.of_size, formatBytes(item.done), formatBytes(item.total)) else formatBytes(item.done)
    val speed = if (item.status == Status.RUNNING && item.speed > 0) " · ${formatBytes(item.speed)}/s" else ""
    return "$state · $size$speed"
}

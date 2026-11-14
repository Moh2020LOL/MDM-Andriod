package dev.mdm.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ======================================================================================
// The list, laid out like a phone download manager: a side drawer with the categories (videos,
// music, documents ...), tabs Downloading / Completed, a progress bar on every row, a + button.
// ======================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBrowser: () -> Unit, onSettings: () -> Unit, prefill: String?, onPrefillHandled: () -> Unit) {
    val items by Downloads.items.collectAsStateWithLifecycle()
    val mode by Prefs.speedMode.collectAsStateWithLifecycle()
    val snail by Prefs.snail.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var category by remember { mutableStateOf<FileType?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var speedOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    // The video page whose qualities are being chosen.
    var pickFor by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val shown = items
        .filter { if (tab == 0) it.status != Status.DONE else it.status == Status.DONE }
        .filter { category == null || it.type == category }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

    fun closeThen(action: () -> Unit) {
        scope.launch { drawer.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.app_name),
                        fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    )
                    DrawerEntry(Icons.Filled.Download, stringResource(R.string.drawer_downloads), items.size, category == null) {
                        closeThen { category = null }
                    }
                    listOf(
                        Triple(FileType.VIDEO, Icons.Filled.Movie, R.string.cat_video),
                        Triple(FileType.AUDIO, Icons.Filled.MusicNote, R.string.cat_audio),
                        Triple(FileType.DOCUMENT, Icons.Filled.Description, R.string.cat_documents),
                        Triple(FileType.APP, Icons.Filled.Android, R.string.cat_apps),
                        Triple(FileType.ARCHIVE, Icons.Filled.FolderZip, R.string.cat_archives),
                        Triple(FileType.IMAGE, Icons.Filled.Image, R.string.cat_images),
                        Triple(FileType.OTHER, Icons.Filled.InsertDriveFile, R.string.cat_other),
                    ).forEach { (type, icon, label) ->
                        DrawerEntry(icon, stringResource(label), items.count { it.type == type }, category == type) {
                            closeThen { category = type }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    DrawerEntry(Icons.Filled.Language, stringResource(R.string.menu_browser), null, false) { closeThen(onBrowser) }
                    DrawerEntry(Icons.Filled.Pause, stringResource(R.string.menu_pause_all), null, false) { closeThen { Downloads.pauseAll() } }
                    DrawerEntry(Icons.Filled.PlayArrow, stringResource(R.string.menu_resume_all), null, false) { closeThen { Downloads.resumeAll() } }
                    DrawerEntry(Icons.Filled.ClearAll, stringResource(R.string.menu_clear_completed), null, false) { closeThen { Downloads.clearCompleted() } }
                    DrawerEntry(Icons.Filled.Settings, stringResource(R.string.menu_settings), null, false) { closeThen(onSettings) }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            if (searching) {
                                TextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    singleLine = true,
                                    placeholder = { Text(stringResource(R.string.search_hint)) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(stringResource(R.string.app_name), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    ModeChip(mode, snail) { speedOpen = true }
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, null) }
                        },
                        actions = {
                            IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                                Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.search))
                            }
                            IconButton(onClick = onBrowser) { Icon(Icons.Filled.Language, stringResource(R.string.menu_browser)) }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                    TabRow(
                        selectedTabIndex = tab,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        listOf(R.string.tab_downloading, R.string.tab_completed).forEachIndexed { index, label ->
                            Tab(
                                selected = tab == index,
                                onClick = { tab = index },
                                text = { Text(stringResource(label), fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { adding = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = Color.White, shape = CircleShape) {
                    Icon(Icons.Filled.Add, stringResource(R.string.add_title))
                }
            },
        ) { padding ->
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(if (tab == 0) R.string.empty_active else R.string.empty_completed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                    items(shown, key = { it.key }) {
                        DownloadRow(it)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    if (adding || prefill != null) {
        AddDialog(
            initial = prefill.orEmpty(),
            onDismiss = { adding = false; onPrefillHandled() },
            onAdd = { text, video, audio ->
                if (video && !audio) {
                    pickFor = text
                } else {
                    Downloads.add(text, video, audio = audio)
                }
                adding = false
                onPrefillHandled()
            },
        )
    }
    pickFor?.let { link -> QualityDialog(link) { pickFor = null } }
    if (speedOpen) SpeedDialog(onDismiss = { speedOpen = false })
}

private sealed class QualityState {
    object Loading : QualityState()
    class Ready(val listing: VideoEngine.Listing) : QualityState()
    class Failed(val short: String, val detail: String) : QualityState()
}

/** Lists every quality the page offers (yt-dlp), the person taps one and the download starts with it. */
@Composable
private fun QualityDialog(link: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<QualityState>(QualityState.Loading) }
    LaunchedEffect(link) {
        state = withContext(Dispatchers.IO) {
            try {
                QualityState.Ready(VideoEngine.listFormats(context, link))
            } catch (e: Exception) {
                val raw = e.message.orEmpty()
                QualityState.Failed(VideoEngine.explain(raw), raw)
            }
        }
    }
    fun start(q: VideoEngine.Quality?) {
        Downloads.add(link, video = true, audio = q?.audioOnly == true, selector = q?.selector)
        Toast.makeText(context, R.string.overlay_added, Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quality_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when (val s = state) {
                    is QualityState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.quality_loading), fontSize = 14.sp)
                    }
                    is QualityState.Ready -> {
                        s.listing.title?.let { Text(it, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        var lastGroup = -1
                        s.listing.qualities.forEach { q ->
                            if (q.group != lastGroup && q.group != 0) {
                                Text(
                                    stringResource(
                                        when (q.group) {
                                            1 -> R.string.quality_sec_both
                                            2 -> R.string.quality_sec_video_only
                                            else -> R.string.quality_sec_audio
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                                )
                            }
                            lastGroup = q.group
                            TextButton(onClick = { start(q) }, modifier = Modifier.fillMaxWidth()) {
                                Text(q.label, modifier = Modifier.fillMaxWidth(), fontSize = 14.sp)
                            }
                        }
                    }
                    is QualityState.Failed -> {
                        Text(s.short, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                        SelectionContainer {
                            Text(s.detail, fontSize = 10.sp, maxLines = 12, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { start(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.quality_best), modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun DrawerEntry(icon: ImageVector, label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(label) },
        badge = { if (count != null && count > 0) Text(count.toString()) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

@Composable
private fun ModeChip(mode: SpeedMode, snail: Boolean, onClick: () -> Unit) {
    val (label, color) = when {
        snail -> R.string.mode_snail to MdmColors.Red
        mode == SpeedMode.FAST -> R.string.mode_fast to MdmColors.Green
        mode == SpeedMode.MODERATE -> R.string.mode_moderate to MdmColors.Orange
        else -> R.string.mode_slow to MdmColors.Red
    }
    Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.clickable(onClick = onClick)) {
        Text(
            stringResource(label).uppercase(),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun DownloadRow(item: Item) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth()
            .clickable {
                when (item.status) {
                    Status.DONE -> if (!Files.open(context, item)) {
                        Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                    }
                    Status.RUNNING, Status.QUEUED -> Downloads.pause(item.key)
                    Status.PAUSED, Status.FAILED -> Downloads.resume(item.key)
                }
            }
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(item.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            val failed = item.status == Status.FAILED
            Text(
                if (failed && item.error != null) item.error else statusLine(item),
                fontSize = 12.sp,
                maxLines = if (failed) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (failed) {
                Row {
                    TextButton(onClick = { Downloads.resume(item.key) }) { Text(stringResource(R.string.retry)) }
                    if (item.detail != null) {
                        TextButton(onClick = { showDetails = true }) { Text(stringResource(R.string.details)) }
                    }
                }
            }
            if (item.status != Status.DONE) {
                LinearProgressIndicator(
                    progress = { item.fraction },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = if (item.status == Status.PAUSED) MdmColors.Orange else MaterialTheme.colorScheme.primary,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                when (item.status) {
                    Status.RUNNING, Status.QUEUED ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.pause)) }, onClick = { menu = false; Downloads.pause(item.key) })
                    Status.PAUSED, Status.FAILED ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.resume)) }, onClick = { menu = false; Downloads.resume(item.key) })
                    Status.DONE -> {
                        DropdownMenuItem(text = { Text(stringResource(R.string.open)) }, onClick = {
                            menu = false
                            if (!Files.open(context, item)) Toast.makeText(context, R.string.cannot_open, Toast.LENGTH_SHORT).show()
                        })
                        DropdownMenuItem(text = { Text(stringResource(R.string.share)) }, onClick = { menu = false; Files.share(context, item) })
                    }
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.copy_link)) }, onClick = {
                    menu = false
                    val link = item.page ?: item.source
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("link", link))
                    Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.remove)) }, onClick = { menu = false; confirmRemove = true })
            }
        }
    }

    if (showDetails && item.detail != null) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text(stringResource(R.string.details)) },
            text = {
                SelectionContainer {
                    Text(item.detail, fontSize = 11.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("details", item.detail))
                    Toast.makeText(context, R.string.details_copied, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.details_copy)) }
            },
            dismissButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.ok)) } },
        )
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
private fun TypeBadge(type: FileType) {
    val (icon: ImageVector, color: Color) = when (type) {
        FileType.VIDEO -> Icons.Filled.Movie to MdmColors.Blue
        FileType.AUDIO -> Icons.Filled.MusicNote to MdmColors.Orange
        FileType.ARCHIVE -> Icons.Filled.FolderZip to Color(0xFF8E24AA)
        FileType.IMAGE -> Icons.Filled.Image to MdmColors.Green
        FileType.DOCUMENT -> Icons.Filled.Description to Color(0xFF546E7A)
        FileType.APP -> Icons.Filled.Android to Color(0xFF00897B)
        FileType.OTHER -> Icons.Filled.InsertDriveFile to Color(0xFF78909C)
    }
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White)
    }
}

@Composable
private fun statusLine(item: Item): String {
    val size = if (item.total > 0) {
        stringResource(R.string.of_size, formatBytes(item.done), formatBytes(item.total))
    } else {
        formatBytes(item.done)
    }
    return when (item.status) {
        Status.QUEUED -> stringResource(R.string.status_queued)
        Status.RUNNING -> {
            val speed = if (item.speed > 0) " · ${formatBytes(item.speed)}/s" else ""
            val eta = if (item.etaSeconds >= 0) " · " + formatEta(item.etaSeconds) else ""
            size + speed + eta
        }
        Status.PAUSED -> "$size · ${stringResource(R.string.status_paused)}"
        Status.DONE -> "${formatBytes(item.total)} · ${stringResource(R.string.status_done)}"
        Status.FAILED -> stringResource(R.string.status_failed)
    }
}

@Composable
private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.eta_seconds, seconds)
    seconds < 3600 -> stringResource(R.string.eta_minutes, seconds / 60)
    else -> stringResource(R.string.eta_hours, seconds / 3600, (seconds % 3600) / 60)
}

@Composable
private fun AddDialog(initial: String, onDismiss: () -> Unit, onAdd: (String, Boolean, Boolean) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(firstLink(initial) ?: initial) }
    // A known video site, or any link that looks like a web page, is ticked: the page is analyzed.
    fun page(t: String) = VideoDetector.videoPage(t) || VideoDetector.looksLikePage(t)
    var video by remember { mutableStateOf(page(text.trim())) }
    var audio by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; video = page(it.trim()) },
                    label = { Text(stringResource(R.string.add_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            firstLink(clip)?.let { text = it; video = page(it) }
                        }) { Icon(Icons.Filled.ContentPaste, stringResource(R.string.paste_link)) }
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = video, onCheckedChange = { video = it })
                    Text(stringResource(R.string.add_analyze), fontSize = 14.sp)
                }
                if (video) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = audio, onCheckedChange = { audio = it })
                        Text(stringResource(R.string.add_audio), fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(firstLink(text) ?: text, video, video && audio) }) { Text(stringResource(R.string.add_button)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ======================================================================================
// Speed mode: Fast / Moderate / Slow, and Snail mode
// ======================================================================================

@Composable
fun SpeedDialog(onDismiss: () -> Unit) {
    val mode by Prefs.speedMode.collectAsStateWithLifecycle()
    val snail by Prefs.snail.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.speed_title)) },
        text = {
            Column {
                SpeedMode.entries.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().clickable { Prefs.setSpeedMode(m) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == m, onClick = { Prefs.setSpeedMode(m) })
                        Column {
                            Text(
                                stringResource(
                                    when (m) {
                                        SpeedMode.FAST -> R.string.mode_fast
                                        SpeedMode.MODERATE -> R.string.mode_moderate
                                        SpeedMode.SLOW -> R.string.mode_slow
                                    },
                                ),
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                if (m.limitBps == 0L) {
                                    stringResource(R.string.speed_desc_unlimited, m.maxSimultaneous)
                                } else {
                                    stringResource(R.string.speed_desc_limit, formatBytes(m.limitBps), m.maxSimultaneous)
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.snail_title), fontWeight = FontWeight.Medium)
                        Text(stringResource(R.string.snail_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = snail, onCheckedChange = { Prefs.setSnail(it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

// ======================================================================================
// Settings
// ======================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onPermissions: () -> Unit) {
    val context = LocalContext.current
    val theme by Prefs.theme.collectAsStateWithLifecycle()
    val overlay by Prefs.overlayEnabled.collectAsStateWithLifecycle()
    val bubble by Prefs.bubbleEnabled.collectAsStateWithLifecycle()
    val deep by Prefs.deepDetect.collectAsStateWithLifecycle()
    var speedOpen by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val canOverlay = remember(tick) { Permissions.canOverlay(context) }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                titleContentColor = MaterialTheme.colorScheme.onSurface,
                navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(R.string.settings_video)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.overlay_switch))
                    Text(stringResource(R.string.overlay_switch_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = overlay, onCheckedChange = { Prefs.setOverlayEnabled(it) })
            }
            if (overlay && !canOverlay) {
                Text(stringResource(R.string.overlay_needs_permission), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                TextButton(onClick = {
                    Permissions.settingsIntent(context, Permissions.Kind.OVERLAY)?.let { context.startActivity(it) }
                }) { Text(stringResource(R.string.perm_allow)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_deep))
                    Text(stringResource(R.string.settings_deep_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = deep, onCheckedChange = { Prefs.setDeepDetect(it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_bubble))
                    Text(stringResource(R.string.settings_bubble_desc), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = bubble, onCheckedChange = {
                    Prefs.setBubbleEnabled(it)
                    BubbleService.sync(context)
                    if (it && !canOverlay) {
                        Permissions.settingsIntent(context, Permissions.Kind.OVERLAY)?.let { i -> context.startActivity(i) }
                    }
                })
            }
            HorizontalDivider()

            SectionTitle(R.string.settings_speed)
            TextButton(onClick = { speedOpen = true }) { Text(stringResource(R.string.speed_title)) }
            HorizontalDivider()

            SectionTitle(R.string.settings_appearance)
            listOf(
                ThemeChoice.SYSTEM to R.string.theme_system,
                ThemeChoice.LIGHT to R.string.theme_light,
                ThemeChoice.DARK to R.string.theme_dark,
            ).forEach { (choice, label) ->
                Row(Modifier.fillMaxWidth().clickable { Prefs.setTheme(choice) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == choice, onClick = { Prefs.setTheme(choice) })
                    Text(stringResource(label))
                }
            }
            HorizontalDivider()

            SectionTitle(R.string.settings_permissions)
            TextButton(onClick = onPermissions) { Text(stringResource(R.string.settings_permissions_open)) }
            HorizontalDivider()

            SectionTitle(R.string.settings_storage)
            Text(
                if (Files.legacyPublicDir(context) != null || android.os.Build.VERSION.SDK_INT >= 29) {
                    stringResource(R.string.settings_storage_public)
                } else {
                    Downloads.saveDir.absolutePath
                },
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            SectionTitle(R.string.settings_about)
            Text(stringResource(R.string.app_name) + " " + version, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (speedOpen) SpeedDialog(onDismiss = { speedOpen = false })
}

@Composable
private fun SectionTitle(res: Int) {
    Text(stringResource(res), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
}

// ======================================================================================
// Permissions: one guided screen (first start, and again from Settings)
// ======================================================================================

@Composable
fun PermissionsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    // Back from a system settings screen: look again at what was granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val asked = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.perm_title), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.perm_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Permissions.applicable()) { kind ->
                val granted = remember(tick) { Permissions.granted(context, kind) }
                PermissionCard(kind, granted) {
                    val runtime = Permissions.runtimePermission(kind)
                    val intent: Intent? = Permissions.settingsIntent(context, kind)
                    when {
                        runtime != null -> asked.launch(runtime)
                        intent != null -> context.startActivity(intent)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.perm_continue)) }
    }
}

@Composable
private fun PermissionCard(kind: Permissions.Kind, granted: Boolean, onAllow: () -> Unit) {
    val (title, why) = when (kind) {
        Permissions.Kind.NOTIFICATIONS -> R.string.perm_notifications_title to R.string.perm_notifications_why
        Permissions.Kind.OVERLAY -> R.string.perm_overlay_title to R.string.perm_overlay_why
        Permissions.Kind.BATTERY -> R.string.perm_battery_title to R.string.perm_battery_why
        Permissions.Kind.ACCESSIBILITY -> R.string.perm_accessibility_title to R.string.perm_accessibility_why
        Permissions.Kind.STORAGE -> R.string.perm_storage_title to R.string.perm_storage_why
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(title), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (granted) {
                    Text(stringResource(R.string.perm_granted), color = MdmColors.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else if (kind == Permissions.Kind.ACCESSIBILITY) {
                    Text(stringResource(R.string.perm_optional), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Text(stringResource(why), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!granted) {
                Button(onClick = onAllow) { Text(stringResource(R.string.perm_allow)) }
            }
        }
    }
}

/** Opens a link the way the system does (used by Settings' "about" later). */
fun openLink(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

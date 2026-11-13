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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.runtime.remember
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

// ======================================================================================
// The list: top bar with the speed-mode chip, tabs All / Active / Completed, search, rows
// ======================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBrowser: () -> Unit, onSettings: () -> Unit, prefill: String?, onPrefillHandled: () -> Unit) {
    val items by Downloads.items.collectAsStateWithLifecycle()
    val mode by Prefs.speedMode.collectAsStateWithLifecycle()
    val snail by Prefs.snail.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var speedOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    val shown = items
        .filter {
            when (tab) {
                0 -> true
                1 -> it.status != Status.DONE
                else -> it.status == Status.DONE
            }
        }
        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }

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
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.app_name), fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ModeChip(mode, snail) { speedOpen = true }
                            }
                        }
                    },
                    navigationIcon = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.Menu, null) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_browser)) }, onClick = { menuOpen = false; onBrowser() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_pause_all)) }, onClick = { menuOpen = false; Downloads.pauseAll() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_resume_all)) }, onClick = { menuOpen = false; Downloads.resumeAll() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_clear_completed)) }, onClick = { menuOpen = false; Downloads.clearCompleted() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.menu_settings)) }, onClick = { menuOpen = false; onSettings() })
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                            Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, stringResource(R.string.search))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MdmColors.Blue,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                )
                TabRow(
                    selectedTabIndex = tab,
                    containerColor = MdmColors.Blue,
                    contentColor = Color.White,
                    indicator = {},
                    divider = {},
                ) {
                    listOf(R.string.tab_all, R.string.tab_active, R.string.tab_completed).forEachIndexed { index, label ->
                        Tab(
                            selected = tab == index,
                            onClick = { tab = index },
                            text = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        stringResource(label),
                                        fontSize = 13.sp,
                                        fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.White.copy(alpha = if (tab == index) 1f else 0.7f),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier.width(28.dp).height(3.dp)
                                            .background(if (tab == index) Color.White else Color.Transparent),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { adding = true }, containerColor = MdmColors.Blue, contentColor = Color.White, shape = CircleShape) {
                Icon(Icons.Filled.Add, stringResource(R.string.add_title))
            }
        },
    ) { padding ->
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when (tab) {
                            0 -> R.string.empty_all
                            1 -> R.string.empty_active
                            else -> R.string.empty_completed
                        },
                    ),
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

    if (adding || prefill != null) {
        AddDialog(
            initial = prefill.orEmpty(),
            onDismiss = { adding = false; onPrefillHandled() },
            onAdd = { text, video -> Downloads.add(text, video); adding = false; onPrefillHandled() },
        )
    }
    if (speedOpen) SpeedDialog(onDismiss = { speedOpen = false })
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
private fun AddDialog(initial: String, onDismiss: () -> Unit, onAdd: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    // A link the detector knows as a video page is ticked for the person.
    var video by remember { mutableStateOf(VideoDetector.videoPage(initial)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; video = VideoDetector.videoPage(it.trim()) },
                    label = { Text(stringResource(R.string.add_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = video, onCheckedChange = { video = it })
                    Text(stringResource(R.string.add_video))
                }
            }
        },
        confirmButton = { Button(onClick = { onAdd(text, video) }) { Text(stringResource(R.string.add_button)) } },
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
                containerColor = MdmColors.Blue,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
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

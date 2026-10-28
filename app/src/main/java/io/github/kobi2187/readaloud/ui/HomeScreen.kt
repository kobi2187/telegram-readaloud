package io.github.kobi2187.readaloud.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.data.ChannelSummary
import io.github.kobi2187.readaloud.sync.SyncEngine
import io.github.kobi2187.readaloud.sync.SyncState
import io.github.kobi2187.readaloud.text.Lang

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, navigate: (Screen) -> Unit, sharedText: String?, onSharedConsumed: () -> Unit) {
    val channels by vm.channels.collectAsStateCompat()
    val sync by vm.syncState.collectAsStateCompat()
    val player by vm.player.collectAsStateCompat()
    val settings by vm.settings.collectAsStateCompat()
    var addDialog by remember { mutableStateOf<String?>(null) }
    var hebrewDeviceVoice by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { hebrewDeviceVoice = vm.deviceVoiceAvailable(Lang.HE) }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            addDialog = sharedText
            onSharedConsumed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { vm.syncNow() }, enabled = !sync.running) {
                        Icon(Icons.Filled.Sync, stringResource(R.string.action_sync))
                    }
                    IconButton(onClick = { navigate(Screen.Settings) }) {
                        Icon(Icons.Filled.Settings, stringResource(R.string.settings_title))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { addDialog = "" },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.add_channel_title)) },
            )
        },
        bottomBar = { PlayerBar(vm) { navigate(Screen.NowPlaying) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            SyncStatus(sync)
            if (!settings.cloudEnabled && !hebrewDeviceVoice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clickable { navigate(Screen.Settings) },
                ) {
                    Text(
                        stringResource(R.string.warning_no_hebrew_voice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (channels.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(channels, key = { it.channel.username }) { summary ->
                        ChannelCard(
                            summary = summary,
                            playing = player.channel == summary.channel.username,
                            vm = vm,
                            onOpen = { navigate(Screen.Channel(summary.channel.username)) },
                        )
                    }
                }
            }
        }
    }

    addDialog?.let { initial -> AddChannelDialog(vm, initial) { addDialog = null } }
}

@Composable
private fun SyncStatus(sync: SyncState) {
    when {
        sync.running -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            val text = if (sync.phase == SyncState.Phase.AUDIO && sync.audioTotal > 0) {
                stringResource(R.string.sync_audio_progress, sync.audioDone, sync.audioTotal)
            } else {
                stringResource(R.string.sync_text_progress, sync.channel ?: "")
            }
            Text(text, style = MaterialTheme.typography.bodySmall)
            if (sync.phase == SyncState.Phase.AUDIO && sync.audioTotal > 0) {
                LinearProgressIndicator(
                    progress = { sync.audioDone.toFloat() / sync.audioTotal },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
        sync.offline -> Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.sync_offline), style = MaterialTheme.typography.bodySmall)
        }
        sync.lastFinishedAt > 0 -> Text(
            stringResource(R.string.sync_last, formatDateTime(sync.lastFinishedAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.empty_channels),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun ChannelCard(summary: ChannelSummary, playing: Boolean, vm: MainViewModel, onOpen: () -> Unit) {
    val ch = summary.channel
    var menu by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Card(
        colors = if (playing) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth().clickable { vm.playChannel(ch.username) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ChannelAvatar(ch.avatarPath, ch.title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playing) {
                        Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(ch.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    "@${ch.username} · " + stringResource(R.string.channel_from, formatDate(ch.startDate)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                )
                val counts = if (summary.remaining == 0) {
                    stringResource(R.string.channel_caught_up)
                } else {
                    pluralStringResource(R.plurals.channel_remaining, summary.remaining, summary.remaining) + " · " +
                        pluralStringResource(R.plurals.channel_offline_ready, summary.audioReady, summary.audioReady)
                }
                Text(counts, style = MaterialTheme.typography.bodySmall)
                ch.lastError?.let { err ->
                    Text(
                        when (err) {
                            SyncEngine.ERROR_NO_PREVIEW -> stringResource(R.string.error_no_preview)
                            SyncEngine.ERROR_RATE_LIMIT -> stringResource(R.string.error_rate_limited)
                            else -> stringResource(R.string.error_sync_failed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!ch.enabled) {
                    Text(stringResource(R.string.channel_paused), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_posts)) }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_start_date)) }, onClick = { menu = false; dateDialog = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_mark_heard)) }, onClick = { menu = false; vm.markAllHeard(ch.username) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_restart)) }, onClick = { menu = false; vm.restart(ch.username) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_move_up)) }, onClick = { menu = false; vm.move(ch.username, -1) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_move_down)) }, onClick = { menu = false; vm.move(ch.username, +1) })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (ch.enabled) R.string.menu_pause_channel else R.string.menu_resume_channel)) },
                        onClick = { menu = false; vm.setEnabled(ch.username, !ch.enabled) },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.menu_remove)) }, onClick = { menu = false; confirmRemove = true })
                }
            }
        }
    }

    if (dateDialog) {
        StartDateDialog(ch.startDate, onDismiss = { dateDialog = false }) {
            dateDialog = false
            vm.setStartDate(ch.username, it)
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_title, ch.title)) },
            text = { Text(stringResource(R.string.remove_body)) },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; vm.removeChannel(ch.username) }) { Text(stringResource(R.string.menu_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

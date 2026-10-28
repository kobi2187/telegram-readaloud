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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.MessageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(vm: MainViewModel, username: String, back: () -> Unit, navigate: (Screen) -> Unit) {
    val channel by remember(username) { vm.channel(username) }.collectAsState(initial = null)
    val posts by remember(username) { vm.messages(username) }.collectAsState(initial = emptyList())
    val player by vm.player.collectAsStateCompat()
    var dateDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val cursor = channel?.cursorId ?: 0

    // Open at the next post to be read.
    LaunchedEffect(posts.isNotEmpty()) {
        val i = posts.indexOfFirst { it.id >= cursor }
        if (i > 0) listState.scrollToItem(i)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(channel?.title ?: username, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        channel?.let {
                            Text(
                                stringResource(R.string.channel_from, formatDate(it.startDate)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = { dateDialog = true }) { Icon(Icons.Filled.DateRange, stringResource(R.string.menu_start_date)) }
                    IconButton(onClick = { vm.playChannel(username) }) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play)) }
                },
            )
        },
        bottomBar = { PlayerBar(vm) { navigate(Screen.NowPlaying) } },
    ) { padding ->
        if (posts.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.channel_no_posts), color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        heard = post.id < cursor || post.played,
                        playing = player.channel == username && player.messageId == post.id,
                        onClick = { if (post.speech.isNotBlank()) vm.playChannel(username, post.id) },
                    )
                }
            }
        }
    }

    if (dateDialog && channel != null) {
        StartDateDialog(channel!!.startDate, onDismiss = { dateDialog = false }) {
            dateDialog = false
            vm.setStartDate(username, it)
        }
    }
}

@Composable
private fun PostCard(post: MessageEntity, heard: Boolean, playing: Boolean, onClick: () -> Unit) {
    val readable = post.speech.isNotBlank()
    Card(
        colors = when {
            playing -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            heard || !readable -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
            else -> CardDefaults.cardColors()
        },
        modifier = Modifier.fillMaxWidth().clickable(enabled = readable, onClick = onClick),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatDateTime(post.date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f),
                )
                if (playing) StatusIcon(Icons.Filled.GraphicEq, R.string.status_playing)
                if (heard) StatusIcon(Icons.Filled.CheckCircle, R.string.status_heard)
                when {
                    !readable -> Unit
                    post.audioState == AudioState.NEURAL -> StatusIcon(Icons.Filled.OfflinePin, R.string.status_neural_ready)
                    post.audioPath != null -> StatusIcon(Icons.Filled.PhoneAndroid, R.string.status_device_ready)
                    else -> StatusIcon(Icons.Filled.CloudDownload, R.string.status_not_downloaded)
                }
            }
            Spacer(Modifier.size(4.dp))
            val body = post.text.ifBlank { stringResource(R.string.post_media_only, post.media ?: "") }
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (playing) Int.MAX_VALUE else 8,
                overflow = TextOverflow.Ellipsis,
                color = if (readable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

@Composable
private fun StatusIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: Int) {
    Spacer(Modifier.width(6.dp))
    Icon(icon, stringResource(description), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
}

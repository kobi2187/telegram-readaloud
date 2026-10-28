package io.github.kobi2187.readaloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kobi2187.readaloud.R
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(vm: MainViewModel, back: () -> Unit) {
    val ui by vm.player.collectAsStateCompat()
    val settings by vm.settings.collectAsStateCompat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ui.channelTitle.ifBlank { stringResource(R.string.app_name) }) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding()) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                if (ui.time.isNotBlank()) {
                    Text(ui.time, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.size(8.dp))
                }
                SelectionContainer {
                    Text(
                        ui.text.ifBlank { stringResource(R.string.player_idle) },
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.15).byContent(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (ui.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.previousMessage() }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_prev_message)) }
                IconButton(onClick = { vm.seekBy(-10_000) }) { Icon(Icons.Filled.Replay10, stringResource(R.string.action_back_10)) }
                FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (ui.isPlaying) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { vm.seekBy(10_000) }) { Icon(Icons.Filled.Forward10, stringResource(R.string.action_forward_10)) }
                IconButton(onClick = { vm.nextMessage() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next_message)) }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = { vm.switchChannel(-1) }, modifier = Modifier.weight(1f)) {
                    Icon(painterResource(R.drawable.ic_channel_prev), null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.action_prev_channel_short), maxLines = 1)
                }
                OutlinedButton(onClick = { vm.switchChannel(+1) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_next_channel_short), maxLines = 1)
                    Spacer(Modifier.size(6.dp))
                    Icon(painterResource(R.drawable.ic_channel_next), null)
                }
            }
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    stringResource(R.string.speed_label, String.format(Locale.US, "%.2f×", settings.speed)),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = settings.speed,
                    onValueChange = { vm.setSpeed((it * 20).toInt() / 20f) },
                    valueRange = 0.7f..2.0f,
                )
            }
        }
    }
}

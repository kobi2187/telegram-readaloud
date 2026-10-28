package io.github.kobi2187.readaloud.ui

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.data.ChannelRepository
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun ChannelAvatar(path: String?, title: String, size: Int = 44) {
    val bitmap = remember(path) {
        path?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun PlayerBar(vm: MainViewModel, onOpen: () -> Unit) {
    val ui by vm.player.collectAsStateCompat()
    if (ui.mediaId == null && !ui.isPlaying) {
        // Nothing loaded yet: a single play button that starts the last channel.
        Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.player_idle), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = vm::togglePlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play)) }
            }
        }
        return
    }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.navigationBarsPadding()) {
            if (ui.durationMs > 0) {
                LinearProgressIndicator(
                    progress = { (ui.positionMs.toFloat() / ui.durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (ui.buffering) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Row(
                Modifier.clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ui.channelTitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.bodyMedium.byContent(),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = { vm.switchChannel(+1) }) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.action_next_channel))
                }
                IconButton(onClick = { vm.previousMessage() }) { Icon(Icons.Filled.SkipPrevious, stringResource(R.string.action_prev_message)) }
                IconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        stringResource(if (ui.isPlaying) R.string.action_pause else R.string.action_play),
                    )
                }
                IconButton(onClick = { vm.nextMessage() }) { Icon(Icons.Filled.SkipNext, stringResource(R.string.action_next_message)) }
            }
        }
    }
}

/** Start-of-day (local time) for a date [daysAgo] days back. */
fun startOfDayMillis(daysAgo: Int): Long =
    LocalDate.now().minusDays(daysAgo.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

@Composable
fun formatDate(millis: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
}

@Composable
fun formatDateTime(millis: Long): String {
    val context = LocalContext.current
    return DateUtils.formatDateTime(
        context,
        millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
    )
}

/** Date choice: quick presets plus a calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartDateChooser(value: Long, onChange: (Long) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    val presets = listOf(
        0 to R.string.start_today,
        1 to R.string.start_yesterday,
        3 to R.string.start_3_days,
        7 to R.string.start_week,
    )
    // Plain rows rather than FlowRow: dialogs measure content with intrinsics, and FlowRow
    // inside AlertDialog never settles at common phone widths.
    Column {
        Text(stringResource(R.string.start_from_label, formatDate(value)), style = MaterialTheme.typography.bodyMedium)
        presets.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (days, label) ->
                    val millis = startOfDayMillis(days)
                    FilterChip(selected = value == millis, onClick = { onChange(millis) }, label = { Text(stringResource(label)) })
                }
            }
        }
        TextButton(onClick = { showPicker = true }) { Text(stringResource(R.string.start_pick_date)) }
    }
    if (showPicker) {
        // The Material date picker works in UTC days; convert to local midnight.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val local = Instant.ofEpochMilli(utc).atZone(ZoneOffset.UTC).toLocalDate()
                            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                        onChange(local)
                    }
                    showPicker = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
fun AddChannelDialog(vm: MainViewModel, initial: String, onDismiss: () -> Unit) {
    val settings by vm.settings.collectAsStateCompat()
    var input by remember { mutableStateOf(initial) }
    var start by remember { mutableLongStateOf(startOfDayMillis(settings.defaultStartDays)) }
    var error by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_channel_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text(stringResource(R.string.add_channel_hint)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(stringResource(it)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.size(8.dp))
                StartDateChooser(start) { start = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        when (vm.addChannel(input, start)) {
                            is ChannelRepository.AddResult.Added -> onDismiss()
                            ChannelRepository.AddResult.AlreadyExists -> error = R.string.add_error_exists
                            ChannelRepository.AddResult.InvalidName -> error = R.string.add_error_invalid
                            ChannelRepository.AddResult.NoPreview -> error = R.string.add_error_no_preview
                        }
                        busy = false
                    }
                },
            ) { Text(stringResource(if (busy) R.string.add_checking else R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
fun StartDateDialog(initial: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    var value by remember { mutableLongStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.change_start_title)) },
        text = {
            Column {
                Text(stringResource(R.string.change_start_body), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.width(8.dp))
                StartDateChooser(value) { value = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

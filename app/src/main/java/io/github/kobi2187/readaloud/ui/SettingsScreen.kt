package io.github.kobi2187.readaloud.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import android.speech.tts.TextToSpeech
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.data.CloudVoice
import io.github.kobi2187.readaloud.data.VolumeKeyMode
import io.github.kobi2187.readaloud.text.Lang
import io.github.kobi2187.readaloud.tts.CloudStatus
import io.github.kobi2187.readaloud.tts.GeminiTts
import io.github.kobi2187.readaloud.tts.NeuralVoice
import io.github.kobi2187.readaloud.sync.VoiceDownloadWorker
import io.github.kobi2187.readaloud.tts.Voices
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, back: () -> Unit) {
    val s by vm.settings.collectAsStateCompat()
    val health by vm.cloudHealth.collectAsStateCompat()
    val offlineVoices by vm.offlineVoices.collectAsStateCompat()
    val cloudStatus = if (health.voice == s.cloudVoice) health.status else CloudStatus.OK
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var hebrewDevice by remember { mutableStateOf<Boolean?>(null) }
    var englishDevice by remember { mutableStateOf<Boolean?>(null) }
    var previewFailed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        cacheSize = vm.cacheSize()
        hebrewDevice = vm.deviceVoiceAvailable(Lang.HE)
        englishDevice = vm.deviceVoiceAvailable(Lang.EN)
    }

    fun preview(voice: String, hebrew: Boolean) {
        val sample = context.getString(if (hebrew) R.string.voice_sample_he else R.string.voice_sample_en)
        scope.launch { previewFailed = !vm.previewCloudVoice(voice, sample) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Section(R.string.settings_voice)
            Hint(R.string.online_voice_desc)
            RadioRow(stringResource(R.string.tts_gemini), stringResource(R.string.tts_gemini_desc), s.cloudVoice == CloudVoice.GEMINI) {
                vm.updateSettings { it.copy(cloudVoice = CloudVoice.GEMINI) }
            }
            RadioRow(stringResource(R.string.tts_azure), stringResource(R.string.tts_azure_desc), s.cloudVoice == CloudVoice.AZURE) {
                vm.updateSettings { it.copy(cloudVoice = CloudVoice.AZURE) }
            }
            RadioRow(stringResource(R.string.tts_none), stringResource(R.string.tts_none_desc), s.cloudVoice == CloudVoice.NONE) {
                vm.updateSettings { it.copy(cloudVoice = CloudVoice.NONE) }
            }
            when (s.cloudVoice) {
                CloudVoice.GEMINI -> {
                    GeminiKeyEditor(vm, s.geminiKey, cloudStatus) { previewFailed = false }
                    VoicePicker(stringResource(R.string.voice_gemini), geminiVoices, s.geminiVoice, onPreview = { preview(it, true) }) { v ->
                        vm.updateSettings { it.copy(geminiVoice = v) }
                    }
                    Hint(R.string.gemini_note)
                }
                CloudVoice.AZURE -> {
                    AzureAccountEditor(vm, s.azureKey, s.azureRegion, cloudStatus) { previewFailed = false }
                    VoicePicker(stringResource(R.string.voice_hebrew), Voices.hebrew, s.hebrewVoice, onPreview = { preview(it, true) }) { v ->
                        vm.updateSettings { it.copy(hebrewVoice = v) }
                    }
                    VoicePicker(stringResource(R.string.voice_english), Voices.english, s.englishVoice, onPreview = { preview(it, false) }) { v ->
                        vm.updateSettings { it.copy(englishVoice = v) }
                    }
                }
                CloudVoice.NONE -> Unit
            }
            if (previewFailed && s.cloudVoice != CloudVoice.NONE) {
                Text(
                    stringResource(R.string.voice_preview_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Section(R.string.settings_offline_voice)
            OfflineVoicesEditor(vm, offlineVoices)
            DeviceVoiceStatus(hebrewDevice, englishDevice)
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (e: ActivityNotFoundException) {
                        context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }) { Text(stringResource(R.string.device_tts_settings)) }
            }

            Section(R.string.settings_playback)
            SwitchRow(stringResource(R.string.announce_channel), stringResource(R.string.announce_channel_desc), s.announceChannel) { v ->
                vm.updateSettings { it.copy(announceChannel = v) }
            }
            SwitchRow(stringResource(R.string.auto_advance), stringResource(R.string.auto_advance_desc), s.autoAdvance) { v ->
                vm.updateSettings { it.copy(autoAdvance = v) }
            }

            Section(R.string.settings_volume_keys)
            Text(
                stringResource(R.string.volume_keys_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            RadioRow(stringResource(R.string.volume_keys_channels), null, s.volumeKeys == VolumeKeyMode.CHANNELS) {
                vm.updateSettings { it.copy(volumeKeys = VolumeKeyMode.CHANNELS) }
            }
            RadioRow(stringResource(R.string.volume_keys_messages), null, s.volumeKeys == VolumeKeyMode.MESSAGES) {
                vm.updateSettings { it.copy(volumeKeys = VolumeKeyMode.MESSAGES) }
            }
            RadioRow(stringResource(R.string.volume_keys_off), null, s.volumeKeys == VolumeKeyMode.OFF) {
                vm.updateSettings { it.copy(volumeKeys = VolumeKeyMode.OFF) }
            }

            Section(R.string.settings_sync)
            val intervals = listOf(0, 30, 60, 180, 360)
            ChoiceRow(
                stringResource(R.string.sync_interval),
                intervals,
                intervals.map { if (it == 0) stringResource(R.string.sync_manual) else pluralStringResource(R.plurals.minutes, it, it) },
                s.syncIntervalMinutes,
            ) { v -> vm.updateSettings { it.copy(syncIntervalMinutes = v) } }
            SwitchRow(stringResource(R.string.allow_metered), stringResource(R.string.allow_metered_desc), s.allowMetered) { v ->
                vm.updateSettings { it.copy(allowMetered = v) }
            }
            val prefetch = listOf(25, 50, 100, 200, 500)
            ChoiceRow(
                stringResource(R.string.prefetch_count),
                prefetch,
                prefetch.map { it.toString() },
                s.prefetchCount,
            ) { v -> vm.updateSettings { it.copy(prefetchCount = v) } }
            val starts = listOf(0, 1, 3, 7)
            ChoiceRow(
                stringResource(R.string.default_start),
                starts,
                starts.map { if (it == 0) stringResource(R.string.start_today) else pluralStringResource(R.plurals.days_back, it, it) },
                s.defaultStartDays,
            ) { v -> vm.updateSettings { it.copy(defaultStartDays = v) } }

            Section(R.string.settings_storage)
            SwitchRow(stringResource(R.string.delete_played), stringResource(R.string.delete_played_desc), s.deletePlayedAudio) { v ->
                vm.updateSettings { it.copy(deletePlayedAudio = v) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cache_size, cacheSize?.let { Formatter.formatShortFileSize(context, it) } ?: "…"),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.clear_cache)) }
            }

            Section(R.string.settings_about)
            Text(
                stringResource(R.string.about_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_cache)) },
            text = { Text(stringResource(R.string.clear_cache_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearAudioCache()
                    cacheSize = 0
                }) { Text(stringResource(R.string.clear_cache)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun Section(title: Int) {
    HorizontalDivider(Modifier.padding(top = 16.dp))
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun RadioRow(title: String, subtitle: String?, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.size(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<T>, labels: List<String>, value: T, onChange: (T) -> Unit) {
    val label: (T) -> String = { option -> labels.getOrElse(options.indexOf(option)) { option.toString() } }
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(label(value), color = MaterialTheme.colorScheme.primary)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        RadioRow(label(option), null, option == value) {
                            onChange(option)
                            open = false
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun VoicePicker(title: String, voices: List<NeuralVoice>, selected: String, onPreview: (String) -> Unit, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = voices.firstOrNull { it.id == selected }?.label ?: selected
    Row(
        Modifier.fillMaxWidth().clickable { open = true }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(current, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { onPreview(selected) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.voice_preview)) }
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    voices.forEach { v ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                RadioRow(v.label, null, v.id == selected) {
                                    onSelect(v.id)
                                    open = false
                                }
                            }
                            IconButton(onClick = { onPreview(v.id) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.voice_preview)) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
private fun DeviceVoiceStatus(hebrew: Boolean?, english: Boolean?) {
    fun mark(v: Boolean?) = when (v) { true -> "✓"; false -> "✗"; null -> "…" }
    Text(
        stringResource(R.string.device_voices_status, mark(hebrew), mark(english)),
        style = MaterialTheme.typography.bodySmall,
        color = if (hebrew == false || english == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun AzureAccountEditor(
    vm: MainViewModel,
    savedKey: String,
    savedRegion: String,
    status: CloudStatus,
    onTested: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var region by remember(savedRegion) { mutableStateOf(savedRegion) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim(); tested = null },
            label = { Text(stringResource(R.string.azure_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = region,
            onValueChange = { region = it.trim().lowercase(); tested = null },
            label = { Text(stringResource(R.string.azure_region)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(
                enabled = !testing && key.isNotBlank() && region.isNotBlank(),
                onClick = {
                    testing = true
                    scope.launch {
                        vm.updateSettings { it.copy(azureKey = key, azureRegion = region) }.join()
                        val cfg = vm.settings.value.copy(cloudVoice = CloudVoice.AZURE, azureKey = key, azureRegion = region)
                        val ok = vm.previewCloudVoice(cfg.hebrewVoice, context.getString(R.string.voice_sample_he), cfg)
                        tested = ok
                        onTested(ok)
                        testing = false
                    }
                },
            ) { Text(stringResource(if (testing) R.string.add_checking else R.string.key_save_test)) }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, AZURE_CREATE_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(stringResource(R.string.azure_get_key)) }
        }
        KeyStatus(
            savedKey.isBlank(), tested, status,
            R.string.azure_no_key, R.string.azure_ok, R.string.azure_bad_key, R.string.azure_quota, R.string.azure_throttled,
        )
    }
}

/** What we know about a key: missing, tested, or what the service said about it lately. */
@Composable
private fun KeyStatus(
    missing: Boolean,
    tested: Boolean?,
    status: CloudStatus,
    noKey: Int,
    ok: Int,
    badKey: Int,
    quota: Int,
    throttled: Int,
) {
    val message = when {
        missing -> noKey
        status == CloudStatus.BAD_KEY -> badKey
        status == CloudStatus.QUOTA -> quota
        status == CloudStatus.THROTTLED -> throttled
        tested == false -> R.string.voice_preview_failed
        tested == true -> ok
        else -> null
    } ?: return
    Text(
        stringResource(message),
        style = MaterialTheme.typography.bodySmall,
        color = if (message == ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private val geminiVoices = GeminiTts.VOICES.map { (name, character) -> NeuralVoice(name, "$name · $character", male = false) }

@Composable
private fun GeminiKeyEditor(vm: MainViewModel, savedKey: String, status: CloudStatus, onTested: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim(); tested = null },
            label = { Text(stringResource(R.string.gemini_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            OutlinedButton(
                enabled = !testing && key.isNotBlank(),
                onClick = {
                    testing = true
                    scope.launch {
                        vm.updateSettings { it.copy(geminiKey = key) }.join()
                        val cfg = vm.settings.value.copy(cloudVoice = CloudVoice.GEMINI, geminiKey = key)
                        val ok = vm.previewCloudVoice(cfg.geminiVoice, context.getString(R.string.voice_sample_he), cfg)
                        tested = ok
                        onTested(ok)
                        testing = false
                    }
                },
            ) { Text(stringResource(if (testing) R.string.add_checking else R.string.key_save_test)) }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, GEMINI_KEY_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text(stringResource(R.string.gemini_get_key)) }
        }
        KeyStatus(
            savedKey.isBlank(), tested, status,
            R.string.gemini_no_key, R.string.gemini_ok, R.string.gemini_bad_key, R.string.gemini_quota, R.string.gemini_throttled,
        )
    }
}

@Composable
private fun OfflineVoicesEditor(vm: MainViewModel, rows: List<OfflineVoiceRow>) {
    Hint(R.string.offline_voices_desc)
    rows.forEach { OfflineVoiceItem(vm, it) }
}

@Composable
private fun OfflineVoiceItem(vm: MainViewModel, row: OfflineVoiceRow) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0))
    val pack = row.pack
    val installed = row.status == OfflineVoiceStatus.Installed
    val traits = listOf(
        stringResource(if (pack.male) R.string.voice_male else R.string.voice_female),
        stringResource(if (pack.heavy) R.string.voice_heavy else R.string.voice_fast),
        pack.license,
    ).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = row.active, enabled = installed, role = Role.RadioButton) { vm.selectOfflineVoice(pack.id) }
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = row.active, onClick = null, enabled = installed)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pack.name, style = MaterialTheme.typography.bodyLarge)
            Text(traits, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            when (val st = row.status) {
                is OfflineVoiceStatus.Downloading -> {
                    Text(stringResource(R.string.offline_voice_downloading, size(st.done), size(st.total)), style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { if (st.total > 0) (st.done.toFloat() / st.total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
                is OfflineVoiceStatus.Waiting -> {
                    Text(
                        stringResource(if (st.wifiOnly) R.string.offline_voice_waiting_wifi else R.string.offline_voice_waiting),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (st.wifiOnly) {
                        TextButton(onClick = { vm.downloadOfflineVoice(pack.id, mobileData = true) }) {
                            Text(stringResource(R.string.offline_voice_use_mobile_data, size(row.toDownload)))
                        }
                    }
                }
                is OfflineVoiceStatus.Failed -> Text(
                    when (st.reason) {
                        VoiceDownloadWorker.REASON_SPACE -> stringResource(R.string.offline_voice_no_space, size(st.neededBytes))
                        VoiceDownloadWorker.REASON_UNSUPPORTED -> stringResource(R.string.offline_voice_unsupported)
                        else -> stringResource(R.string.offline_voice_failed)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Unit
            }
            if (previewFailed) {
                Text(stringResource(R.string.offline_voice_unsupported), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when (row.status) {
            OfflineVoiceStatus.Installed -> {
                IconButton(onClick = {
                    scope.launch { previewFailed = !vm.previewOfflineVoice(pack.id, context.getString(R.string.voice_sample_he)) }
                }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.voice_preview)) }
                TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.offline_voice_remove)) }
            }
            is OfflineVoiceStatus.Downloading, is OfflineVoiceStatus.Waiting ->
                TextButton(onClick = { vm.cancelOfflineVoiceDownload(pack.id) }) { Text(stringResource(android.R.string.cancel)) }
            else -> OutlinedButton(onClick = { vm.downloadOfflineVoice(pack.id) }) {
                Text(stringResource(R.string.offline_voice_download, size(row.toDownload)))
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.offline_voice_remove)) },
            text = { Text(stringResource(R.string.offline_voice_remove_body, pack.name)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeOfflineVoice(pack.id)
                }) { Text(stringResource(R.string.offline_voice_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

private const val GEMINI_KEY_URL = "https://aistudio.google.com/apikey"
private const val AZURE_CREATE_URL = "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"

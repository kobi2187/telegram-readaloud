package io.github.kobi2187.readaloud.ui

import android.app.Application
import android.content.ComponentName
import android.media.MediaPlayer
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.kobi2187.readaloud.ReadAloudApp
import io.github.kobi2187.readaloud.data.AppSettings
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.ChannelRepository
import io.github.kobi2187.readaloud.data.ChannelSummary
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.playback.Commands
import io.github.kobi2187.readaloud.playback.MediaIds
import io.github.kobi2187.readaloud.playback.PlaybackService
import io.github.kobi2187.readaloud.sync.SyncState
import io.github.kobi2187.readaloud.sync.SyncWorker
import io.github.kobi2187.readaloud.text.Lang
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.kobi2187.readaloud.sync.VoiceDownloadWorker
import io.github.kobi2187.readaloud.tts.CloudHealth
import io.github.kobi2187.readaloud.tts.OfflineVoices
import io.github.kobi2187.readaloud.voice.VoiceCatalog
import io.github.kobi2187.readaloud.voice.VoicePack
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One offline voice as the settings screen shows it. */
data class OfflineVoiceRow(val pack: VoicePack, val status: OfflineVoiceStatus, val active: Boolean, val toDownload: Long)

sealed interface OfflineVoiceStatus {
    data object NotInstalled : OfflineVoiceStatus
    /** Queued: waiting for a connection ([wifiOnly]: Wi-Fi), or for a retry after an interruption. */
    data class Waiting(val wifiOnly: Boolean) : OfflineVoiceStatus
    data class Downloading(val done: Long, val total: Long) : OfflineVoiceStatus
    data class Failed(val reason: String?, val neededBytes: Long = 0) : OfflineVoiceStatus
    data object Installed : OfflineVoiceStatus
}

data class PlayerUi(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val mediaId: String? = null,
    val channel: String? = null,
    val messageId: Int? = null,
    val title: String = "",
    val channelTitle: String = "",
    val time: String = "",
    val text: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = (app as ReadAloudApp).graph
    private val repo: ChannelRepository = graph.channels

    val channels: StateFlow<List<ChannelSummary>> =
        graph.db.channels().observeSummaries().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<AppSettings> = graph.settings.state
    val syncState: StateFlow<SyncState> = graph.sync.state
    val cloudHealth: StateFlow<CloudHealth> = graph.renderer.cloudHealth

    private val voiceErrors = MutableStateFlow<Map<String, OfflineVoiceStatus.Failed>>(emptyMap())

    val offlineVoices: StateFlow<List<OfflineVoiceRow>> = combine(
        graph.offlineVoices.state,
        WorkManager.getInstance(app).getWorkInfosByTagFlow(VoiceDownloadWorker.TAG_ALL),
        voiceErrors,
    ) { state, works, errors ->
        val byVoice = works.groupBy { VoiceDownloadWorker.voiceOf(it.tags) }
        graph.offlineVoices.let { voices ->
            VoiceCatalog.voices.map { pack ->
                val work = byVoice[pack.id]?.firstOrNull { !it.state.isFinished } ?: byVoice[pack.id]?.firstOrNull()
                val status = when {
                    pack.id in state.installed -> OfflineVoiceStatus.Installed
                    work?.state == WorkInfo.State.RUNNING -> OfflineVoiceStatus.Downloading(
                        work.progress.getLong(VoiceDownloadWorker.PROGRESS_DONE, 0),
                        work.progress.getLong(VoiceDownloadWorker.PROGRESS_TOTAL, voices.remainingBytes(pack.id)),
                    )
                    work?.state == WorkInfo.State.ENQUEUED || work?.state == WorkInfo.State.BLOCKED ->
                        OfflineVoiceStatus.Waiting(VoiceDownloadWorker.TAG_WIFI_ONLY in work.tags)
                    errors[pack.id] != null -> errors.getValue(pack.id)
                    work?.state == WorkInfo.State.FAILED -> OfflineVoiceStatus.Failed(
                        work.outputData.getString(VoiceDownloadWorker.KEY_REASON),
                        work.outputData.getLong(VoiceDownloadWorker.KEY_NEEDED, 0),
                    )
                    else -> OfflineVoiceStatus.NotInstalled
                }
                OfflineVoiceRow(pack, status, pack.id == state.active, voices.remainingBytes(pack.id))
            }
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Some offline voice is installed or on its way. */
    val offlineVoiceComing: StateFlow<Boolean> = offlineVoices.map { rows ->
        rows.any { it.status is OfflineVoiceStatus.Installed || it.status is OfflineVoiceStatus.Downloading || it.status is OfflineVoiceStatus.Waiting }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, graph.offlineVoices.activeId != null)

    private val _player = MutableStateFlow(PlayerUi())
    val player: StateFlow<PlayerUi> = _player.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = refreshPlayer()
    }

    private var onConnected: (() -> Unit)? = null

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(getApplication(), ComponentName(getApplication(), PlaybackService::class.java))
        val future = MediaController.Builder(getApplication(), token).buildAsync()
        controllerFuture = future
        viewModelScope.launch {
            val c = runCatching { future.await() }.getOrNull() ?: run { controllerFuture = null; return@launch }
            controller = c
            c.addListener(listener)
            refreshPlayer()
            onConnected?.invoke()
            onConnected = null
        }
    }

    private fun refreshPlayer() {
        val c = controller ?: return
        val item: MediaItem? = c.currentMediaItem
        val meta = item?.mediaMetadata
        val parsed = MediaIds.parse(item?.mediaId)
        _player.value = PlayerUi(
            connected = true,
            isPlaying = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            mediaId = item?.mediaId,
            channel = when (parsed) {
                is MediaIds.Message -> parsed.channel
                is MediaIds.Announce -> parsed.channel
                else -> null
            },
            messageId = (parsed as? MediaIds.Message)?.id,
            title = meta?.title?.toString().orEmpty(),
            channelTitle = meta?.artist?.toString().orEmpty(),
            time = meta?.albumTitle?.toString().orEmpty(),
            text = meta?.description?.toString() ?: meta?.title?.toString().orEmpty(),
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0,
            speed = c.playbackParameters.speed,
        )
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = viewModelScope.launch {
                while (isActive && controller?.isPlaying == true) {
                    delay(500)
                    val cc = controller ?: break
                    _player.value = _player.value.copy(
                        positionMs = cc.currentPosition.coerceAtLeast(0),
                        durationMs = cc.duration.takeIf { it > 0 } ?: 0,
                    )
                }
            }
        }
    }

    // --- Playback -------------------------------------------------------------------------

    fun playChannel(username: String, fromMessageId: Int? = null) {
        val c = controller ?: return connect()
        val args = Bundle().apply {
            putString(Commands.ARG_CHANNEL, username)
            if (fromMessageId != null) {
                putInt(Commands.ARG_MESSAGE_ID, fromMessageId)
                putBoolean(Commands.ARG_ANNOUNCE, false)
            }
        }
        c.sendCustomCommand(SessionCommand(Commands.PLAY_CHANNEL, Bundle.EMPTY), args)
    }

    /** "Play <name>" from the Assistant: the matching channel, or resume. */
    fun playFromSearch(query: String?) {
        if (controller == null) {
            onConnected = { playFromSearch(query) }
            return connect()
        }
        val q = query?.trim()?.lowercase().orEmpty()
        val match = channels.value.map { it.channel }.firstOrNull {
            q.isNotEmpty() && (it.title.lowercase().contains(q) || it.username.contains(q.replace(' ', '_')))
        }
        if (match != null) playChannel(match.username) else if (controller?.isPlaying != true) togglePlay()
    }

    fun togglePlay() {
        val c = controller ?: return connect()
        when {
            c.isPlaying -> c.pause()
            c.mediaItemCount == 0 || c.playbackState == Player.STATE_ENDED -> {
                val withNews = channels.value.filter { it.remaining > 0 && it.channel.enabled }.map { it.channel.username }
                val current = settings.value.currentChannel
                val target = current?.takeIf { it in withNews } ?: withNews.firstOrNull()
                if (target != null) {
                    playChannel(target)
                } else if (c.mediaItemCount > 0) {
                    c.seekToDefaultPosition()
                    c.play()
                }
            }
            else -> {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            }
        }
    }

    fun nextMessage() = controller?.seekToNextMediaItem()
    fun previousMessage() = controller?.seekToPreviousMediaItem()
    fun seekBy(ms: Long) = controller?.let { it.seekTo((it.currentPosition + ms).coerceAtLeast(0)) }

    fun switchChannel(direction: Int) {
        val c = controller ?: return connect()
        c.sendCustomCommand(
            SessionCommand(if (direction > 0) Commands.NEXT_CHANNEL else Commands.PREV_CHANNEL, Bundle.EMPTY),
            Bundle.EMPTY,
        )
    }

    fun setSpeed(speed: Float) {
        viewModelScope.launch { graph.settings.update { it.copy(speed = speed) } }
    }

    // --- Channels ----------------------------------------------------------------------------

    suspend fun addChannel(input: String, startDate: Long): ChannelRepository.AddResult {
        val result = repo.add(input, startDate)
        if (result is ChannelRepository.AddResult.Added) syncNow(only = result.username)
        return result
    }

    fun removeChannel(username: String) = viewModelScope.launch { repo.remove(username) }

    fun setStartDate(username: String, startDate: Long) = viewModelScope.launch {
        repo.setStartDate(username, startDate)
        syncNow(only = username)
    }

    fun markAllHeard(username: String) = viewModelScope.launch { repo.markAllHeard(username) }
    fun restart(username: String) = viewModelScope.launch { repo.restart(username) }
    fun move(username: String, delta: Int) = viewModelScope.launch { repo.move(username, delta) }
    fun setEnabled(username: String, enabled: Boolean) = viewModelScope.launch { repo.setEnabled(username, enabled) }

    fun channel(username: String): Flow<ChannelEntity?> = graph.db.channels().observe(username)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun messages(username: String): Flow<List<MessageEntity>> =
        graph.db.channels().observe(username).flatMapLatest { ch ->
            if (ch == null) flowOf(emptyList()) else graph.db.messages().observeChannel(username, ch.startDate)
        }

    // --- Sync & settings ---------------------------------------------------------------------

    /** Runs a sync now if online; otherwise WorkManager runs it when the network returns. */
    fun syncNow(only: String? = null) {
        val cfg = settings.value
        if (graph.network.isOnline(cfg.allowMetered)) {
            graph.scope.launch { graph.sync.sync(only = only) }
        } else {
            SyncWorker.syncNow(getApplication(), cfg.allowMetered)
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { graph.settings.update(transform) }

    suspend fun cacheSize(): Long = withContext(Dispatchers.IO) { graph.renderer.cacheSizeBytes() }

    fun clearAudioCache() = viewModelScope.launch {
        graph.db.messages().clearAllAudio()
        withContext(Dispatchers.IO) { graph.renderer.clearAll() }
    }

    suspend fun deviceVoiceAvailable(lang: Lang): Boolean =
        withContext(Dispatchers.IO) { runCatching { graph.systemTts.isLanguageAvailable(lang) }.getOrDefault(false) }

    /** Starts downloading [id]; [mobileData] overrides the Wi-Fi-only setting for this download. */
    fun downloadOfflineVoice(id: String, mobileData: Boolean = false) = viewModelScope.launch {
        val needed = withContext(Dispatchers.IO) { graph.offlineVoices.remainingBytes(id) } + OfflineVoices.SPACE_MARGIN
        val free = withContext(Dispatchers.IO) {
            runCatching { android.os.StatFs(getApplication<Application>().filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        }
        if (needed > free) {
            voiceErrors.value += id to OfflineVoiceStatus.Failed(VoiceDownloadWorker.REASON_SPACE, needed)
            return@launch
        }
        voiceErrors.value -= id
        VoiceDownloadWorker.start(getApplication(), id, mobileData || settings.value.allowMetered)
    }

    fun cancelOfflineVoiceDownload(id: String) = VoiceDownloadWorker.cancel(getApplication(), id)

    fun selectOfflineVoice(id: String) = viewModelScope.launch(Dispatchers.IO) { graph.offlineVoices.select(id) }

    fun removeOfflineVoice(id: String) = viewModelScope.launch {
        VoiceDownloadWorker.cancel(getApplication(), id)
        withContext(Dispatchers.IO) { graph.offlineVoices.remove(id) }
    }

    private var previewPlayer: MediaPlayer? = null

    /**
     * Plays a short sample of [voice] from the online service selected in [cfg] (defaults to the
     * saved settings); returns false when it could not be fetched.
     */
    suspend fun previewCloudVoice(voice: String, sample: String, cfg: AppSettings = settings.value): Boolean {
        if (!cfg.cloudEnabled) return false
        val file = withContext(Dispatchers.IO) { runCatching { graph.renderer.previewCloud(cfg, voice, sample) }.getOrNull() }
        return file != null && play(file)
    }

    /** Plays a sample of an installed offline voice. */
    suspend fun previewOfflineVoice(id: String, sample: String): Boolean {
        val file = withContext(Dispatchers.IO) { runCatching { graph.renderer.previewOffline(id, sample) }.getOrNull() }
        return file != null && play(file)
    }

    private fun play(file: File): Boolean {
        previewPlayer?.release()
        previewPlayer = null
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.path)
            player.setOnCompletionListener { it.release(); if (previewPlayer === it) previewPlayer = null }
            player.prepare()
            player.start()
            previewPlayer = player
            true
        } catch (e: Exception) {
            player.release()
            false
        }
    }

    override fun onCleared() {
        previewPlayer?.release()
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}

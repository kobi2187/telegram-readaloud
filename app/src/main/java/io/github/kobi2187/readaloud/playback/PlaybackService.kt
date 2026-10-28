package io.github.kobi2187.readaloud.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.ReadAloudApp
import io.github.kobi2187.readaloud.data.VolumeKeyMode
import io.github.kobi2187.readaloud.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class PlaybackService : MediaLibraryService() {

    private val graph by lazy { (application as ReadAloudApp).graph }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exo: ExoPlayer
    private lateinit var player: VolumeKeyPlayer
    private lateinit var session: MediaLibrarySession
    private lateinit var queue: QueueController
    private var lastAutoSync = 0L
    private var consecutiveErrors = 0

    override fun onCreate() {
        super.onCreate()
        val resolver = MediaIds.Resolver(graph.renderer, graph.db.channels()) { key -> queue.noticeText(key) }
        val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this), resolver)
        val mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSource)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        queue = QueueController(this, exo, graph, scope)
        player = VolumeKeyPlayer(exo, this) { direction -> onVolumeGesture(direction) }

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                queue.onMediaItemTransition(reason)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) queue.onEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) consecutiveErrors = 0 else scope.launch { queue.savePosition() }
            }

            override fun onPlayerError(error: PlaybackException) {
                // A post we could not voice: skip it, unless nothing can be voiced right now
                // (offline and no device voice), in which case stop instead of racing through.
                if (++consecutiveErrors > MAX_CONSECUTIVE_ERRORS) return
                if (exo.hasNextMediaItem()) {
                    exo.seekToNextMediaItem()
                    exo.prepare()
                    exo.play()
                } else {
                    queue.onEnded()
                }
            }
        })

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setMediaButtonPreferences(mediaButtons())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        observeSettings()
        keepSyncing()
        savePositionPeriodically()
    }

    private fun mediaButtons(): ImmutableList<CommandButton> = ImmutableList.of(
        CommandButton.Builder(CommandButton.ICON_SKIP_BACK)
            .setDisplayName(getString(R.string.action_prev_channel))
            .setCustomIconResId(R.drawable.ic_channel_prev)
            .setSessionCommand(SessionCommand(Commands.PREV_CHANNEL, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD)
            .setDisplayName(getString(R.string.action_next_channel))
            .setCustomIconResId(R.drawable.ic_channel_next)
            .setSessionCommand(SessionCommand(Commands.NEXT_CHANNEL, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private fun observeSettings() {
        scope.launch {
            graph.settings.flow.map { it.speed }.distinctUntilChanged().collect { exo.setPlaybackSpeed(it) }
        }
        scope.launch {
            graph.settings.flow.map { it.volumeKeys }.distinctUntilChanged().collect {
                player.hijackEnabled = it != VolumeKeyMode.OFF
            }
        }
    }

    /** Sync whenever connectivity comes back while we are alive, and every 15 min while online. */
    private fun keepSyncing() {
        scope.launch {
            graph.network.onlineChanges().filter { it }.collect { maybeSync(minGapMs = 2 * 60_000L) }
        }
        scope.launch {
            while (isActive) {
                delay(15 * 60_000L)
                if (exo.isPlaying) maybeSync(minGapMs = 10 * 60_000L)
            }
        }
    }

    private fun maybeSync(minGapMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastAutoSync < minGapMs) return
        lastAutoSync = now
        graph.scope.launch { graph.sync.sync() }
    }

    private fun savePositionPeriodically() {
        scope.launch {
            while (isActive) {
                delay(5_000)
                if (exo.isPlaying) queue.savePosition()
            }
        }
    }

    private fun onVolumeGesture(direction: Int) {
        when (graph.settings.state.value.volumeKeys) {
            VolumeKeyMode.CHANNELS -> scope.launch { queue.switchChannel(direction) }
            VolumeKeyMode.MESSAGES -> if (direction > 0) exo.seekToNextMediaItem() else exo.seekToPreviousMediaItem()
            VolumeKeyMode.OFF -> Unit
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onDestroy() {
        runBlocking { runCatching { queue.savePosition() } }
        player.releaseWatchers()
        session.release()
        player.release()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(Commands.NEXT_CHANNEL, Bundle.EMPTY))
                .add(SessionCommand(Commands.PREV_CHANNEL, Bundle.EMPTY))
                .add(SessionCommand(Commands.PLAY_CHANNEL, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setMediaButtonPreferences(mediaButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = scope.future {
            when (customCommand.customAction) {
                Commands.NEXT_CHANNEL -> queue.switchChannel(+1)
                Commands.PREV_CHANNEL -> queue.switchChannel(-1)
                Commands.PLAY_CHANNEL -> {
                    val channel = args.getString(Commands.ARG_CHANNEL) ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                    val fromId = args.getInt(Commands.ARG_MESSAGE_ID, -1).takeIf { it > 0 }
                    val announce = if (args.containsKey(Commands.ARG_ANNOUNCE)) args.getBoolean(Commands.ARG_ANNOUNCE) else null
                    if (!queue.playChannel(channel, fromId, announce)) {
                        return@future SessionResult(SessionError.ERROR_INVALID_STATE)
                    }
                }
                else -> return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            }
            SessionResult(SessionResult.RESULT_SUCCESS)
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            LibraryResult.ofItem(
                MediaItem.Builder()
                    .setMediaId(MediaIds.ROOT)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(getString(R.string.app_name))
                            .setIsBrowsable(true)
                            .setIsPlayable(false)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                            .build(),
                    )
                    .build(),
                params,
            ),
        )

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            if (parentId != MediaIds.ROOT) return@future LibraryResult.ofItemList(ImmutableList.of(), params)
            val items = graph.db.channels().enabled().map { ch ->
                MediaItem.Builder()
                    .setMediaId(MediaIds.channel(ch.username))
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(ch.title)
                            .setIsPlayable(true)
                            .setIsBrowsable(false)
                            .setArtworkUri(ch.avatarPath?.let { android.net.Uri.fromFile(java.io.File(it)) })
                            .build(),
                    )
                    .build()
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val first = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
            val search = first?.requestMetadata?.searchQuery
            val prepared = when (val requested = MediaIds.parse(first?.mediaId)) {
                is MediaIds.Channel -> queue.prepareChannel(requested.channel, null, announce = true)
                is MediaIds.Message -> queue.prepareChannel(requested.channel, requested.id, announce = false)
                else -> if (search != null) queue.prepareForSearch(search) else null
            }
            prepared ?: MediaSession.MediaItemsWithStartPosition(mediaItems.filter { it.localConfiguration != null }, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.filter { it.localConfiguration != null }.toMutableList(),
        )

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            queue.resumptionItems() ?: throw IllegalStateException("Nothing to resume")
        }
    }
}

private const val MAX_CONSECUTIVE_ERRORS = 3

object Commands {
    const val NEXT_CHANNEL = "readaloud.NEXT_CHANNEL"
    const val PREV_CHANNEL = "readaloud.PREV_CHANNEL"
    const val PLAY_CHANNEL = "readaloud.PLAY_CHANNEL"
    const val ARG_CHANNEL = "channel"
    const val ARG_MESSAGE_ID = "messageId"
    const val ARG_ANNOUNCE = "announce"
}

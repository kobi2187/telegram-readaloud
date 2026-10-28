package io.github.kobi2187.readaloud.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import io.github.kobi2187.readaloud.AppGraph
import io.github.kobi2187.readaloud.R
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns what is in the player's playlist. One channel is loaded at a time: an optional spoken
 * channel name, then that channel's posts from its cursor onwards. Reaching the end continues
 * with the next channel that has unheard posts.
 */
class QueueController(
    private val context: Context,
    private val player: Player,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val channels = graph.db.channels()
    private val messages = graph.db.messages()

    var currentChannel: String? = null
        private set
    private var lastQueuedId = 0
    private var watchJob: Job? = null
    private var lastItem: MediaIds.Parsed? = null
    private var lastIndex = -1

    init {
        // Once everything is heard, carry on as soon as any channel gets a new post (like radio).
        scope.launch {
            channels.observeSummaries()
                .map { list -> list.filter { it.channel.enabled }.sumOf { it.remaining } }
                .distinctUntilChanged()
                .collect { remaining -> if (remaining > 0 && isCaughtUp() && !currentChannelHasNews()) advanceToNextChannel() }
        }
    }

    private fun isCaughtUp(): Boolean =
        player.playWhenReady && player.playbackState == Player.STATE_ENDED &&
            MediaIds.parse(player.currentMediaItem?.mediaId) == MediaIds.Notice(NOTICE_CAUGHT_UP)

    /** New posts in the current channel are appended by [topUp] instead. */
    private suspend fun currentChannelHasNews(): Boolean {
        val ch = currentChannel?.let { channels.get(it) } ?: return false
        return messages.countFrom(ch.username, lastQueuedId + 1, ch.startDate) > 0
    }

    /** Builds the playlist for [username] without touching the player; null if nothing to read. */
    suspend fun prepareChannel(username: String, fromId: Int?, announce: Boolean): MediaItemsWithStartPosition? {
        val ch = channels.get(username) ?: return null
        val start = fromId ?: ch.cursorId
        val posts = messages.queue(username, start, ch.startDate, QUEUE_BATCH)
        if (posts.isEmpty()) return null
        val items = buildList {
            if (announce) add(announceItem(ch))
            posts.forEach { add(messageItem(ch, it)) }
        }
        val resumeMs = if (!announce && fromId == null && posts.first().id == ch.cursorId) ch.cursorPositionMs else 0L
        currentChannel = username
        lastQueuedId = posts.last().id
        lastItem = null
        lastIndex = -1
        channels.setCursor(username, posts.first().id, resumeMs)
        graph.settings.update { it.copy(currentChannel = username) }
        watchForNewPosts(username)
        return MediaItemsWithStartPosition(items, 0, resumeMs)
    }

    /** Loads and plays a channel. Returns false when it has nothing left to read. */
    suspend fun playChannel(username: String, fromId: Int? = null, announce: Boolean? = null, play: Boolean = true): Boolean {
        savePosition()
        val wantAnnounce = announce ?: graph.settings.current().announceChannel
        val prepared = prepareChannel(username, fromId, wantAnnounce) ?: return false
        player.setMediaItems(prepared.mediaItems, prepared.startIndex, prepared.startPositionMs)
        player.prepare()
        player.playWhenReady = play
        return true
    }

    /** Moves to the next (+1) or previous (-1) channel that still has unheard posts. */
    suspend fun switchChannel(direction: Int) {
        val list = channels.enabled()
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.username == currentChannel }
        for (step in 1 until list.size + 1) {
            val candidate = list[Math.floorMod(idx + direction * step, list.size)]
            if (candidate.username == currentChannel) break
            if (messages.countFrom(candidate.username, candidate.cursorId, candidate.startDate) > 0) {
                playChannel(candidate.username)
                return
            }
        }
        playNotice(NOTICE_NO_OTHER, resumeAfter = true)
    }

    /** Voice search ("play <channel>"): best title/username match, or carry on where we were. */
    suspend fun prepareForSearch(query: String?): MediaItemsWithStartPosition? {
        val match = findChannel(query) ?: return resumptionItems()
        return prepareChannel(match.username, null, graph.settings.current().announceChannel)
    }

    suspend fun findChannel(query: String?): ChannelEntity? {
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val list = channels.enabled()
        return list.firstOrNull { it.title.lowercase() == q || it.username == q }
            ?: list.firstOrNull { it.title.lowercase().contains(q) || it.username.contains(q.replace(' ', '_')) }
            ?: list.firstOrNull { ch -> q.split(' ').any { w -> w.length > 2 && ch.title.lowercase().contains(w) } }
    }

    /** Resumption after the app was killed (e.g. play pressed on a Bluetooth headset). */
    suspend fun resumptionItems(): MediaItemsWithStartPosition? {
        val preferred = graph.settings.current().currentChannel
        preferred?.let { prepareChannel(it, null, announce = false) }?.let { return it }
        for (ch in channels.enabled()) {
            prepareChannel(ch.username, null, announce = false)?.let { return it }
        }
        return null
    }

    /** Keeps cursors, heard marks and channel changes in step with the player. */
    val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = onMediaItemTransition(reason)

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded()
        }
    }

    fun onMediaItemTransition(reason: Int) {
        val index = player.currentMediaItemIndex
        val now = MediaIds.parse(player.currentMediaItem?.mediaId)
        val previous = lastItem
        val movedForward = index > lastIndex
        scope.launch {
            if (previous is MediaIds.Message && previous.channel == currentChannel &&
                (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || movedForward)
            ) {
                messages.markPlayed(previous.channel, previous.id)
            }
            if (now is MediaIds.Message) channels.setCursor(now.channel, now.id, 0)
            if (player.mediaItemCount - index < TOP_UP_THRESHOLD) topUp()
        }
        lastItem = now
        lastIndex = index
    }

    /** Called when the playlist finished: continue with the next channel or say so. */
    fun onEnded() {
        val finished = lastItem
        scope.launch {
            if (finished is MediaIds.Notice) return@launch
            val channel = currentChannel
            if (finished is MediaIds.Message && channel != null) {
                messages.markPlayed(finished.channel, finished.id)
                channels.setCursor(channel, finished.id + 1, 0)
            }
            if (graph.settings.current().autoAdvance && advanceToNextChannel()) return@launch
            playNotice(NOTICE_CAUGHT_UP, resumeAfter = false)
        }
    }

    private suspend fun advanceToNextChannel(): Boolean {
        val list = channels.enabled()
        if (list.isEmpty()) return false
        val idx = list.indexOfFirst { it.username == currentChannel }
        for (step in 1..list.size) {
            val candidate = list[Math.floorMod(idx + step, list.size)]
            if (messages.countFrom(candidate.username, candidate.cursorId, candidate.startDate) > 0) {
                return playChannel(candidate.username)
            }
        }
        return false
    }

    private suspend fun playNotice(key: String, resumeAfter: Boolean) {
        if (resumeAfter && player.mediaItemCount > 0 && player.currentMediaItem != null) {
            // Say it, then carry on where we were.
            val at = player.currentMediaItemIndex
            player.addMediaItem(at, noticeItem(key))
            player.seekTo(at, 0)
            player.play()
            return
        }
        player.setMediaItems(listOf(noticeItem(key)))
        player.prepare()
        player.play()
    }

    /** Appends posts that arrived (sync) or were beyond the first batch. */
    private suspend fun topUp() {
        val username = currentChannel ?: return
        val ch = channels.get(username) ?: return
        val more = messages.queue(username, lastQueuedId + 1, ch.startDate, QUEUE_BATCH)
        if (more.isEmpty() || currentChannel != username) return
        lastQueuedId = more.last().id
        val wasEnded = player.playbackState == Player.STATE_ENDED
        player.addMediaItems(more.map { messageItem(ch, it) })
        if (wasEnded) {
            player.seekTo(player.mediaItemCount - more.size, 0)
            player.prepare()
        }
    }

    private fun watchForNewPosts(username: String) {
        watchJob?.cancel()
        watchJob = scope.launch {
            messages.observeMaxId(username).filterNotNull().collect { maxId ->
                if (currentChannel == username && maxId > lastQueuedId) topUp()
            }
        }
    }

    suspend fun savePosition() {
        // After the playlist ended, onEnded already moved the cursor past the last post;
        // saving "the current item" here would pull it back and replay that post forever.
        if (player.playbackState == Player.STATE_ENDED) return
        val item = MediaIds.parse(player.currentMediaItem?.mediaId)
        if (item is MediaIds.Message && item.channel == currentChannel) {
            channels.setCursor(item.channel, item.id, player.currentPosition.coerceAtLeast(0))
        }
    }

    private fun artwork(ch: ChannelEntity): Uri? = ch.avatarPath?.let { File(it) }?.takeIf { it.exists() }?.let { Uri.fromFile(it) }

    private fun announceItem(ch: ChannelEntity): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.announce(ch.username))
        .setUri(MediaIds.announceUri(ch.username))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(ch.title)
                .setArtist(ch.title)
                .setArtworkUri(artwork(ch))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    private fun noticeItem(key: String): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.notice(key))
        .setUri(MediaIds.noticeUri(key))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(noticeText(key))
                .setArtist(context.getString(R.string.app_name))
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    fun noticeText(key: String): String = when (key) {
        NOTICE_NO_OTHER -> context.getString(R.string.notice_no_other_channels)
        else -> context.getString(R.string.notice_caught_up)
    }

    fun messageItem(ch: ChannelEntity, m: MessageEntity): MediaItem {
        val title = m.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(120) ?: ch.title
        val time = DateUtils.formatDateTime(
            context,
            m.date,
            DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH,
        )
        return MediaItem.Builder()
            .setMediaId(MediaIds.message(ch.username, m.id))
            .setUri(MediaIds.messageUri(ch.username, m.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(ch.title)
                    .setAlbumTitle(time)
                    .setDescription(m.text)
                    .setArtworkUri(artwork(ch))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(Bundle().apply { putString("channel", ch.username); putInt("id", m.id) })
                    .build(),
            )
            .build()
    }

    companion object {
        const val NOTICE_CAUGHT_UP = "caught_up"
        const val NOTICE_NO_OTHER = "no_other"
        private const val QUEUE_BATCH = 200
        private const val TOP_UP_THRESHOLD = 10
    }
}

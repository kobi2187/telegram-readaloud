package io.github.kobi2187.readaloud.data

import io.github.kobi2187.readaloud.telegram.ChannelInput
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Channel operations used by the UI. */
class ChannelRepository(
    private val db: AppDatabase,
    private val telegram: TelegramClient,
    private val renderer: AudioRenderer,
) {
    private val channels = db.channels()
    private val messages = db.messages()

    sealed interface AddResult {
        data class Added(val username: String, val title: String, val verified: Boolean) : AddResult
        data object InvalidName : AddResult
        data object AlreadyExists : AddResult
        data object NoPreview : AddResult
    }

    /** Adds a channel; checks it online when possible but also works offline (checked at next sync). */
    suspend fun add(input: String, startDate: Long): AddResult {
        val username = ChannelInput.parse(input) ?: return AddResult.InvalidName
        if (channels.get(username) != null) return AddResult.AlreadyExists
        var title = username
        var verified = false
        try {
            val page = telegram.fetchPage(username)
            title = page.title ?: username
            verified = true
        } catch (e: io.github.kobi2187.readaloud.telegram.ChannelUnavailableException) {
            return AddResult.NoPreview
        } catch (e: IOException) {
            // Offline: keep it and let the next sync fill in the details.
        }
        channels.insert(
            ChannelEntity(
                username = username,
                title = title,
                startDate = startDate,
                sortOrder = channels.nextSortOrder(),
            ),
        )
        return AddResult.Added(username, title, verified)
    }

    suspend fun remove(username: String) {
        messages.deleteChannel(username)
        channels.get(username)?.avatarPath?.let { File(it).delete() }
        channels.delete(username)
        withContext(Dispatchers.IO) { renderer.deleteChannelAudio(username) }
    }

    /** Changes where reading starts, and restarts the channel from that date. */
    suspend fun setStartDate(username: String, startDate: Long) {
        val c = channels.get(username) ?: return
        channels.update(c.copy(startDate = startDate, startId = null, cursorId = 0, cursorPositionMs = 0))
        messages.markAllUnplayed(username)
    }

    suspend fun markAllHeard(username: String) {
        val c = channels.get(username) ?: return
        val end = maxOf(c.fetchedTo, c.latestId, messages.maxId(username)) + 1
        channels.setCursor(username, end, 0)
        messages.markPlayedBefore(username, end)
    }

    suspend fun restart(username: String) {
        channels.setCursor(username, 0, 0)
        messages.markAllUnplayed(username)
    }

    suspend fun setEnabled(username: String, enabled: Boolean) {
        val c = channels.get(username) ?: return
        channels.update(c.copy(enabled = enabled))
    }

    suspend fun move(username: String, delta: Int) {
        val list = channels.all().toMutableList()
        val i = list.indexOfFirst { it.username == username }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val item = list.removeAt(i)
        list.add(j, item)
        list.forEachIndexed { index, c -> channels.setSortOrder(c.username, index) }
    }
}

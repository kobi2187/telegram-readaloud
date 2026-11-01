package io.github.kobi2187.readaloud.data

import io.github.kobi2187.readaloud.telegram.ChannelInput
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.web.ArticleText
import io.github.kobi2187.readaloud.web.NoFeedException
import io.github.kobi2187.readaloud.web.SiteRefusedException
import io.github.kobi2187.readaloud.web.WebClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Channel operations used by the UI. */
class ChannelRepository(
    private val db: AppDatabase,
    private val telegram: TelegramClient,
    private val renderer: AudioRenderer,
    private val web: WebClient,
) {
    private val channels = db.channels()
    private val messages = db.messages()

    sealed interface AddResult {
        data class Added(val username: String, val title: String, val verified: Boolean) : AddResult
        data object InvalidName : AddResult
        data object AlreadyExists : AddResult
        data object NoPreview : AddResult
        data object NoFeed : AddResult
        data object SiteRefused : AddResult
        data object Offline : AddResult
    }

    sealed interface SaveResult {
        data class Saved(val title: String?) : SaveResult
        data object AlreadySaved : SaveResult
    }

    /**
     * Adds a Telegram channel ("@name", t.me link) or a website (an address with a dot). Telegram
     * channels can be added offline (checked at the next sync); websites need a connection to find
     * their feed.
     */
    suspend fun add(input: String, startDate: Long): AddResult {
        // Telegram usernames cannot contain a dot, so anything else with one is a website.
        val username = ChannelInput.parse(input)
            ?: return if (input.contains('.') || input.trim().startsWith("http")) addWebsite(input, startDate) else AddResult.InvalidName
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

    private suspend fun addWebsite(input: String, startDate: Long): AddResult {
        val found = try {
            web.discover(input)
        } catch (e: NoFeedException) {
            return AddResult.NoFeed
        } catch (e: SiteRefusedException) {
            return AddResult.SiteRefused
        } catch (e: IOException) {
            return AddResult.Offline
        }
        val id = websiteId(found.url)
        if (channels.get(id) != null) return AddResult.AlreadyExists
        val title = found.title?.takeIf { it.isNotBlank() } ?: (WebClient.normalize(input)?.host ?: input)
        channels.insert(
            ChannelEntity(
                username = id,
                title = title,
                startDate = startDate,
                sortOrder = channels.nextSortOrder(),
                kind = ChannelKind.FEED,
                url = found.url,
            ),
        )
        return AddResult.Added(id, title, verified = true)
    }

    /**
     * Saves a shared link to the "saved" channel and reads the article now if online; offline, the
     * next sync fetches it. [savedTitle] names the channel the first time.
     */
    suspend fun saveLink(url: String, savedTitle: String): SaveResult {
        if (channels.get(ChannelKind.SAVED_ID) == null) {
            channels.insert(
                ChannelEntity(
                    username = ChannelKind.SAVED_ID,
                    title = savedTitle,
                    startDate = 0,
                    sortOrder = channels.firstSortOrder(),
                    kind = ChannelKind.SAVED,
                ),
            )
        }
        if (url in messages.guids(ChannelKind.SAVED_ID)) return SaveResult.AlreadySaved
        val id = messages.maxId(ChannelKind.SAVED_ID) + 1
        messages.insertAll(listOf(MessageEntity(ChannelKind.SAVED_ID, id, System.currentTimeMillis(), url, "", link = url, guid = url)))
        val article = try {
            web.fetchArticle(url)
        } catch (e: IOException) {
            return SaveResult.Saved(null)
        }
        messages.setArticle(ChannelKind.SAVED_ID, id, ArticleText.display(article.title, article), ArticleText.speech(article.title, article))
        return SaveResult.Saved(article.title)
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

    companion object {
        /** A stable, file-name-safe id for a website channel. */
        fun websiteId(feedUrl: String): String = "web_" + Integer.toHexString(feedUrl.lowercase().trimEnd('/').hashCode())
    }
}

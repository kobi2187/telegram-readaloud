package io.github.kobi2187.readaloud.sync

import android.util.Log
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.AppSettings
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.telegram.ChannelUnavailableException
import io.github.kobi2187.readaloud.telegram.RateLimitedException
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.telegram.TgMessage
import io.github.kobi2187.readaloud.telegram.TgPage
import io.github.kobi2187.readaloud.tts.AudioRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

data class SyncState(
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    val channel: String? = null,
    val audioDone: Int = 0,
    val audioTotal: Int = 0,
    val lastFinishedAt: Long = 0,
    val offline: Boolean = false,
) {
    enum class Phase { IDLE, TEXT, AUDIO }
}

/**
 * Downloads posts and their neural audio. Designed for sporadic connectivity: every page and
 * every audio file is committed as soon as it arrives, so an interrupted sync resumes where it
 * stopped, and nothing here is required for playback, which works fully offline.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val telegram: TelegramClient,
    private val renderer: AudioRenderer,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    private val avatarDir: File,
) {
    private val channels = db.channels()
    private val messages = db.messages()
    private val history = ChannelHistory(PageSource { u, before, after -> telegram.fetchPage(u, before, after) })
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private class Deadline(private val at: Long) {
        val passed: Boolean get() = System.currentTimeMillis() >= at
    }

    /**
     * Runs one sync pass. Returns false if another pass is already running. [deadlineMillis]
     * bounds the run (WorkManager stops workers after ~10 minutes).
     */
    suspend fun sync(deadlineMillis: Long = Long.MAX_VALUE, only: String? = null): Boolean {
        if (!mutex.tryLock()) return false
        try {
            val cfg = settings.current()
            if (!network.isOnline(cfg.allowMetered)) {
                _state.update { it.copy(offline = true) }
                return true
            }
            _state.value = SyncState(running = true, phase = SyncState.Phase.TEXT, lastFinishedAt = _state.value.lastFinishedAt)
            val deadline = Deadline(deadlineMillis)
            val list = (if (only != null) listOfNotNull(channels.get(only)) else channels.enabled())
                .sortedBy { it.username != cfg.currentChannel }
            syncText(list, deadline)
            if (renderer.cloudReady(cfg) && !deadline.passed) prefetchAudio(cfg, deadline)
            cleanup(cfg)
            return true
        } finally {
            _state.value = SyncState(lastFinishedAt = System.currentTimeMillis(), offline = !network.isOnline())
            mutex.unlock()
        }
    }

    private suspend fun syncText(list: List<ChannelEntity>, deadline: Deadline) {
        for (ch in list) {
            if (deadline.passed) break
            _state.update { it.copy(channel = ch.username) }
            try {
                syncChannel(ch, deadline)
                channels.setSyncResult(ch.username, null, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChannelUnavailableException) {
                channels.setSyncResult(ch.username, ERROR_NO_PREVIEW, System.currentTimeMillis())
            } catch (e: RateLimitedException) {
                channels.setSyncResult(ch.username, ERROR_RATE_LIMIT, System.currentTimeMillis())
                break
            } catch (e: IOException) {
                Log.w(TAG, "Sync of ${ch.username} failed", e)
                channels.setSyncResult(ch.username, e.message ?: ERROR_NETWORK, System.currentTimeMillis())
                if (!network.isOnline()) break
            }
        }
    }

    private suspend fun syncChannel(original: ChannelEntity, deadline: Deadline) {
        val latest = telegram.fetchPage(original.username)
        var c = original.copy(
            title = latest.title ?: original.title,
            description = latest.description ?: original.description,
            latestId = maxOf(original.latestId, latest.maxId ?: 0),
        )
        c = refreshAvatar(c, latest.avatarUrl)
        store(c, latest.messages)
        channels.update(c)

        if (c.startId == null) {
            val startId = locateStart(c, latest, deadline)
            c = when {
                c.fetchedFrom == 0 || startId > c.fetchedTo + 1 ->
                    c.copy(startId = startId, fetchedFrom = startId, fetchedTo = startId - 1)
                startId >= c.fetchedFrom -> c.copy(startId = startId)
                else -> {
                    // The start moved earlier than what we have: fill the hole first.
                    val reached = forward(c, startId - 1, c.fetchedFrom - 1, deadline) {}
                    if (reached >= c.fetchedFrom - 1) {
                        c.copy(startId = startId, fetchedFrom = startId)
                    } else {
                        c.copy(startId = startId, fetchedFrom = startId, fetchedTo = reached)
                    }
                }
            }
            channels.update(c)
        }

        val latestMin = latest.minId
        c = if (latestMin != null && latestMin <= c.fetchedTo + 1) {
            c.copy(fetchedTo = maxOf(c.fetchedTo, latest.maxId ?: c.fetchedTo))
        } else {
            var progress = c
            val reached = forward(c, c.fetchedTo, c.latestId, deadline) { at ->
                progress = progress.copy(fetchedTo = at)
                channels.update(progress)
            }
            progress.copy(fetchedTo = maxOf(progress.fetchedTo, reached))
        }
        channels.update(c)
    }

    private suspend fun forward(
        c: ChannelEntity,
        afterId: Int,
        stopAt: Int,
        deadline: Deadline,
        onProgress: suspend (Int) -> Unit,
    ): Int = history.forward(
        username = c.username,
        afterId = afterId,
        stopAt = stopAt,
        keepGoing = { !deadline.passed },
        onPage = { page -> store(c, page.messages) },
        onProgress = onProgress,
    )

    private suspend fun locateStart(c: ChannelEntity, latest: TgPage, deadline: Deadline): Int =
        history.locateStart(c.username, c.startDate, latest) { !deadline.passed }

    private suspend fun store(c: ChannelEntity, page: List<TgMessage>) {
        val rows = page.filter { !it.isService && it.dateMillis >= c.startDate }.map {
            MessageEntity(
                channel = c.username,
                id = it.id,
                date = it.dateMillis,
                text = it.text,
                speech = it.speech,
                media = it.media,
                edited = it.edited,
                forwardedFrom = it.forwardedFrom,
            )
        }
        messages.upsertFromWeb(rows)
    }

    private suspend fun refreshAvatar(c: ChannelEntity, url: String?): ChannelEntity {
        if (url == null) return c
        val file = File(avatarDir, "${c.username}.jpg")
        val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > AVATAR_MAX_AGE_MS
        if (!stale && c.avatarPath == file.path) return c
        return try {
            telegram.download(url, file)
            c.copy(avatarUrl = url, avatarPath = file.path)
        } catch (e: IOException) {
            c
        }
    }

    /**
     * Downloads neural audio for the next posts of every channel, round-robin in small batches so
     * each channel gets some coverage before the connection drops.
     */
    private suspend fun prefetchAudio(cfg: AppSettings, deadline: Deadline) {
        val list = channels.enabled().sortedBy { it.username != cfg.currentChannel }
        val queues = list.map { ch ->
            messages.queue(ch.username, ch.cursorId, ch.startDate, cfg.prefetchCount)
                .filter { renderer.needsNeural(it, cfg) }
        }
        val ordered = buildList {
            var round = 0
            while (true) {
                var added = false
                for (q in queues) {
                    val batch = q.drop(round * BATCH).take(BATCH)
                    if (batch.isNotEmpty()) { addAll(batch); added = true }
                }
                if (!added) break
                round++
            }
        }
        _state.update { it.copy(phase = SyncState.Phase.AUDIO, audioDone = 0, audioTotal = ordered.size, channel = null) }

        withContext(Dispatchers.IO) {
            list.forEach { ch -> runCatching { renderer.ensureClip("ch_${ch.username}", ch.title) } }
        }

        val failures = AtomicInteger(0)
        val done = AtomicInteger(0)
        val permits = Semaphore(PARALLEL_RENDERS)
        coroutineScope {
            ordered.map { m ->
                async(Dispatchers.IO) {
                    permits.withPermit {
                        if (deadline.passed || failures.get() >= MAX_CONSECUTIVE_FAILURES ||
                            !network.isOnline(cfg.allowMetered)
                        ) {
                            return@withPermit
                        }
                        when (renderer.prefetchNeural(m, cfg)) {
                            AudioRenderer.Prefetch.DONE -> {
                                failures.set(0)
                                val n = done.incrementAndGet()
                                _state.update { it.copy(audioDone = n, channel = m.channel) }
                            }
                            AudioRenderer.Prefetch.NETWORK_FAILED -> failures.incrementAndGet()
                            // Bad key or quota: stop; device voice covers playback.
                            AudioRenderer.Prefetch.CLOUD_UNAVAILABLE -> failures.set(MAX_CONSECUTIVE_FAILURES)
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun cleanup(cfg: AppSettings) {
        if (cfg.deletePlayedAudio) {
            for (ch in channels.all()) {
                for (m in messages.playedWithAudio(ch.username, ch.cursorId, keep = KEEP_PLAYED_AUDIO)) {
                    m.audioPath?.let { File(it).delete() }
                    withContext(Dispatchers.IO) {
                        messages.setAudioBlocking(m.channel, m.id, if (m.audioState == AudioState.NEURAL_FAILED) AudioState.NEURAL_FAILED else AudioState.NONE, null, null)
                    }
                }
            }
        }
        val referenced = messages.allAudioPaths().toHashSet()
        withContext(Dispatchers.IO) { renderer.deleteOrphans(referenced) }
    }

    companion object {
        private const val TAG = "SyncEngine"
        const val ERROR_NO_PREVIEW = "no_preview"
        const val ERROR_RATE_LIMIT = "rate_limited"
        const val ERROR_NETWORK = "network"
        private const val AVATAR_MAX_AGE_MS = 7L * 24 * 3600 * 1000
        private const val BATCH = 10
        private const val PARALLEL_RENDERS = 1 // the service paces requests anyway
        private const val MAX_CONSECUTIVE_FAILURES = 4
        private const val KEEP_PLAYED_AUDIO = 3
    }
}

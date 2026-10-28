package io.github.kobi2187.readaloud.sync

import io.github.kobi2187.readaloud.telegram.TgMessage
import io.github.kobi2187.readaloud.telegram.TgPage
import kotlinx.coroutines.delay

/** Anything that serves t.me/s pages; the real one is [io.github.kobi2187.readaloud.telegram.TelegramClient]. */
fun interface PageSource {
    suspend fun fetchPage(username: String, before: Int?, after: Int?): TgPage
}

/**
 * Navigation over a channel's history using only the web preview's paging: finds the first post
 * at a date (binary search over post ids, which grow with time) and pages forward from an id.
 */
class ChannelHistory(
    private val pages: PageSource,
    private val politeDelayMs: Long = 250,
) {
    /**
     * Returns the id of the first post published at or after [startDate], or one past the newest
     * post when there is none yet. [latest] is the channel's newest page.
     */
    suspend fun locateStart(
        username: String,
        startDate: Long,
        latest: TgPage,
        keepGoing: () -> Boolean = { true },
    ): Int {
        val onPage = latest.messages
        if (onPage.isEmpty()) return (latest.maxId ?: 0) + 1
        if (onPage.first().dateMillis < startDate) {
            return onPage.firstOrNull { it.dateMillis >= startDate }?.id ?: (onPage.last().id + 1)
        }
        var lo = 0 // every post with id <= lo is older than the start date
        var hi = onPage.first().id // hi is at or after the start date
        var probes = 0
        while (hi - lo > 1 && probes < MAX_PROBES && keepGoing()) {
            val mid = lo + (hi - lo) / 2
            var probe = mid
            var ms = emptyList<TgMessage>()
            for (hop in 0 until MAX_GAP_HOPS) {
                val page = pages.fetchPage(username, probe + 1, null)
                probes++
                delay(politeDelayMs)
                ms = page.messages.filter { it.id <= mid }
                if (ms.isNotEmpty()) break
                // An empty window means a stretch of deleted posts: look further back.
                probe = page.prevBefore?.minus(1)?.takeIf { it < probe } ?: (probe - EMPTY_STRETCH_STEP)
                if (probe <= lo) break
            }
            when {
                ms.isEmpty() || ms.last().dateMillis < startDate -> lo = mid
                ms.first().dateMillis >= startDate -> hi = ms.first().id
                else -> return ms.first { it.dateMillis >= startDate }.id
            }
        }
        return hi
    }

    /**
     * Pages forward from [afterId] until [stopAt], handing every page to [onPage] and reporting
     * the highest id covered so far to [onProgress]. Returns the highest id covered.
     */
    suspend fun forward(
        username: String,
        afterId: Int,
        stopAt: Int,
        maxPages: Int = MAX_PAGES,
        keepGoing: () -> Boolean = { true },
        onPage: suspend (TgPage) -> Unit,
        onProgress: suspend (Int) -> Unit = {},
    ): Int {
        var cursor = afterId
        var count = 0
        while (cursor < stopAt && count < maxPages && keepGoing()) {
            val page = pages.fetchPage(username, null, cursor)
            count++
            onPage(page)
            val maxId = page.maxId
            val next = page.nextAfter
            cursor = when {
                maxId != null && maxId > cursor -> maxId
                next != null && next > cursor -> next
                else -> cursor + EMPTY_STRETCH_STEP // a run of deleted posts
            }
            onProgress(cursor)
            delay(politeDelayMs)
        }
        return cursor
    }

    companion object {
        const val MAX_PROBES = 60
        const val MAX_GAP_HOPS = 5
        const val MAX_PAGES = 250
        const val EMPTY_STRETCH_STEP = 20
    }
}

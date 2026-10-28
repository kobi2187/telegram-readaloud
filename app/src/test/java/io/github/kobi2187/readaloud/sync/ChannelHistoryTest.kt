package io.github.kobi2187.readaloud.sync

import io.github.kobi2187.readaloud.telegram.TgMessage
import io.github.kobi2187.readaloud.telegram.TgPage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * A simulated channel. [windowed] mimics t.me's real behaviour of answering with a window of ~20
 * ids (which can be empty after mass deletions) instead of the 20 nearest existing posts.
 */
private class FakeChannel(val posts: Map<Int, Long>, private val windowed: Boolean) : PageSource {
    var requests = 0
    private val ids = posts.keys.sorted()

    private fun msg(id: Int) = TgMessage(id, posts.getValue(id), "p$id", "p$id", null, false, false, null)

    private fun page(selected: List<Int>): TgPage {
        val min = selected.minOrNull()
        val max = selected.maxOrNull()
        val hasOlder = min != null && ids.first() < min
        val hasNewer = max != null && ids.last() > max
        return TgPage("c", "C", null, null, selected.map(::msg), if (hasOlder) min else null, if (hasNewer) max else null)
    }

    override suspend fun fetchPage(username: String, before: Int?, after: Int?): TgPage {
        requests++
        val selected = when {
            before != null && windowed -> ids.filter { it in (before - 20) until before }
            before != null -> ids.filter { it < before }.takeLast(20)
            after != null && windowed -> ids.filter { it in (after + 1)..(after + 20) }
            after != null -> ids.filter { it > after }.take(20)
            else -> ids.takeLast(20)
        }
        return page(selected)
    }
}

class ChannelHistoryTest {

    private fun channel(count: Int, seed: Int, deleteRate: Double, windowed: Boolean, bigGapAt: Int? = null): FakeChannel {
        val rnd = Random(seed)
        val posts = LinkedHashMap<Int, Long>()
        var t = 1_700_000_000_000L
        for (id in 1..count) {
            t += rnd.nextLong(1, 3_600_000)
            val inGap = bigGapAt != null && id in bigGapAt until bigGapAt + 75
            if (!inGap && rnd.nextDouble() >= deleteRate) posts[id] = t
        }
        return FakeChannel(posts, windowed)
    }

    private fun expectedStart(ch: FakeChannel, start: Long): Int =
        ch.posts.entries.sortedBy { it.key }.firstOrNull { it.value >= start }?.key ?: (ch.posts.keys.max() + 1)

    private suspend fun check(ch: FakeChannel, start: Long) {
        val history = ChannelHistory(ch, politeDelayMs = 0)
        val latest = ch.fetchPage("c", null, null)
        val got = history.locateStart("c", start, latest)
        assertEquals("start=$start", expectedStart(ch, start), got)
    }

    @Test
    fun findsStartDateExactlyForManyChannels() = runTest {
        for (seed in 1..30) {
            for (windowed in listOf(false, true)) {
                val ch = channel(count = 3000, seed = seed, deleteRate = 0.15, windowed = windowed)
                val times = ch.posts.values.sorted()
                val rnd = Random(seed * 7)
                repeat(5) { check(ch, times[rnd.nextInt(times.size)]) }
                check(ch, times.first() - 1) // before everything
                check(ch, times.last() + 1) // after everything
                check(ch, times[times.size - 3]) // on the newest page
            }
        }
    }

    @Test
    fun survivesLongStretchesOfDeletedPosts() = runTest {
        for (seed in 1..20) {
            val ch = channel(count = 2000, seed = seed, deleteRate = 0.05, windowed = true, bigGapAt = 900)
            val times = ch.posts.entries.sortedBy { it.key }
            val beforeGap = times.last { it.key < 900 }.value
            val afterGap = times.first { it.key >= 975 }.value
            check(ch, beforeGap)
            check(ch, afterGap)
            check(ch, beforeGap + 1)
        }
    }

    @Test
    fun usesFewRequests() = runTest {
        val ch = channel(count = 200_000, seed = 3, deleteRate = 0.1, windowed = false)
        val times = ch.posts.values.sorted()
        val history = ChannelHistory(ch, politeDelayMs = 0)
        val latest = ch.fetchPage("c", null, null)
        ch.requests = 0
        history.locateStart("c", times[times.size / 3], latest)
        assert(ch.requests <= 20) { "took ${ch.requests} requests" }
    }

    @Test
    fun forwardCoversEverythingWithoutGaps() = runTest {
        for (windowed in listOf(false, true)) {
            val ch = channel(count = 1500, seed = 11, deleteRate = 0.2, windowed = windowed, bigGapAt = 600)
            val history = ChannelHistory(ch, politeDelayMs = 0)
            val seen = sortedSetOf<Int>()
            val reached = history.forward("c", afterId = 99, stopAt = ch.posts.keys.max(), onPage = { p -> p.messages.forEach { seen += it.id } })
            assertEquals(ch.posts.keys.filter { it >= 100 }.toSortedSet(), seen)
            assert(reached >= ch.posts.keys.max())
        }
    }
}

package io.github.kobi2187.readaloud

import io.github.kobi2187.readaloud.sync.ChannelHistory
import io.github.kobi2187.readaloud.sync.PageSource
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.text.LanguageSegmenter
import io.github.kobi2187.readaloud.tts.AzureTts
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Talks to the real services. Run with `LIVE_TESTS=1 ./gradlew testDebugUnitTest`
 * (plus AZURE_SPEECH_KEY / AZURE_SPEECH_REGION for the voice test). Audio samples are written to
 * app/build/live-samples for listening.
 */
class LiveServicesTest {
    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
    private val out = File("build/live-samples").apply { mkdirs() }

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getenv("LIVE_TESTS") == "1")

    @Test
    fun azureSpeaksMixedHebrewAndEnglish() {
        val key = System.getenv("AZURE_SPEECH_KEY")
        assumeTrue("set AZURE_SPEECH_KEY to run", !key.isNullOrBlank())
        val account = AzureTts.Account(key!!, System.getenv("AZURE_SPEECH_REGION") ?: AzureTts.DEFAULT_REGION)
        val text = "עדכון חדשות: הממשלה התכנסה הבוקר.\nThe White House said talks will continue tomorrow."
        val parts = LanguageSegmenter.segment(text).map { (if (it.lang.name == "HE") "he-IL-HilaNeural" else "en-US-AvaNeural") to it.text }
        val bytes = AzureTts(http).synthesize(account, parts, trailingSilenceMs = 700)
        assertTrue(bytes.size > 2000)
        assertEquals(0xFF, bytes[0].toInt() and 0xFF)
        File(out, "azure-mixed.mp3").writeBytes(bytes)
    }

    @Test
    fun telegramStartDateSearchOnARealChannel() = runBlocking {
        val tg = TelegramClient(http)
        val history = ChannelHistory(PageSource { u, b, a -> tg.fetchPage(u, b, a) })
        val latest = tg.fetchPage("durov")
        // Three months back: the result must be the first post on/after that instant.
        val start = System.currentTimeMillis() - 90L * 24 * 3600 * 1000
        val id = history.locateStart("durov", start, latest)
        val around = tg.fetchPage("durov", before = id + 1).messages.filter { !it.isService }
        val at = around.first { it.id == id }
        assertTrue(at.dateMillis >= start)
        around.filter { it.id < id }.forEach { assertTrue(it.dateMillis < start) }
        // Forward paging from there reaches the newest post without holes.
        val ids = mutableListOf<Int>()
        history.forward("durov", id - 1, latest.maxId!!, onPage = { p -> ids += p.messages.map { it.id } })
        assertTrue(ids.contains(latest.maxId!!))
        assertEquals(ids.distinct().sorted(), ids.distinct())
    }
}

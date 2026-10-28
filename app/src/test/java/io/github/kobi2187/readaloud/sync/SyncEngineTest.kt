package io.github.kobi2187.readaloud.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.ChannelRepository
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.SystemTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

/** End-to-end sync against a simulated t.me, with a real (in-memory) database. */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
    private val server = MockWebServer()
    private val telegram = FakeTelegramServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var engine: SyncEngine
    private lateinit var repo: ChannelRepository

    private val day = 24 * 3600 * 1000L
    private val t0 = 1_780_000_000_000L

    @Before
    fun setUp() {
        server.dispatcher = telegram
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val online = object : NetworkMonitor(context) {
            override fun isOnline(allowMetered: Boolean) = true
        }
        val http = OkHttpClient()
        val client = TelegramClient(http, server.url("/"))
        val settings = SettingsRepository(context, scope)
        val renderer = AudioRenderer(context, db.messages(), settings, AzureTts(http), SystemTts(context), online)
        engine = SyncEngine(db, client, renderer, settings, online, File(context.cacheDir, "avatars"))
        repo = ChannelRepository(db, client, renderer)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        scope.cancel()
    }

    /** 600 posts over 60 days, ~10% deleted. */
    private fun busyChannel(name: String) {
        val ch = FakeTelegramServer.Channel("Busy $name")
        val rnd = Random(42)
        for (id in 1..600) if (rnd.nextDouble() > 0.1) ch.posts[id] = t0 + id * (day / 10)
        telegram.channels[name] = ch
    }

    private fun expectedIds(name: String, start: Long) =
        telegram.channels.getValue(name).posts.filter { it.value >= start }.keys.toList()

    private fun storedIds(name: String, start: Long) = runBlocking {
        db.messages().queue(name, 0, start, 10_000).map { it.id }
    }

    @Test
    fun firstSyncDownloadsExactlyFromTheStartDate() = runBlocking {
        busyChannel("news")
        val start = t0 + 40 * day
        assertTrue(repo.add("news", start) is ChannelRepository.AddResult.Added)
        engine.sync()
        assertEquals(expectedIds("news", start), storedIds("news", start))
        val ch = db.channels().get("news")!!
        assertEquals("Busy news", ch.title)
        assertEquals(expectedIds("news", start).first(), ch.startId)
        assertEquals(telegram.channels.getValue("news").newest, ch.fetchedTo)
        assertEquals(null, ch.lastError)
    }

    @Test
    fun laterSyncsOnlyFetchWhatIsNew() = runBlocking {
        busyChannel("news")
        val start = t0 + 50 * day
        repo.add("news", start)
        engine.sync()
        val ch = telegram.channels.getValue("news")
        val last = ch.newest
        for (id in last + 1..last + 30) ch.posts[id] = t0 + id * (day / 10)
        telegram.requests.clear()
        engine.sync()
        assertEquals(expectedIds("news", start), storedIds("news", start))
        assertTrue("used ${telegram.requests}", telegram.requests.size <= 4)
    }

    @Test
    fun movingTheStartEarlierFillsTheGap() = runBlocking {
        busyChannel("news")
        repo.add("news", t0 + 55 * day)
        engine.sync()
        val earlier = t0 + 20 * day
        repo.setStartDate("news", earlier)
        engine.sync()
        assertEquals(expectedIds("news", earlier), storedIds("news", earlier))
    }

    @Test
    fun channelWithoutPreviewIsReported() = runBlocking {
        db.channels().insert(ChannelEntity(username = "hidden", title = "hidden", startDate = 0, sortOrder = 0))
        engine.sync()
        assertEquals(SyncEngine.ERROR_NO_PREVIEW, db.channels().get("hidden")!!.lastError)
    }

    @Test
    fun addRefusesChannelsWithoutPreview() = runBlocking {
        assertEquals(ChannelRepository.AddResult.NoPreview, repo.add("t.me/hidden", 0))
        assertEquals(ChannelRepository.AddResult.InvalidName, repo.add("not a channel!", 0))
    }
}

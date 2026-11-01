package io.github.kobi2187.readaloud.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.ChannelKind
import io.github.kobi2187.readaloud.data.ChannelRepository
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.telegram.TelegramClient
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.GeminiTts
import io.github.kobi2187.readaloud.tts.NoOfflineHebrew
import io.github.kobi2187.readaloud.tts.SystemTts
import io.github.kobi2187.readaloud.web.WebClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections

/** Websites become channels of articles; shared links are saved to listen to later. */
@RunWith(AndroidJUnit4::class)
class WebsiteSyncTest {
    private val server = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase
    private lateinit var engine: SyncEngine
    private lateinit var repo: ChannelRepository
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val t0 = Instant.parse("2026-11-01T06:00:00Z").toEpochMilli()

    private data class Item(val id: String, val title: String, val minutes: Long)

    private val items = Collections.synchronizedList(
        mutableListOf(
            Item("old", "Yesterday's story", -24 * 60),
            Item("a1", "ראש הממשלה נפגש עם שר הביטחון", 10),
            Item("a2", "Markets open higher", 20),
            Item("a3", "Premium analysis", 30),
        ),
    )
    @Volatile private var savedPageUp = false

    private val para = "This is a long enough paragraph of the article body to be counted as real text."

    private fun rss(): String {
        val fmt = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
        return """<?xml version="1.0" encoding="utf-8"?><rss version="2.0"><channel><title>Example News</title>""" +
            items.joinToString("") {
                "<item><title>${it.title}</title><link>${server.url("/${it.id}")}</link><guid>${it.id}</guid>" +
                    "<pubDate>${fmt.format(Instant.ofEpochMilli(t0 + it.minutes * 60_000))}</pubDate>" +
                    "<description>Summary of ${it.id}.</description></item>"
            } + "</channel></rss>"
    }

    private fun page(path: String): MockResponse = when (path) {
        "/" -> MockResponse().setBody(
            """<html><head><title>Example</title><link rel="alternate" type="application/rss+xml" href="/news.rss"></head><body>Home</body></html>""",
        )
        "/news.rss" -> MockResponse().setHeader("Content-Type", "application/rss+xml").setBody(rss())
        "/a1" -> MockResponse().setBody(
            """<html><head><script type="application/ld+json">{"@type":"NewsArticle","headline":"x",
               "articleBody":"${"$para ".repeat(3)}\n${"$para ".repeat(3)}"}</script></head><body></body></html>""",
        )
        "/a2", "/a4" -> MockResponse().setBody("<html><body><div class=story>${(1..5).joinToString("") { "<p>$para $it</p>" }}</div></body></html>")
        "/a3" -> MockResponse().setResponseCode(403)
        "/shared" -> if (savedPageUp) {
            MockResponse().setBody("""<html><head><meta property="og:title" content="A shared story | Site"></head><body><article>${(1..4).joinToString("") { "<p>$para</p>" }}</article></body></html>""")
        } else {
            MockResponse().setResponseCode(503)
        }
        else -> MockResponse().setResponseCode(404)
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.path!!
                return page(request.path!!)
            }
        }
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val online = object : NetworkMonitor(context) {
            override fun isOnline(allowMetered: Boolean) = true
        }
        val http = OkHttpClient()
        val settings = SettingsRepository(context, scope)
        val renderer = AudioRenderer(context, db.messages(), settings, GeminiTts(http), AzureTts(http), SystemTts(context), online, NoOfflineHebrew)
        val web = WebClient(http)
        engine = SyncEngine(db, TelegramClient(http, server.url("/tg/")), renderer, settings, online, File(context.cacheDir, "avatars"), web)
        repo = ChannelRepository(db, TelegramClient(http, server.url("/tg/")), renderer, web)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        scope.cancel()
    }

    @Test
    fun aWebsiteBecomesAChannelOfArticles() = runBlocking {
        val added = repo.add(server.url("/").toString(), startDate = t0) as ChannelRepository.AddResult.Added
        assertEquals("Example News", added.title)
        val ch = db.channels().get(added.username)!!
        assertEquals(ChannelKind.FEED, ch.kind)
        assertEquals(server.url("/news.rss").toString(), ch.url)
        assertEquals("adding it again is refused", ChannelRepository.AddResult.AlreadyExists, repo.add(server.url("/news.rss").toString(), t0))

        engine.sync(only = added.username)
        val posts = db.messages().queue(added.username, 0, 0, 100)
        assertEquals("items before the start date are skipped", listOf("a1", "a2", "a3"), posts.map { it.guid })
        assertEquals(listOf(1, 2, 3), posts.map { it.id })
        assertTrue(posts[0].speech, posts[0].speech.startsWith("ראש הממשלה נפגש עם שר הביטחון.") && posts[0].speech.contains(para))
        assertTrue(posts[1].text.startsWith("Markets open higher\n\n$para 1"))
        assertEquals(server.url("/a2").toString(), posts[1].link)
        assertTrue("a refused page falls back to the feed summary: " + posts[2].speech, posts[2].speech.endsWith("Summary of a3.\nThis is only a summary."))

        requests.clear()
        items += Item("a4", "Evening update", 40)
        engine.sync(only = added.username)
        val after = db.messages().queue(added.username, 0, 0, 100)
        assertEquals(listOf("a1", "a2", "a3", "a4"), after.map { it.guid })
        assertEquals(4, after.last().id)
        assertEquals("only the new article is fetched", listOf("/news.rss", "/a4"), requests)
    }

    @Test
    fun aSiteWithoutAFeedIsReported() = runBlocking {
        assertEquals(ChannelRepository.AddResult.NoFeed, repo.add(server.url("/a2").toString(), t0))
        assertEquals(ChannelRepository.AddResult.SiteRefused, repo.add(server.url("/a3").toString(), t0))
        assertEquals(ChannelRepository.AddResult.InvalidName, repo.add("not a site", t0))
    }

    @Test
    fun sharedLinksAreSavedAndFetchedLaterWhenNeeded() = runBlocking {
        val url = server.url("/shared").toString()
        val first = repo.saveLink(url, "Saved for listening")
        assertEquals("the page was down: saved for later", ChannelRepository.SaveResult.Saved(null), first)
        assertEquals(ChannelRepository.SaveResult.AlreadySaved, repo.saveLink(url, "Saved for listening"))
        val saved = db.channels().get(ChannelKind.SAVED_ID)!!
        assertEquals(ChannelKind.SAVED, saved.kind)
        assertEquals("Saved for listening", saved.title)
        assertTrue("nothing to read yet", db.messages().queue(ChannelKind.SAVED_ID, 0, 0, 10).isEmpty())

        savedPageUp = true
        engine.sync(only = ChannelKind.SAVED_ID)
        val post = db.messages().queue(ChannelKind.SAVED_ID, 0, 0, 10).single()
        assertTrue(post.text, post.text.startsWith("A shared story\n\n$para"))
        assertEquals(url, post.link)

        val second = repo.saveLink(server.url("/a2").toString(), "Saved for listening") as ChannelRepository.SaveResult.Saved
        assertEquals("Markets", second.title?.take(0) + "Markets")
        assertEquals(2, db.messages().queue(ChannelKind.SAVED_ID, 0, 0, 10).size)
    }
}

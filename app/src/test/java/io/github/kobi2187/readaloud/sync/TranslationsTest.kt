package io.github.kobi2187.readaloud.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.data.resetForTest
import io.github.kobi2187.readaloud.web.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.URLDecoder

@RunWith(AndroidJUnit4::class)
class TranslationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var translations: Translations
    private val asked = mutableListOf<String>()

    private fun gtx(text: String, source: String) = MockResponse().setBody(
        buildJsonArray {
            addJsonArray { addJsonArray { add(text); add("orig") } }
            add(null as String?)
            add(source)
        }.toString(),
    )

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val translator = Translator(OkHttpClient(), server.url("/free"), server.url("/cloud"), minIntervalMs = 0)
        translations = Translations(db.messages(), translator, SettingsRepository(context, scope).resetForTest())
        runBlocking {
            db.channels().insert(ChannelEntity(username = "news", title = "News", startDate = 0, sortOrder = 0, translateTo = "he"))
            db.messages().insertAll(
                listOf(
                    MessageEntity("news", 1, 1000, "", "Hello world."),
                    MessageEntity("news", 2, 2000, "", "שלום עולם."),
                    MessageEntity("news", 3, 3000, "", "Good night."),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
        scope.cancel()
    }

    private fun drainAsked() {
        while (true) {
            val r = server.takeRequest(0, java.util.concurrent.TimeUnit.SECONDS) ?: return
            asked += URLDecoder.decode(r.body.readUtf8().removePrefix("q="), "UTF-8")
        }
    }

    @Test
    fun upcomingPostsAreTranslatedOnceAndThoseAlreadyInTheLanguageAreNotSent() = runBlocking {
        server.enqueue(gtx("שלום עולם.", "en"))
        server.enqueue(gtx("לילה טוב.", "en"))
        translations.translatePending(listOf(db.channels().get("news")!!)) { false }
        drainAsked()
        assertEquals(listOf("Hello world.", "Good night."), asked)
        assertEquals("שלום עולם.", db.messages().get("news", 1)!!.speechIn("he"))
        assertEquals("שלום עולם.", db.messages().get("news", 2)!!.speechIn("he"))
        assertEquals("the original is kept", "Hello world.", db.messages().get("news", 1)!!.speechIn(null))
        translations.translatePending(listOf(db.channels().get("news")!!)) { false }
        drainAsked()
        assertEquals("nothing left to translate", 2, asked.size)
    }

    @Test
    fun whenGoogleLimitsRequestsTranslationPausesAndTheOriginalIsRead() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))
        assertNull(translations.ensure(db.messages().get("news", 1)!!, "he"))
        assertTrue(translations.paused)
        assertNull(translations.ensure(db.messages().get("news", 3)!!, "he"))
        assertEquals("no requests while paused", 1, server.requestCount)
    }

    @Test
    fun aTranslatedChannelIsReadFromItsTranslationWithoutItsSignature() = runBlocking {
        val sig = "Join us"
        db.messages().insertAll(
            listOf(
                MessageEntity("news", 4, 4000, "", "One.\n\n$sig"),
                MessageEntity("news", 5, 5000, "", "Two.\n\n$sig"),
                MessageEntity("news", 6, 6000, "", "Three.\n\n$sig"),
            ),
        )
        db.channels().setTranslateTo("news", null)
        val spoken = SpokenText(db.messages(), db.channels(), translations)
        assertEquals("One.", spoken.of(db.messages().get("news", 4)!!))
        db.channels().setTranslateTo("news", "he")
        server.enqueue(gtx("שלום עולם.", "en"))
        assertEquals("שלום עולם.", spoken.of(db.messages().get("news", 1)!!))
    }
}

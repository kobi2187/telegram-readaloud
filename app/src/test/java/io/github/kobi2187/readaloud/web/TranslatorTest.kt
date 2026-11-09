package io.github.kobi2187.readaloud.web

import io.github.kobi2187.readaloud.tts.ThrottledException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonArray
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URLDecoder

class TranslatorTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.shutdown()

    private fun translator() = Translator(OkHttpClient(), server.url("/free"), server.url("/cloud"), minIntervalMs = 0)

    /** The free endpoint's answer: segments of [translated, original, ...], then the source language. */
    private fun gtx(parts: List<String>, source: String) = MockResponse().setBody(
        buildJsonArray {
            addJsonArray { parts.forEach { p -> addJsonArray { add(p); add("orig") } } }
            add(null as String?)
            add(source)
        }.toString(),
    )

    @Test
    fun postsTheTextToTheFreeEndpointAndJoinsTheSegments() {
        server.enqueue(gtx(listOf("The Prime Minister met the minister.\n", "Second paragraph."), "iw"))
        val t = translator().translate("ראש הממשלה נפגש עם השר.\nפסקה שנייה.", "en")
        assertEquals("The Prime Minister met the minister.\nSecond paragraph.", t.text)
        assertEquals("Google says \"iw\" for Hebrew", "he", t.sourceLang)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("en", req.requestUrl!!.queryParameter("tl"))
        val q = req.body.readUtf8().removePrefix("q=")
        assertEquals("ראש הממשלה נפגש עם השר.\nפסקה שנייה.", URLDecoder.decode(q, "UTF-8"))
    }

    @Test
    fun longPostsGoInPartsSplitBetweenParagraphs() {
        val para = "מילה ".repeat(500)
        val parts = Translator.chunks(listOf(para, para, para).joinToString("\n\n"))
        assertEquals(3, parts.size)
        assertTrue(parts.all { it.length <= Translator.MAX_CHUNK })
        assertTrue(Translator.chunks("  ").isEmpty())
    }

    @Test
    fun googlesRequestLimitIsReportedAsThrottling() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://www.google.com/sorry/index"))
        try {
            translator().translate("שלום", "en")
            fail()
        } catch (e: ThrottledException) {
            // expected
        }
    }

    @Test
    fun withAKeyTheOfficialApiIsUsed() {
        server.enqueue(MockResponse().setBody("""{"data":{"translations":[{"translatedText":"Hello","detectedSourceLanguage":"he"}]}}"""))
        val t = translator().translate("שלום", "en", apiKey = "KEY")
        assertEquals("Hello", t.text)
        val req = server.takeRequest()
        assertEquals("/cloud", req.requestUrl!!.encodedPath)
        assertEquals("KEY", req.requestUrl!!.queryParameter("key"))
    }
}

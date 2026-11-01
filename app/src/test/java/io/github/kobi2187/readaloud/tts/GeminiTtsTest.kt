package io.github.kobi2187.readaloud.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class GeminiTtsTest {
    private val server = MockWebServer().apply { start() }
    private var now = 1_800_000_000_000L
    private val tts = GeminiTts(OkHttpClient(), server.url("/"), clock = { now }, minIntervalMs = 0)

    @After
    fun tearDown() = server.shutdown()

    private fun wav(samples: Int): ByteArray = ByteArrayOutputStream().also {
        Wav.write(it, Wav.Format(24_000, 1, 16), listOf(ByteArray(samples * 2) { 1 }))
    }.toByteArray()

    /** The documented response shape: steps -> model_output -> content -> audio (base64). */
    private fun audioResponse(bytes: ByteArray) = MockResponse().setBody(
        """{"id":"x","steps":[{"type":"user_input"},{"type":"model_output","content":[
           {"type":"audio","data":"${Base64.getEncoder().encodeToString(bytes)}"}]}]}""",
    )

    private fun error(code: Int, body: String) = MockResponse().setResponseCode(code).setBody(body)

    @Test
    fun sendsTheDocumentedRequestAndDecodesWav() {
        server.enqueue(audioResponse(wav(2400)))
        val clip = tts.synthesize("KEY", "שלום, hello", "Kore")
        assertEquals(24_000, clip.format.sampleRate)
        assertEquals(4800, clip.pcm.size)

        val request = server.takeRequest()
        assertEquals("/v1beta/interactions", request.path)
        assertEquals("KEY", request.getHeader("x-goog-api-key"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gemini-3.8-flash-tts", body["model"]!!.jsonPrimitive.content)
        val content = body["input"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("שלום, hello", content["text"]!!.jsonPrimitive.content)
        assertEquals("audio", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val voice = body["generation_config"]!!.jsonObject["speech_config"]!!.jsonArray[0].jsonObject["voice"]
        assertEquals("Kore", voice!!.jsonPrimitive.content)
    }

    @Test
    fun acceptsRawPcm() {
        val clip = GeminiTts.parseAudio(
            """{"steps":[{"type":"model_output","content":[{"type":"audio","mime_type":"audio/l16;rate=16000",
               "data":"${Base64.getEncoder().encodeToString(ByteArray(320))}"}]}]}""",
        )
        assertEquals(16_000, clip.format.sampleRate)
        assertEquals(320, clip.pcm.size)
    }

    @Test
    fun badKeyIsReportedAsSuch() {
        server.enqueue(
            error(
                400,
                """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
                   "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}""",
            ),
        )
        try {
            tts.synthesize("BAD", "text", "Kore")
            fail()
        } catch (e: AuthException) {
            // expected
        }
    }

    @Test
    fun otherBadRequestsRejectTheText() {
        server.enqueue(error(400, """{"error":{"code":400,"message":"Unsupported input","status":"INVALID_ARGUMENT"}}"""))
        try {
            tts.synthesize("KEY", "text", "Kore")
            fail()
        } catch (e: RejectedTextException) {
            // expected
        }
    }

    @Test
    fun dailyQuotaMovesToTheNextModelThenGivesUp() {
        val daily = """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
              {"quotaMetric":"generativelanguage.googleapis.com/generate_requests_per_model_per_day",
               "quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"30s"}]}}"""
        server.enqueue(error(429, daily))
        server.enqueue(audioResponse(wav(100)))
        tts.synthesize("KEY", "text", "Kore")
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gemini-3.8-flash-lite-tts", second["model"]!!.jsonPrimitive.content)

        server.enqueue(error(429, daily))
        try {
            tts.synthesize("KEY", "text", "Kore")
            fail()
        } catch (e: ThrottledException) {
            assertTrue(e.quota)
            assertTrue("waits for the daily reset", e.retryAfterSeconds!! > 600)
            assertTrue(tts.allPaused())
        }
        // The next day both models are tried again.
        now += 25 * 3600_000L
        server.enqueue(audioResponse(wav(100)))
        tts.synthesize("KEY", "text", "Kore")
    }

    @Test
    fun perMinuteLimitIsShortPause() {
        val perMinute = """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"20s"}]}}"""
        server.enqueue(error(429, perMinute))
        server.enqueue(error(429, perMinute))
        try {
            tts.synthesize("KEY", "text", "Kore")
            fail()
        } catch (e: ThrottledException) {
            assertTrue(!e.quota)
            assertTrue(e.retryAfterSeconds!! in 15L..30L)
        }
    }

    @Test
    fun shortLimitIsWaitedOutOnTheSameModelWhenAllowed() {
        server.enqueue(error(429, """{"error":{"code":429,"details":[{"retryDelay":"0s"}]}}"""))
        server.enqueue(audioResponse(wav(100)))
        tts.synthesize("KEY", "text", "Kore", maxWaitMs = 5_000)
        server.takeRequest()
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("gemini-3.8-flash-tts", second["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun longPostsAreSplitBetweenSentences() {
        assertEquals(listOf("short"), GeminiTts.chunks("  short "))
        assertTrue(GeminiTts.chunks(" \n ").isEmpty())
        val sentence = "זה משפט באורך בינוני שחוזר על עצמו. "
        val text = sentence.repeat(200)
        val chunks = GeminiTts.chunks(text, max = 500)
        assertTrue(chunks.all { it.length <= 500 })
        assertTrue(chunks.all { it.endsWith(".") })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(GeminiTts.chunks("x".repeat(1200), max = 500).all { it.length <= 500 })
    }
}

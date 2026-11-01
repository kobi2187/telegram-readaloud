package io.github.kobi2187.readaloud.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/** Downloading the offline voice over a flaky connection. */
@RunWith(AndroidJUnit4::class)
class OfflineHebrewVoiceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val blobs = mapOf("/a.onnx" to Random(1).nextBytes(300_000), "/b.json" to Random(2).nextBytes(5_000))
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var honourRanges = true
    private var corrupt = false

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val files get() = listOf(
        OfflineHebrewVoice.ModelFile("b.json", server.url("/b.json").toString(), 5_000, sha(blobs.getValue("/b.json"))),
        OfflineHebrewVoice.ModelFile("a.onnx", server.url("/a.onnx").toString(), 300_000, sha(blobs.getValue("/a.onnx"))),
    )

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val blob = blobs[request.path] ?: return MockResponse().setResponseCode(404)
                val data = if (corrupt) blob.reversedArray() else blob
                val range = request.getHeader("Range")
                ranges += range
                val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
                return if (start != null && honourRanges) {
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-${data.size - 1}/${data.size}")
                        .setBody(Buffer().write(data, start, data.size - start))
                } else {
                    MockResponse().setBody(Buffer().write(data))
                }
            }
        }
        server.start()
        voice().remove()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun voice() = OfflineHebrewVoice(context, OkHttpClient(), files)

    @Test
    fun downloadsVerifiesAndInstalls() {
        val v = voice()
        assertFalse(v.installed)
        var last = 0L to 0L
        v.download { done, total -> last = done to total }
        assertTrue(v.installed)
        assertTrue(v.installedFlow.value)
        assertEquals(305_000L to 305_000L, last)
        assertArrayEquals(blobs["/a.onnx"], File(v.dir, "a.onnx").readBytes())
        assertTrue("a fresh instance sees the installed files", voice().installed)

        v.remove()
        assertFalse(v.installed)
        assertFalse(File(v.dir, "a.onnx").exists())
    }

    @Test
    fun resumesAPartialDownload() {
        val v = voice()
        v.dir.mkdirs()
        File(v.dir, "a.onnx.part").writeBytes(blobs.getValue("/a.onnx").copyOf(100_000))
        assertEquals(100_000L, v.downloadedBytes())
        v.download()
        assertTrue(ranges.contains("bytes=100000-"))
        assertArrayEquals(blobs["/a.onnx"], File(v.dir, "a.onnx").readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresRanges() {
        honourRanges = false
        val v = voice()
        v.dir.mkdirs()
        File(v.dir, "a.onnx.part").writeBytes(blobs.getValue("/a.onnx").copyOf(100_000))
        v.download()
        assertArrayEquals(blobs["/a.onnx"], File(v.dir, "a.onnx").readBytes())
    }

    @Test
    fun corruptDownloadIsDiscarded() {
        corrupt = true
        val v = voice()
        try {
            v.download()
            fail()
        } catch (e: OfflineHebrewVoice.ChecksumException) {
            // expected
        }
        assertFalse(v.installed)
        assertFalse(File(v.dir, "b.json").exists())
        assertFalse(File(v.dir, "b.json.part").exists())
    }

    @Test
    fun stoppingKeepsWhatWasDownloaded() {
        val v = voice()
        var calls = 0
        try {
            v.download(stopped = { ++calls > 3 })
            fail()
        } catch (e: InterruptedIOException) {
            // expected
        }
        assertFalse(v.installed)
        assertTrue(v.downloadedBytes() > 0)
        v.download()
        assertTrue(v.installed)
    }
}

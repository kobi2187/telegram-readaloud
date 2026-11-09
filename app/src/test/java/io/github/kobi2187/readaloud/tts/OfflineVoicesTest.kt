package io.github.kobi2187.readaloud.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.voice.ModelFile
import io.github.kobi2187.readaloud.voice.VoiceModel
import io.github.kobi2187.readaloud.voice.VoiceCatalog
import io.github.kobi2187.readaloud.voice.VoicePack
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Downloading, switching and removing offline voices over a flaky connection. */
@RunWith(AndroidJUnit4::class)
class OfflineVoicesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val server = MockWebServer()
    private val blobs = mapOf(
        "/shared.onnx" to Random(1).nextBytes(200_000),
        "/a.onnx" to Random(2).nextBytes(300_000),
        "/b.onnx" to Random(3).nextBytes(100_000),
    )
    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private var honourRanges = true
    private var corrupt = false
    private var unsupported = emptySet<String>()

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun file(path: String, name: String = path.removePrefix("/")) =
        ModelFile(name, server.url(path).toString(), blobs.getValue(path).size.toLong(), sha(blobs.getValue(path)))

    private val catalog get() = listOf(
        VoicePack("a", "A", male = false, heavy = false, license = "test", files = listOf(file("/shared.onnx", "renikud.onnx"), file("/a.onnx"))) {
            VoiceModel.Piper(File(it, "a.onnx"), File(it, "a.json"))
        },
        VoicePack("b", "B", male = true, heavy = true, license = "test", files = listOf(file("/shared.onnx", "renikud.onnx"), file("/b.onnx", "sub/b.onnx"))) {
            VoiceModel.Piper(File(it, "b.onnx"), File(it, "b.json"))
        },
    )

    private val opened: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private var free = Long.MAX_VALUE

    /** Each load: the voice and whether it was asked for Phonikud; each synthesis: whether stress was on. */
    private val loads: MutableList<Pair<String, Boolean>> = Collections.synchronizedList(mutableListOf())
    private val stresses: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

    private fun voices(
        catalog: List<VoicePack> = this.catalog,
        wanted: () -> Pronunciation = { Pronunciation() },
    ) = OfflineVoices(context, OkHttpClient(), catalog, freeBytes = { free }, wanted = wanted, addonTester = { _, _ -> }, opener = { _, pack, phonikud ->
        if (pack.id in unsupported) throw UnsatisfiedLinkError("no native library")
        opened += pack.id
        loads += pack.id to phonikud
        object : LoadedVoice {
            override val sampleRate = 22_050
            override var stress = true
            override fun synthesize(text: String, emit: (ShortArray) -> Unit) {
                stresses += stress
                emit(ShortArray(100) { 1 })
            }
            override fun close() = Unit
        }
    })

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val blob = blobs[request.path] ?: return MockResponse().setResponseCode(404)
                requests += request.path!!
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
        File(context.filesDir, "models").deleteRecursively()
        context.getSharedPreferences("offline_voices", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun sharedFilesAreDownloadedOnceAndTheNewVoiceBecomesActive() {
        val v = voices()
        assertNull(v.activeId)
        var last = 0L to 0L
        v.download("a") { done, total -> last = done to total }
        assertEquals(500_000L to 500_000L, last)
        assertEquals(setOf("a"), v.state.value.installed)
        assertEquals("a", v.activeId)
        assertEquals("the phonemizer is already there", 100_000L, v.remainingBytes("b"))

        requests.clear()
        v.download("b")
        assertEquals(listOf("/b.onnx"), requests)
        assertArrayEquals(blobs["/b.onnx"], File(v.dir, "sub/b.onnx").readBytes())
        assertEquals("b", v.activeId)
        assertTrue("each voice was test-spoken", opened.containsAll(listOf("a", "b")))

        v.select("a")
        assertEquals("a", voices().activeId)

        v.remove("a")
        assertTrue("B still needs the shared file", File(v.dir, "renikud.onnx").exists())
        assertFalse(File(v.dir, "a.onnx").exists())
        assertEquals("b", v.activeId)
        v.remove("b")
        assertNull(v.activeId)
        assertFalse(File(v.dir, "renikud.onnx").exists())
    }

    @Test
    fun synthesisUsesTheActiveOrRequestedVoice() {
        val v = voices()
        assertNull(v.synthesize("שלום"))
        v.download("a")
        v.download("b")
        opened.clear()
        assertEquals(22_050, v.synthesize("שלום")!!.sampleRate)
        assertEquals(listOf("b"), opened)
        v.synthesize("שלום", "a")
        v.synthesize("שלום", "a")
        assertEquals("loaded once, swapped on demand", listOf("b", "a"), opened)
    }

    @Test
    fun resumesAPartialDownload() {
        val v = voices()
        v.dir.mkdirs()
        File(v.dir, "a.onnx.part").writeBytes(blobs.getValue("/a.onnx").copyOf(100_000))
        assertEquals(400_000L, v.remainingBytes("a"))
        v.download("a")
        assertTrue(ranges.contains("bytes=100000-"))
        assertArrayEquals(blobs["/a.onnx"], File(v.dir, "a.onnx").readBytes())
    }

    @Test
    fun startsOverWhenTheServerIgnoresRanges() {
        honourRanges = false
        val v = voices()
        v.dir.mkdirs()
        File(v.dir, "a.onnx.part").writeBytes(blobs.getValue("/a.onnx").copyOf(100_000))
        v.download("a")
        assertArrayEquals(blobs["/a.onnx"], File(v.dir, "a.onnx").readBytes())
    }

    @Test
    fun corruptDownloadIsDiscarded() {
        corrupt = true
        val v = voices()
        try {
            v.download("a")
            fail()
        } catch (e: OfflineVoices.ChecksumException) {
            // expected
        }
        assertTrue(v.state.value.installed.isEmpty())
        assertFalse(File(v.dir, "renikud.onnx.part").exists())
    }

    @Test
    fun aVoiceThatCannotRunHereIsNotInstalled() {
        unsupported = setOf("b")
        val v = voices()
        v.download("a")
        try {
            v.download("b")
            fail()
        } catch (e: OfflineVoices.UnsupportedVoiceException) {
            // expected
        }
        assertEquals(setOf("a"), v.state.value.installed)
        assertEquals("a", v.activeId)
        assertFalse(File(v.dir, "sub/b.onnx").exists())
        assertTrue(File(v.dir, "renikud.onnx").exists())
    }

    @Test
    fun notEnoughSpaceFailsBeforeDownloading() {
        free = 10_000_000
        try {
            voices().download("a")
            fail()
        } catch (e: OfflineVoices.NotEnoughSpaceException) {
            assertEquals(500_000L + OfflineVoices.SPACE_MARGIN, e.neededBytes)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun stoppingKeepsWhatWasDownloaded() {
        val v = voices()
        var calls = 0
        try {
            v.download("a", stopped = { ++calls > 3 })
            fail()
        } catch (e: InterruptedIOException) {
            // expected
        }
        assertTrue(v.remainingBytes("a") < 500_000L)
        v.download("a")
        assertEquals("a", v.activeId)
    }

    @Test
    fun theVersion11VoiceFolderIsKept() {
        val old = File(context.filesDir, "models/he-noa").apply { mkdirs() }
        File(old, "renikud.onnx").writeBytes(blobs.getValue("/shared.onnx"))
        File(old, "a.onnx").writeBytes(blobs.getValue("/a.onnx"))
        val v = voices()
        assertEquals(setOf("a"), v.state.value.installed)
        assertEquals("a", v.activeId)
        assertFalse(old.exists())
    }

    @Test
    fun thePhonikudAddOnIsUsedOnceInstalledAndNeverBecomesTheVoice() {
        var wanted = Pronunciation(phonikud = true, stress = false)
        val addon = VoicePack(
            VoiceCatalog.PHONIKUD_ID, "Phonikud", male = false, heavy = false, license = "test", isVoice = false,
            files = listOf(file("/b.onnx", "phonikud.onnx")),
        ) { throw UnsupportedOperationException("not a voice") }
        val v = voices(catalog + addon) { wanted }
        v.download("a")
        assertNotNull(v.synthesize("שלום"))
        assertEquals("a" to false, loads.last())
        assertEquals(false, stresses.last())
        assertEquals("|nostress", v.variant)

        v.download(VoiceCatalog.PHONIKUD_ID)
        assertEquals("an add-on is not a voice", "a", v.activeId)
        v.select(VoiceCatalog.PHONIKUD_ID)
        assertEquals("a", v.activeId)
        v.synthesize("שלום")
        assertEquals("the voice is loaded again with Phonikud", "a" to true, loads.last())
        assertEquals("|phonikud|nostress", v.variant)

        wanted = Pronunciation()
        assertEquals("", v.variant)
        v.synthesize("שלום")
        assertEquals(true, stresses.last())
    }
}

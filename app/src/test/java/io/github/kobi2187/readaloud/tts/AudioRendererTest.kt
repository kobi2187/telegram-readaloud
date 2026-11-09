package io.github.kobi2187.readaloud.tts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.data.AppDatabase
import io.github.kobi2187.readaloud.data.AppSettings
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.CloudVoice
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.text.Lang
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.Collections

/** The engine chain: online voice first, then the offline Hebrew voice and the device engine. */
@RunWith(AndroidJUnit4::class)
class AudioRendererTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer()
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var renderer: AudioRenderer
    private var online = false
    private val device = FakeDevice(context)
    private val hebrew = FakeOfflineHebrew()
    private var compress = false

    /** Stands in for the phone's AAC encoder (not available on the JVM). */
    private class FakeEncoder(private val out: java.io.OutputStream) : PcmEncoder {
        override val extension = "aac"
        var samples = 0
        override fun encode(samples: ShortArray) { this.samples += samples.size; out.write(ByteArray(samples.size / 16)) }
        override fun finish() = out.flush()
    }

    private class FakeDevice(context: Context) : SystemTts(context) {
        val spoken: MutableList<Pair<Lang, String>> = Collections.synchronizedList(mutableListOf())
        override fun isLanguageAvailable(lang: Lang) = true
        override fun synthesize(text: String, lang: Lang, scratchDir: File, timeoutMs: Long): Wav.Clip {
            spoken += lang to text
            val format = Wav.Format(16_000, 1, 16)
            return Wav.Clip(format, Wav.silence(format, 400))
        }
    }

    private class FakeOfflineHebrew : OfflineHebrew {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override var activeId: String? = "noa"
        override fun synthesizeStreaming(text: String, voiceId: String?, emit: (Pcm) -> Unit): Boolean {
            if (voiceId == null) return false
            spoken += text
            emit(Pcm(ShortArray(22_050 / 2) { 1000 }, 22_050))
            return true
        }
    }

    @Before
    fun setUp() {
        server.start()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context, scope)
        val network = object : NetworkMonitor(context) {
            override fun isOnline(allowMetered: Boolean) = online
        }
        val gemini = GeminiTts(OkHttpClient(), server.url("/"), minIntervalMs = 0)
        renderer = AudioRenderer(context, db.messages(), settings, gemini, AzureTts(OkHttpClient()), device, network, hebrew, encoders = { _, out ->
            if (compress) FakeEncoder(out) else null
        })
        runBlocking {
            db.messages().insertAll(
                listOf(
                    MessageEntity("news", 1, 1000, "", "שלום לכולם. This is the English part."),
                    MessageEntity("news", 2, 2000, "", "עוד הודעה בעברית."),
                    MessageEntity("news", 3, 3000, "", "Only English here."),
                ),
            )
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
        scope.cancel()
    }

    private fun configure(cfg: AppSettings) = runBlocking {
        settings.update { cfg }
        val deadline = System.currentTimeMillis() + 5_000
        while (settings.state.value != cfg && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(cfg, settings.state.value)
    }

    /** Phone renders stream in the background; wait for the finished file. */
    private fun rendered(channel: String, id: Int): File = renderer.awaitRendered(renderer.ensureMessageAudio(channel, id))

    private fun message(id: Int) = db.messages().getBlocking("news", id)!!

    private fun wavResponse(): MockResponse {
        val format = Wav.Format(24_000, 1, 16)
        val bytes = ByteArrayOutputStream().also { Wav.write(it, format, listOf(Wav.silence(format, 300))) }.toByteArray()
        return MockResponse().setBody(
            """{"steps":[{"type":"model_output","content":[{"type":"audio","data":"${Base64.getEncoder().encodeToString(bytes)}"}]}]}""",
        )
    }

    private val dailyQuota = MockResponse().setResponseCode(429).setBody(
        """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{"@type":"type.googleapis.com/google.rpc.QuotaFailure",
           "violations":[{"quotaId":"GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]}]}}""",
    )

    @Test
    fun offlineHebrewVoiceForHebrewAndDeviceForEnglish() {
        configure(AppSettings(cloudVoice = CloudVoice.NONE))
        val file = rendered("news", 1)

        assertEquals(listOf("שלום לכולם."), hebrew.spoken)
        assertEquals(listOf(Lang.EN to "This is the English part."), device.spoken)
        val clip = Wav.read(file)
        assertEquals("mixed rates are joined at the higher one", 22_050, clip.format.sampleRate)
        val ms = clip.pcm.size / 2 * 1000L / 22_050
        assertTrue("Hebrew 500 + pause 250 + English 400 + gap 700, was $ms", ms in 1840L..1860L)
        val m = message(1)
        assertEquals(AudioState.LOCAL, m.audioState)
        assertEquals("local|noa", m.audioVoiceKey)
    }

    @Test
    fun deviceVoicedHebrewIsRedoneOnceTheOfflineVoiceIsInstalled() {
        configure(AppSettings(cloudVoice = CloudVoice.NONE))
        hebrew.activeId = null
        val first = rendered("news", 2)
        assertEquals(listOf(Lang.HE to "עוד הודעה בעברית."), device.spoken)
        assertEquals(AudioRenderer.LOCAL_DEVICE, message(2).audioVoiceKey)

        hebrew.activeId = "noa"
        val second = rendered("news", 2)
        assertEquals(listOf("עוד הודעה בעברית."), hebrew.spoken)
        assertEquals("local|noa", message(2).audioVoiceKey)
        assertEquals(first.path, second.path)

        rendered("news", 2)
        assertEquals("rendered once, then reused", 1, hebrew.spoken.size)

        // Choosing another offline voice re-voices upcoming Hebrew; removing them all keeps the file.
        hebrew.activeId = "shaul"
        rendered("news", 2)
        assertEquals(2, hebrew.spoken.size)
        assertEquals("local|shaul", message(2).audioVoiceKey)
        hebrew.activeId = null
        rendered("news", 2)
        assertEquals("local|shaul", message(2).audioVoiceKey)

        // English-only posts never need the Hebrew voice.
        rendered("news", 3)
        rendered("news", 3)
        assertEquals(1, device.spoken.count { it.second == "Only English here." })
    }

    @Test
    fun geminiAudioIsDownloadedWithThePost() {
        online = true
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "KEY", geminiVoice = "Kore"))
        server.enqueue(wavResponse())
        val file = rendered("news", 1)

        assertTrue(file.name, file.name.endsWith(".gemini.wav"))
        assertEquals(24_000, Wav.read(file).format.sampleRate)
        val m = message(1)
        assertEquals(AudioState.NEURAL, m.audioState)
        assertEquals("gemini|Kore", m.audioVoiceKey)
        assertTrue(hebrew.spoken.isEmpty() && device.spoken.isEmpty())
        assertEquals(1, server.requestCount)

        // Channel names and notices are voiced on the phone, saving the small free quota for posts.
        renderer.ensureClip("ch_news", "חדשות")
        assertEquals(1, server.requestCount)
        assertEquals(listOf("חדשות"), hebrew.spoken)

        // A different voice makes the post due for download again.
        assertFalse(renderer.needsNeural(m, settings.state.value))
        assertTrue(renderer.needsNeural(m, settings.state.value.copy(geminiVoice = "Puck")))
    }

    @Test
    fun usedUpQuotaFallsBackToOfflineVoicesUntilReset() {
        online = true
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "KEY"))
        server.enqueue(dailyQuota)
        server.enqueue(dailyQuota) // the second model's quota too
        val file = rendered("news", 2)

        assertTrue(file.name, file.name == "2.wav")
        assertEquals(listOf("עוד הודעה בעברית."), hebrew.spoken)
        assertEquals(CloudHealth(CloudVoice.GEMINI, CloudStatus.QUOTA), renderer.cloudHealth.value)
        assertFalse(renderer.cloudReady(settings.state.value))
        assertEquals(AudioState.LOCAL, message(2).audioState)

        rendered("news", 1)
        assertEquals("no more requests until the quota resets", 2, server.requestCount)
        assertEquals(AudioRenderer.Prefetch.CLOUD_UNAVAILABLE, renderer.prefetchNeural(message(3), settings.state.value))
    }

    @Test
    fun rejectedKeyIsNotRetriedUntilChanged() {
        online = true
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "BAD"))
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error":{"code":400,"message":"API key not valid.","details":[{"reason":"API_KEY_INVALID"}]}}""",
            ),
        )
        rendered("news", 2)
        assertEquals(CloudHealth(CloudVoice.GEMINI, CloudStatus.BAD_KEY), renderer.cloudHealth.value)
        assertFalse(renderer.cloudReady(settings.state.value))
        assertTrue(renderer.cloudReady(settings.state.value.copy(geminiKey = "NEW")))
    }

    @Test
    fun syncReplacesPhoneAudioWithTheOnlineVoice() {
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "KEY"))
        val local = rendered("news", 3) // offline: rendered on the phone
        assertEquals(AudioState.LOCAL, message(3).audioState)

        online = true
        server.enqueue(wavResponse())
        assertTrue(renderer.needsNeural(message(3), settings.state.value))
        assertEquals(AudioRenderer.Prefetch.DONE, renderer.prefetchNeural(message(3), settings.state.value))
        val m = message(3)
        assertEquals(AudioState.NEURAL, m.audioState)
        assertTrue(m.audioPath!!.endsWith("3.gemini.wav"))
        assertFalse("the phone-rendered file is deleted", local.exists())
    }

    @Test
    fun textGeminiCannotVoiceIsLeftToThePhone() {
        online = true
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "KEY"))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"code":400,"message":"Unsupported"}}"""))
        rendered("news", 3)
        val m = message(3)
        assertEquals(AudioState.NEURAL_FAILED, m.audioState)
        assertFalse(renderer.needsNeural(m, settings.state.value))
        assertTrue(renderer.cloudReady(settings.state.value))
    }

    @Test
    fun audioIsStoredCompressedWhenThePhoneCanEncode() {
        compress = true
        configure(AppSettings(cloudVoice = CloudVoice.NONE))
        val local = rendered("news", 2)
        assertEquals("2.aac", local.name)
        assertEquals(local.path, message(2).audioPath)
        assertTrue("an eighth of the WAV size", local.length() in 1000L..3000L)

        online = true
        configure(AppSettings(cloudVoice = CloudVoice.GEMINI, geminiKey = "KEY"))
        server.enqueue(wavResponse())
        val cloud = rendered("news", 3)
        assertEquals("3.gemini.aac", cloud.name)
    }
}

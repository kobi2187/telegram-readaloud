package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the real models (set VOICE_MODELS_DIR). The expected IPA was produced by the reference
 * Python package renikud-onnx 0.1.1 with the same model file, so this checks the port exactly.
 */
class HebrewNeuralVoiceTest {
    private lateinit var dir: File

    @Before
    fun models() {
        dir = File(System.getProperty("voice.models").orEmpty())
        assumeTrue("set VOICE_MODELS_DIR", File(dir, VoiceCatalog.G2P_FILE).exists())
    }

    private val reference = listOf(
        "ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים." to
            "ʁˈoʃ hamemʃalˈa nifɡˈaʃ habokˈeʁ ʔˈim sˈaʁ habitaχˈon bjeʁuʃalˈajim.",
        "התחזית: גשם מקומי בצפון ובמרכז, ירידה בטמפרטורות." to
            "hataχazˈet: ɡˈeʃem mekomˈi batsafˈon vebameʁkˈaz, jeʁidˈa batempeʁatˈuʁot.",
        "הנשיא טראמפ פרסם הודעה על תמיכתו בהסכם החדש." to
            "hanasˈi tʁˈamp piʁsˈem hodaʔˈa ʔˈal tmiχatˈo baheskˈem haχadˈaʃ.",
        "צה\"ל תקף הלילה מטרות בדרום לבנון, דיווחו בארה\"ב." to
            "tsahal takˈaf halˈajla mataʁˈot bidʁˈom levanˈon, divχˈu beʔaʁhav.",
        "ג'ורג' נסע לצ'ילה בשבוע שעבר עם Google ו-Apple." to
            "dʒˈoʁdʒe nasˈa letʃilˈa beʃavˈua ʃeʔavˈaʁ ʔˈim Google ve-Apple.",
        "מחר בבוקר יתקיים דיון חשוב בכנסת על התקציב." to
            "maχˈaʁ babˈokeʁ jitkajˈem dijˈun χaʃˈuv baknˈeset ʔˈal hataktsˈiv.",
    )

    @Test
    fun g2pMatchesTheReferenceImplementation() {
        HebrewG2p(OrtEnvironment.getEnvironment(), File(dir, VoiceCatalog.G2P_FILE).path).use { g2p ->
            for ((text, ipa) in reference) assertEquals(text, ipa, g2p.phonemize(text))
        }
    }

    @Test
    fun speaksPostsWithNumbers() {
        val noa = VoiceCatalog.byId("noa")!!
        assumeTrue("Noa files missing", VoiceCatalog.isInstalled(dir, noa))
        VoiceCatalog.open(dir, noa).use { voice ->
            val posts = listOf(
                "ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.",
                "חצי שעה לסגירת הקלפיות, ושיעור ההצבעה עומד על 62%.",
                "הבורסה עלתה ב-3.5% ביום שלישי. הדולר ירד ל-3.62 שקלים.\n\nפרטים נוספים בהמשך.",
            )
            posts.forEachIndexed { i, text ->
                val pcm = timed("noa post $i", voice, text)
                assertTrue("plausible length for: $text", pcm.size.toDouble() / voice.sampleRate in text.length / 25.0..text.length / 6.0)
                writeWav(File(out, "kotlin_$i.wav"), pcm, voice.sampleRate)
            }
        }
    }

    /** Every catalog voice whose files are present speaks the same sentences (samples for ASR). */
    @Test
    fun everyCatalogVoiceSpeaks() {
        val sentences = listOf(
            "ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.",
            "התחזית: גשם מקומי בצפון ובמרכז, ירידה בטמפרטורות.",
        )
        var tested = 0
        for (pack in VoiceCatalog.voices) {
            if (!VoiceCatalog.isInstalled(dir, pack)) continue
            VoiceCatalog.open(dir, pack).use { voice ->
                sentences.forEachIndexed { i, text ->
                    val pcm = timed(pack.id, voice, text)
                    val seconds = pcm.size.toDouble() / voice.sampleRate
                    assertTrue("${pack.id}: plausible length ($seconds s)", seconds in text.length / 25.0..text.length / 6.0)
                    writeWav(File(out, "${pack.id}_$i.wav"), pcm, voice.sampleRate)
                }
            }
            tested++
        }
        println("tested $tested voices")
    }

    private val out get() = File(System.getProperty("voice.out") ?: "build/voice-samples").apply { mkdirs() }

    private fun timed(label: String, voice: HebrewNeuralVoice, text: String): ShortArray {
        val start = System.nanoTime()
        val pcm = voice.synthesize(text)
        val seconds = pcm.size.toDouble() / voice.sampleRate
        println("$label: ${"%.1f".format(seconds)} s audio, RTF ${"%.3f".format((System.nanoTime() - start) / 1e9 / seconds)}")
        return pcm
    }

    private fun writeWav(file: File, pcm: ShortArray, rate: Int) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16); put("data".toByteArray()); putInt(pcm.size * 2)
        }
        file.writeBytes(header.array() + data.array())
    }
}

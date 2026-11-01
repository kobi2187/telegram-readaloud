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
        assumeTrue("set VOICE_MODELS_DIR", HebrewNeuralVoice.isInstalled(dir))
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
        HebrewG2p(OrtEnvironment.getEnvironment(), File(dir, HebrewNeuralVoice.G2P_FILE).path).use { g2p ->
            for ((text, ipa) in reference) assertEquals(text, ipa, g2p.phonemize(text))
        }
    }

    @Test
    fun speaksPostsWithNumbers() {
        val out = File(System.getProperty("voice.out") ?: "build/voice-samples").apply { mkdirs() }
        HebrewNeuralVoice(dir).use { voice ->
            val posts = listOf(
                "ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים.",
                "חצי שעה לסגירת הקלפיות, ושיעור ההצבעה עומד על 62%.",
                "הבורסה עלתה ב-3.5% ביום שלישי. הדולר ירד ל-3.62 שקלים.\n\nפרטים נוספים בהמשך.",
            )
            posts.forEachIndexed { i, text ->
                val start = System.nanoTime()
                val pcm = voice.synthesize(text)
                val seconds = pcm.size.toDouble() / voice.sampleRate
                val rtf = (System.nanoTime() - start) / 1e9 / seconds
                println("post $i: ${"%.1f".format(seconds)} s audio, RTF ${"%.3f".format(rtf)}\n${voice.lastIpa}")
                assertTrue("plausible length for: $text", seconds in text.length / 25.0..text.length / 6.0)
                writeWav(File(out, "kotlin_$i.wav"), pcm, voice.sampleRate)
            }
        }
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

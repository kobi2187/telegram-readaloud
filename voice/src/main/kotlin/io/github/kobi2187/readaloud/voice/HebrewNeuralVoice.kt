package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import java.io.File

/**
 * Offline neural Hebrew speech: Hebrew text -> spelled-out numbers -> renikud IPA -> Piper "noa"
 * voice. Both models are CC BY 4.0 (renikud by thewh1teagle et al.; noa by thewh1teagle).
 */
class HebrewNeuralVoice(modelDir: File) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val g2p = HebrewG2p(env, File(modelDir, G2P_FILE).path)
    private val voice = PiperVoice(env, File(modelDir, VOICE_FILE).path, File(modelDir, CONFIG_FILE).readText())
    private val speakerId = voice.speakers[SPEAKER] ?: 0

    val sampleRate: Int get() = voice.sampleRate

    /** Last IPA produced, for diagnostics and tests. */
    @Volatile
    var lastIpa: String = ""
        private set

    /** Renders [text] as 16-bit mono PCM at [sampleRate], with natural pauses between sentences. */
    @Synchronized
    fun synthesize(text: String, speed: Float = 1f): ShortArray {
        val sentences = HebrewText.sentences(HebrewText.normalize(text))
        val parts = ArrayList<ShortArray>()
        val ipaLog = StringBuilder()
        for ((sentence, paragraphEnd) in sentences) {
            val ipa = g2p.phonemize(sentence)
            ipaLog.append(ipa).append('\n')
            val pcm = voice.synthesize(ipa, speakerId, speed)
            if (pcm.isEmpty()) continue
            parts += pcm
            parts += ShortArray(sampleRate * (if (paragraphEnd) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS) / 1000)
        }
        lastIpa = ipaLog.toString().trim()
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    override fun close() {
        g2p.close()
        voice.close()
    }

    companion object {
        const val G2P_FILE = "renikud.onnx"
        const val VOICE_FILE = "he_IL-noa-medium.onnx"
        const val CONFIG_FILE = "he_IL-noa-medium.onnx.json"
        private const val SPEAKER = "noa"
        private const val SENTENCE_PAUSE_MS = 250
        private const val PARAGRAPH_PAUSE_MS = 450

        fun isInstalled(modelDir: File) =
            listOf(G2P_FILE, VOICE_FILE, CONFIG_FILE).all { File(modelDir, it).length() > 0 }
    }
}

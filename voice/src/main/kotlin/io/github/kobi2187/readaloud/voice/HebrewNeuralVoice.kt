package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import java.io.File

/**
 * Offline neural Hebrew speech: Hebrew text -> spelled-out numbers -> renikud IPA -> a neural
 * voice ([VoiceModel]). renikud is CC BY 4.0 (thewh1teagle et al.); each voice has its own license.
 */
class HebrewNeuralVoice(g2pFile: File, model: VoiceModel) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val g2p = HebrewG2p(env, g2pFile.path)
    private val engine: IpaEngine = try {
        model.open(env)
    } catch (e: Throwable) {
        g2p.close()
        throw e
    }

    val sampleRate: Int get() = engine.sampleRate

    /** Last IPA produced, for diagnostics and tests. */
    @Volatile
    var lastIpa: String = ""
        private set

    /** Renders [text] as 16-bit mono PCM at [sampleRate], with natural pauses between sentences. */
    @Synchronized
    fun synthesize(text: String): ShortArray {
        val sentences = HebrewText.sentences(HebrewText.normalize(text))
        val parts = ArrayList<ShortArray>()
        val ipaLog = StringBuilder()
        for ((sentence, paragraphEnd) in sentences) {
            val ipa = g2p.phonemize(sentence)
            ipaLog.append(ipa).append('\n')
            val pcm = engine.speak(ipa)
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
        engine.close()
    }

    companion object {
        private const val SENTENCE_PAUSE_MS = 250
        private const val PARAGRAPH_PAUSE_MS = 450
    }
}

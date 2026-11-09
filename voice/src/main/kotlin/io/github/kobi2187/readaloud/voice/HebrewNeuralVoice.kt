package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import java.io.File

/**
 * Offline neural Hebrew speech: Hebrew text -> spelled-out numbers -> IPA (renikud, or the Phonikud
 * pipeline) -> a neural voice ([VoiceModel]). renikud and Phonikud are by thewh1teagle et al.; each
 * voice has its own license.
 */
class HebrewNeuralVoice(g2pFile: File, model: VoiceModel, phonikud: Pair<File, File>? = null) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val g2p: HebrewPhonemizer =
        if (phonikud != null) PhonikudG2p(env, phonikud.first, phonikud.second) else HebrewG2p(env, g2pFile.path)

    /** Mark stressed syllables in the IPA; off leaves them out (to hear the voice without them). */
    @Volatile
    var stress: Boolean = true
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
    fun synthesize(text: String): ShortArray {
        val parts = ArrayList<ShortArray>()
        synthesize(text) { parts += it }
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /**
     * Renders [text] sentence by sentence, handing each sentence (with its following pause) to
     * [emit] as soon as it is ready, so playback can start before the whole text is done.
     * [emit] may throw to stop early.
     */
    fun synthesize(text: String, emit: (ShortArray) -> Unit) {
        val sentences = HebrewText.sentences(HebrewText.normalize(text))
        val ipaLog = StringBuilder()
        try {
            for ((sentence, paragraphEnd) in sentences) {
                // One sentence at a time holds the models, so two texts can render interleaved.
                val pcm = synchronized(this) {
                    val ipa = g2p.phonemize(sentence).let { if (stress) it else it.replace("ˈ", "") }
                    ipaLog.append(ipa).append('\n')
                    engine.speak(ipa)
                }
                if (pcm.isEmpty()) continue
                val pause = ShortArray(sampleRate * (if (paragraphEnd) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS) / 1000)
                emit(pcm + pause)
            }
        } finally {
            lastIpa = ipaLog.toString().trim()
        }
    }

    @Synchronized
    override fun close() {
        g2p.close()
        engine.close()
    }

    companion object {
        private const val SENTENCE_PAUSE_MS = 250
        private const val PARAGRAPH_PAUSE_MS = 450
    }
}

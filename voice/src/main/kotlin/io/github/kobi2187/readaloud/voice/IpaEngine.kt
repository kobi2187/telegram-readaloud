package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OrtEnvironment
import java.io.File

/** A voice that speaks stressed Hebrew IPA (the renikud/Phonikud convention). */
interface IpaEngine : AutoCloseable {
    val sampleRate: Int

    /** 16-bit mono speech at [sampleRate], peak-normalized; empty if nothing could be spoken. */
    fun speak(ipa: String): ShortArray
}

/** Which model files make up a voice; every engine shares the renikud phonemizer. */
sealed interface VoiceModel {
    fun open(env: OrtEnvironment): IpaEngine

    /** A Piper (VITS) voice; [speaker] names one voice of a multi-speaker model. */
    data class Piper(val model: File, val config: File, val speaker: String? = null) : VoiceModel {
        override fun open(env: OrtEnvironment): IpaEngine {
            val voice = PiperVoice(env, model.path, config.readText())
            val id = speaker?.let { voice.speakers[it] } ?: 0
            return object : IpaEngine {
                override val sampleRate = voice.sampleRate
                override fun speak(ipa: String) = voice.synthesize(ipa, id)
                override fun close() = voice.close()
            }
        }
    }

    /** A Kokoro model with its style table ([voices] `.npz`) and phoneme vocabulary ([config]). */
    data class Kokoro(val model: File, val voices: File, val config: File, val voice: String) : VoiceModel {
        override fun open(env: OrtEnvironment): IpaEngine = KokoroVoice(env, model, voices, config, voice)
    }

    /** A BlueTTS bundle directory and one of its voice style files. */
    data class Blue(val dir: File, val style: File) : VoiceModel {
        override fun open(env: OrtEnvironment): IpaEngine = BlueVoice(env, dir, style)
    }
}

internal fun peakNormalize(audio: FloatArray, peak: Float = 0.95f): ShortArray {
    var max = 0f
    for (v in audio) if (v.isFinite()) max = maxOf(max, kotlin.math.abs(v))
    val gain = if (max > 1e-8f) peak / max else 0f
    return ShortArray(audio.size) { k ->
        val v = audio[k].takeIf { it.isFinite() } ?: 0f
        ((v * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
    }
}

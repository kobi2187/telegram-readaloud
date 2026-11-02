package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * A Kokoro (StyleTTS2-style) voice driven by IPA, as kokoro-onnx runs it: token ids padded with
 * 0 on both ends, and a style vector chosen by the number of phonemes.
 */
class KokoroVoice(
    private val env: OrtEnvironment,
    model: File,
    voices: File,
    config: File,
    voice: String,
) : IpaEngine {
    private val session: OrtSession = env.createSession(model.path, OrtSession.SessionOptions())
    private val vocab: Map<String, Long> = Json.parseToJsonElement(config.readText()).jsonObject
        .getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int.toLong() }
    private val styles: NpyArray = Npy.readArchive(voices)[voice] ?: error("No Kokoro voice $voice")
    private val styleDim = styles.shape.last()
    private val styleRows = styles.data.size / styleDim
    private val tokensInput = if ("input_ids" in session.inputNames) "input_ids" else "tokens"

    override val sampleRate = 24_000

    override fun speak(ipa: String): ShortArray {
        val tokens = ArrayList<Long>()
        var i = 0
        while (i < ipa.length) {
            val cp = ipa.codePointAt(i)
            vocab[String(Character.toChars(cp))]?.let { tokens += it }
            i += Character.charCount(cp)
        }
        if (tokens.isEmpty()) return ShortArray(0)
        val out = ArrayList<FloatArray>()
        for (window in tokens.chunked(MAX_TOKENS)) out += infer(window)
        return peakNormalize(out.flatMap { it.asList() }.toFloatArray())
    }

    private fun infer(tokens: List<Long>): FloatArray {
        val ids = LongArray(tokens.size + 2).also { tokens.forEachIndexed { k, t -> it[k + 1] = t } }
        val row = minOf(tokens.size, styleRows) - 1
        val style = styles.data.copyOfRange(row * styleDim, (row + 1) * styleDim)
        val inputs = mapOf(
            tokensInput to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())),
            "style" to OnnxTensor.createTensor(env, FloatBuffer.wrap(style), longArrayOf(1, styleDim.toLong())),
            "speed" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1f)), longArrayOf(1)),
        )
        try {
            session.run(inputs).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                return FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val MAX_TOKENS = 510
    }
}

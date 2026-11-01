package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** A Piper (VITS) voice exported to ONNX, driven by IPA phonemes. */
class PiperVoice(private val env: OrtEnvironment, modelPath: String, configJson: String) : AutoCloseable {
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val idMap: Map<String, List<Long>>
    val sampleRate: Int
    val speakers: Map<String, Int>
    private val noiseScale: Float
    private val lengthScale: Float
    private val noiseW: Float

    init {
        val cfg = Json.parseToJsonElement(configJson).jsonObject
        idMap = cfg.getValue("phoneme_id_map").jsonObject.mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.int.toLong() } }
        sampleRate = cfg.getValue("audio").jsonObject.getValue("sample_rate").jsonPrimitive.int
        speakers = cfg["speaker_id_map"]?.jsonObject?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap()
        val inference = cfg["inference"]?.jsonObject
        noiseScale = inference?.get("noise_scale")?.jsonPrimitive?.float ?: 0.667f
        lengthScale = inference?.get("length_scale")?.jsonPrimitive?.float ?: 1f
        noiseW = inference?.get("noise_w")?.jsonPrimitive?.float ?: 0.8f
    }

    /** Piper's id layout: BOS, PAD, then each phoneme followed by PAD, then EOS. Unknown symbols are dropped. */
    fun phonemeIds(ipa: String): LongArray {
        val ids = ArrayList<Long>(ipa.length * 2 + 3)
        ids += idMap.getValue(BOS)
        ids += idMap.getValue(PAD)
        var i = 0
        while (i < ipa.length) {
            val cp = ipa.codePointAt(i)
            val symbol = String(Character.toChars(cp))
            idMap[symbol]?.let {
                ids += it
                ids += idMap.getValue(PAD)
            }
            i += Character.charCount(cp)
        }
        ids += idMap.getValue(EOS)
        return ids.toLongArray()
    }

    /**
     * Speaks [ipa] and returns 16-bit PCM at [sampleRate]. [speed] above 1 speaks faster.
     */
    fun synthesize(ipa: String, speakerId: Int = 0, speed: Float = 1f): ShortArray {
        val ids = phonemeIds(ipa)
        if (ids.size <= 3) return ShortArray(0)
        val inputs = HashMap<String, OnnxTensor>()
        try {
            inputs["input"] = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong()))
            inputs["input_lengths"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ids.size.toLong())), longArrayOf(1))
            inputs["scales"] = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(floatArrayOf(noiseScale, lengthScale / speed, noiseW)),
                longArrayOf(3),
            )
            if (session.inputNames.contains("sid")) {
                inputs["sid"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speakerId.toLong())), longArrayOf(1))
            }
            session.run(inputs).use { out ->
                val tensor = out.get(0) as OnnxTensor
                val buffer = tensor.floatBuffer
                val audio = FloatArray(buffer.remaining()).also { buffer.get(it) }
                // Like Piper: normalize each utterance's peak, so loudness matches other voices.
                var peak = 0f
                for (v in audio) peak = maxOf(peak, kotlin.math.abs(v))
                val gain = if (peak > 1e-8f) PEAK / peak else 0f
                return ShortArray(audio.size) { k -> ((audio[k] * gain).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    private companion object {
        const val BOS = "^"
        const val EOS = "$"
        const val PAD = "_"
        const val PEAK = 0.95f
    }
}

package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.Random

/**
 * A BlueTTS voice (flow matching, 44.1 kHz), following the reference blue-onnx pipeline: predict
 * the duration, encode the phonemes, denoise a random latent in a few guided steps, then vocode.
 */
class BlueVoice(private val env: OrtEnvironment, private val dir: File, style: File) : IpaEngine {
    private fun session(name: String): OrtSession = env.createSession(File(dir, name).path, OrtSession.SessionOptions())

    private val durationModel = session(DURATION)
    private val textEncoder = session(TEXT_ENCODER)
    private val vectorField = session(VECTOR_FIELD)
    private val vocoder = session(VOCODER)
    private val charIds: Map<String, Long>
    private val padId: Long
    private val ttl: Tensor
    private val dp: Tensor
    private val uText: Tensor
    private val uRef: Tensor
    private val mean: FloatArray?
    private val std: FloatArray?
    private val normalizerScale: Float
    private val latentDim: Int
    private val compress: Int
    private val frame: Int
    private val random = Random()
    override val sampleRate: Int

    private class Tensor(val shape: LongArray, val data: FloatArray)

    init {
        val cfg = Json.parseToJsonElement(File(dir, CONFIG).readText()).jsonObject
        val ae = cfg.getValue("ae").jsonObject
        val ttlCfg = cfg.getValue("ttl").jsonObject
        sampleRate = ae.getValue("sample_rate").jsonPrimitive.int
        latentDim = ttlCfg.getValue("latent_dim").jsonPrimitive.int
        compress = ttlCfg.getValue("chunk_compress_factor").jsonPrimitive.int
        frame = ae.getValue("base_chunk_size").jsonPrimitive.int * compress

        val vocab = Json.parseToJsonElement(File(dir, VOCAB).readText()).jsonObject
        padId = vocab["pad_id"]?.jsonPrimitive?.int?.toLong() ?: 0L
        charIds = vocab.getValue("char_to_id").jsonObject.mapValues { it.value.jsonPrimitive.int.toLong() }

        val styleJson = Json.parseToJsonElement(style.readText()).jsonObject
        ttl = styleTensor(styleJson.getValue("style_ttl").jsonObject)
        dp = styleTensor(styleJson.getValue("style_dp").jsonObject)

        val uncond = Npy.readArchive(File(dir, UNCOND))
        uText = uncond.getValue("u_text").let { Tensor(it.shape.map(Int::toLong).toLongArray(), it.data) }
        uRef = uncond.getValue("u_ref").let { Tensor(it.shape.map(Int::toLong).toLongArray(), it.data) }

        // Bundles whose vocoder takes the raw latent ship de-normalization stats instead.
        val stats = File(dir, STATS).takeIf { it.exists() }?.let { Npy.readArchive(it) }
        if (stats != null && vocoderTakesLatent()) {
            mean = stats.getValue("mean").data
            std = stats.getValue("std").data
            normalizerScale = stats["normalizer_scale"]?.data?.firstOrNull()?.takeIf { it != 0f } ?: 1f
        } else {
            mean = null
            std = null
            normalizerScale = 1f
        }
    }

    private fun vocoderTakesLatent(): Boolean {
        val info = vocoder.inputInfo.values.first().info as ai.onnxruntime.TensorInfo
        return info.shape.getOrNull(1) == latentDim.toLong()
    }

    private fun styleTensor(o: JsonObject): Tensor {
        val dims = o.getValue("dims").jsonArray.map { it.jsonPrimitive.int.toLong() }.toLongArray()
        val values = ArrayList<Float>()
        fun flatten(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::flatten)
                is JsonPrimitive -> values += e.float
                else -> Unit
            }
        }
        flatten(o.getValue("data"))
        return Tensor(dims, values.toFloatArray())
    }

    override fun speak(ipa: String): ShortArray {
        var text = Normalizer.normalize(ipa, Normalizer.Form.NFKD).replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ShortArray(0)
        if (!Regex("[.!?;:,'\")\\]}…]$").containsMatchIn(text)) text += "."
        val ids = ArrayList<Long>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            ids += charIds[String(Character.toChars(cp))] ?: padId
            i += Character.charCount(cp)
        }
        return peakNormalize(infer(ids.toLongArray()))
    }

    private fun infer(textIds: LongArray): FloatArray {
        val tLen = textIds.size.toLong()
        val open = ArrayList<OnnxTensor>()
        fun f(data: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).also { open += it }
        fun f(t: Tensor) = f(t.data, *t.shape)
        try {
            val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(textIds), longArrayOf(1, tLen)).also { open += it }
            val textMask = f(FloatArray(textIds.size) { 1f }, 1, 1, tLen)
            val styleTtl = f(ttl)
            val seconds = durationModel.run(mapOf("text_ids" to ids, "style_dp" to f(dp), "text_mask" to textMask)).use {
                val b = (it.get(0) as OnnxTensor).floatBuffer
                b.get(0)
            }
            val textEmb = textEncoder.run(mapOf("text_ids" to ids, "style_ttl" to styleTtl, "text_mask" to textMask)).use {
                val b = (it.get(0) as OnnxTensor).floatBuffer
                FloatArray(b.remaining()).also { a -> b.get(a) }
            }
            val emb = f(textEmb, 1, textEmb.size / tLen, tLen)
            val tLat = ((seconds * sampleRate).toLong().coerceAtLeast(1) + frame - 1) / frame
            val channels = latentDim * compress
            var xt = FloatArray((channels * tLat).toInt()) { random.nextGaussian().toFloat() }
            val latentMask = f(FloatArray(tLat.toInt()) { 1f }, 1, 1, tLat)
            val total = f(floatArrayOf(STEPS.toFloat()), 1)
            val uTextT = f(uText)
            val uRefT = f(uRef)
            val uMask = f(floatArrayOf(1f), 1, 1, 1)
            for (step in 0 until STEPS) {
                val current = f(floatArrayOf(step.toFloat()), 1)
                val noisy = f(xt, 1, channels.toLong(), tLat)
                val cond = run(noisy, emb, styleTtl, textMask, latentMask, current, total)
                val uncond = run(noisy, uTextT, uRefT, uMask, latentMask, current, total)
                xt = FloatArray(cond.size) { k -> uncond[k] + CFG_SCALE * (cond[k] - uncond[k]) }
            }
            val latent = if (mean != null && std != null) decompress(xt, tLat.toInt()) else xt
            val latentChannels = if (mean != null) latentDim.toLong() else channels.toLong()
            val latentT = latent.size / latentChannels
            val wav = vocoder.run(mapOf("latent" to f(latent, 1, latentChannels, latentT))).use {
                val b = (it.get(0) as OnnxTensor).floatBuffer
                FloatArray(b.remaining()).also { a -> b.get(a) }
            }
            return if (wav.size > 2 * frame) wav.copyOfRange(frame, wav.size - frame) else wav
        } finally {
            open.forEach { it.close() }
        }
    }

    private fun run(
        noisy: OnnxTensor, textEmb: OnnxTensor, style: OnnxTensor, textMask: OnnxTensor,
        latentMask: OnnxTensor, current: OnnxTensor, total: OnnxTensor,
    ): FloatArray = vectorField.run(
        mapOf(
            "noisy_latent" to noisy, "text_emb" to textEmb, "style_ttl" to style, "text_mask" to textMask,
            "latent_mask" to latentMask, "current_step" to current, "total_step" to total,
        ),
    ).use {
        val b = (it.get(0) as OnnxTensor).floatBuffer
        FloatArray(b.remaining()).also { a -> b.get(a) }
    }

    /** De-normalizes and unfolds (ldim*f, T) channel-major into (ldim, T*f), as the vocoder expects. */
    private fun decompress(xt: FloatArray, t: Int): FloatArray {
        val m = mean!!
        val s = std!!
        val out = FloatArray(xt.size)
        for (l in 0 until latentDim) for (j in 0 until compress) {
            val ch = l * compress + j
            for (k in 0 until t) {
                val z = (xt[ch * t + k] / normalizerScale) * s[ch] + m[ch]
                out[l * t * compress + k * compress + j] = z
            }
        }
        return out
    }

    override fun close() {
        durationModel.close()
        textEncoder.close()
        vectorField.close()
        vocoder.close()
    }

    companion object {
        const val DURATION = "duration_predictor_style.onnx"
        const val TEXT_ENCODER = "text_encoder.onnx"
        const val VECTOR_FIELD = "vector_estimator.onnx"
        const val VOCODER = "vocoder.onnx"
        const val CONFIG = "tts.json"
        const val VOCAB = "vocab.json"
        const val UNCOND = "uncond.npz"
        const val STATS = "stats.npz"
        private const val STEPS = 5
        private const val CFG_SCALE = 4f
    }
}

package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Hebrew grapheme-to-phoneme: unvocalized Hebrew text to stressed IPA.
 *
 * Kotlin port of renikud-onnx 0.1.1 (https://github.com/thewh1teagle/renikud, CC BY 4.0). The
 * model predicts a consonant, a vowel and stress for every letter; vocabularies and per-letter
 * constraints are stored in the ONNX file's metadata. Non-Hebrew characters pass through.
 */
class HebrewG2p(private val env: OrtEnvironment, modelPath: String) : HebrewPhonemizer {
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions())
    private val vocab: Map<String, Int>
    private val consonants: Map<Int, String>
    private val vowels: Map<Int, String>
    private val constraints: Map<String, List<Int>>
    private val gereshMap: Map<String, String>
    private val clsId: Long
    private val sepId: Long
    private val unkId: Long

    init {
        val meta = session.metadata.customMetadata
        fun obj(key: String) = Json.parseToJsonElement(meta.getValue(key)).jsonObject
        vocab = obj("vocab").mapValues { it.value.jsonPrimitive.int }
        consonants = obj("consonant_vocab").entries.associate { it.key.toInt() to it.value.jsonPrimitive.content }
        vowels = obj("vowel_vocab").entries.associate { it.key.toInt() to it.value.jsonPrimitive.content }
        constraints = obj("letter_consonant_constraints").mapValues { e -> e.value.jsonArray.map { it.jsonPrimitive.int } }
        gereshMap = meta["geresh_map"]?.let { Json.parseToJsonElement(it).jsonObject.mapValues { e -> e.value.jsonPrimitive.content } }
            ?: emptyMap()
        clsId = meta.getValue("cls_token_id").toLong()
        sepId = meta.getValue("sep_token_id").toLong()
        unkId = (vocab["[UNK]"] ?: 0).toLong()
    }

    override fun phonemize(text: String): String {
        // Code points of the decomposed text; index i is token i + 1 (after CLS).
        val chars = codePoints(Normalizer.normalize(normalizeGraphemes(text), Normalizer.Form.NFD))
        if (chars.isEmpty()) return ""
        val ids = LongArray(chars.size + 2)
        ids[0] = clsId
        chars.forEachIndexed { i, c -> ids[i + 1] = (vocab[c] ?: unkId.toInt()).toLong() }
        ids[ids.size - 1] = sepId
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1, ids.size.toLong())

        val (consonantLogits, vowelLogits, stressLogits) =
            OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsTensor ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), shape).use { maskTensor ->
                    session.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        Triple(
                            (out.get("consonant_logits").get().value as Array<Array<FloatArray>>)[0],
                            (out.get("vowel_logits").get().value as Array<Array<FloatArray>>)[0],
                            (out.get("stress_logits").get().value as Array<Array<FloatArray>>)[0],
                        )
                    }
                }
            }

        val stressed = stressedTokens(chars, stressLogits)
        val sb = StringBuilder()
        for ((i, char) in chars.withIndex()) {
            val tok = i + 1
            if (!isHebrewLetter(char)) {
                if (char != "'" && char != "\"") sb.append(char)
                continue
            }
            var cid = argmax(consonantLogits[tok])
            val allowed = constraints[char]
            if (allowed != null && cid !in allowed) cid = allowed.maxBy { consonantLogits[tok][it] }
            var consonant = consonants[cid] ?: NONE
            // Geresh: ג׳ צ׳ ז׳ are read as dʒ tʃ ʒ.
            val next = chars.getOrNull(i + 1)
            if (next == "'" && char in gereshMap) consonant = gereshMap.getValue(char)
            val vowel = vowels[argmax(vowelLogits[tok])] ?: NONE
            val stress = tok in stressed
            val wordFinal = next == null || !Character.isLetter(next.codePointAt(0))
            if (char == "ח" && wordFinal && vowel == "a") {
                // Furtive patah: final ח with "a" is read "aχ".
                if (stress) sb.append(STRESS)
                sb.append("aχ")
            } else {
                if (consonant != NONE) sb.append(consonant)
                if (stress) sb.append(STRESS)
                if (vowel != NONE) sb.append(vowel)
            }
        }
        return sb.toString()
    }

    /** For each whitespace-separated word, the single token most likely to carry stress. */
    private fun stressedTokens(chars: List<String>, stressLogits: Array<FloatArray>): Set<Int> {
        val result = HashSet<Int>()
        var i = 0
        while (i < chars.size) {
            if (chars[i].isBlank()) { i++; continue }
            var best = -1
            while (i < chars.size && !chars[i].isBlank()) {
                val tok = i + 1
                if (best == -1 || stressLogits[tok][1] > stressLogits[best][1]) best = tok
                i++
            }
            if (best != -1) result += best
        }
        return result
    }

    override fun close() = session.close()

    companion object {
        private const val STRESS = "ˈ"
        private const val NONE = "∅"

        private fun isHebrewLetter(c: String) = c.length == 1 && c[0] in 'א'..'ת'

        private fun argmax(v: FloatArray): Int {
            var best = 0
            for (k in 1 until v.size) if (v[k] > v[best]) best = k
            return best
        }

        private fun codePoints(s: String): List<String> {
            val out = ArrayList<String>(s.length)
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                out += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            return out
        }

        /** Unifies geresh and gershayim variants, as renikud does. */
        fun normalizeGraphemes(text: String): String =
            text.replace(Regex("[׳'`´]"), "'").replace(Regex("[״\"“”]"), "\"")
    }
}

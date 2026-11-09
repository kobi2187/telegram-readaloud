package io.github.kobi2187.readaloud.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.LongBuffer
import java.text.Normalizer

/** Hebrew text to stressed IPA: renikud (built into every voice) or the Phonikud pipeline. */
interface HebrewPhonemizer : AutoCloseable {
    fun phonemize(text: String): String
}

/**
 * Hebrew text to IPA the way the original Phonikud project does it: a model adds nikud plus
 * phonetic marks (stress, vocal shva, prefixes), then rules read the marked text.
 *
 * Ports of phonikud-onnx 1.0.6 (the diacritizer) and phonikud 0.4.1 (the rule-based phonemizer),
 * https://github.com/thewh1teagle/phonikud, MIT. The model is Phonikud's enhancement of Dicta's
 * dictabert-large-char-menaked (CC BY 4.0).
 */
class PhonikudG2p(env: OrtEnvironment, model: File, tokenizer: File) : HebrewPhonemizer {
    private val diacritizer = PhonikudDiacritizer(env, model, tokenizer)

    override fun phonemize(text: String): String = PhonikudPhonemizer.phonemize(diacritizer.addDiacritics(text))

    override fun close() = diacritizer.close()
}

/** Adds nikud and Phonikud's phonetic marks to unvocalized Hebrew. */
class PhonikudDiacritizer(private val env: OrtEnvironment, model: File, tokenizer: File) : AutoCloseable {
    private val session: OrtSession = env.createSession(model.path, OrtSession.SessionOptions())
    private val vocab: Map<String, Long> = Json.parseToJsonElement(tokenizer.readText()).jsonObject
        .getValue("model").jsonObject.getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int.toLong() }

    /** One token: its id and the characters (code point indexes) it covers. */
    data class Token(val id: Long, val start: Int, val end: Int)

    /**
     * The model's tokens for [chars] (code points): one per character after the tokenizer's
     * normalizer (NFKC, lowercase, combining marks dropped); a run of unsupported characters is
     * one [UNK].
     */
    fun tokens(chars: IntArray): List<Token> {
        val out = ArrayList<Token>(chars.size)
        var i = 0
        while (i < chars.size) {
            if (!inRange(chars[i])) {
                var j = i
                while (j < chars.size && !inRange(chars[j])) j++
                out += Token(0, i, j)
                i = j
                continue
            }
            val s = Normalizer.normalize(String(Character.toChars(chars[i])), Normalizer.Form.NFKC)
                .lowercase().replace(Regex("\\p{Mn}"), "")
            var k = 0
            while (k < s.length) {
                val cp = s.codePointAt(k)
                out += Token(vocab[String(Character.toChars(cp))] ?: 0L, i, i + 1)
                k += Character.charCount(cp)
            }
            i++
        }
        return out
    }

    fun addDiacritics(text: String): String = chunks(text).joinToString("") { predict(it) }

    private fun predict(sentence: String): String {
        val chars = sentence.replace(NIKUD, "").codePoints().toArray()
        if (chars.isEmpty()) return ""
        val tokens = tokens(chars)
        val ids = LongArray(tokens.size + 2)
        ids[0] = 1 // [CLS]
        tokens.forEachIndexed { k, t -> ids[k + 1] = t.id }
        ids[ids.size - 1] = 2 // [SEP]
        val shape = longArrayOf(1, ids.size.toLong())
        val (nikud, shin, extra) = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { idsT ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { maskT ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { typesT ->
                    session.run(mapOf("input_ids" to idsT, "attention_mask" to maskT, "token_type_ids" to typesT)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        Triple(
                            (out.get("nikud_logits").get().value as Array<Array<FloatArray>>)[0],
                            (out.get("shin_logits").get().value as Array<Array<FloatArray>>)[0],
                            (out.get("additional_logits").get().value as Array<Array<FloatArray>>)[0],
                        )
                    }
                }
            }
        }
        val sb = StringBuilder()
        var prev = 0
        for ((t, token) in tokens.withIndex()) {
            val row = t + 1 // after [CLS]
            if (token.start > prev) sb.appendCodePoints(chars, prev, token.start)
            if (token.end - token.start != 1) continue
            val c = chars[token.start]
            prev = token.end
            val ch = String(Character.toChars(c))
            if (c !in 0x05D0..0x05EA) {
                sb.append(ch)
                continue
            }
            var n = NIKUD_CLASSES[argmax(nikud[row])]
            val shinMark = if (ch == "ש") SHIN_CLASSES[argmax(shin[row])] else ""
            if (n == MAT_LECT) {
                if (ch in "אוי") {
                    sb.append(ch)
                    continue
                }
                n = ""
            }
            val stress = if (extra[row][0] > 0) STRESS_CHAR else ""
            val shva = if (extra[row][1] > 0) VOCAL_SHVA_CHAR else ""
            val prefix = if (extra[row][2] > 0) PREFIX_CHAR else ""
            sb.append(ch).append(shinMark).append(n).append(stress).append(shva).append(prefix)
        }
        sb.appendCodePoints(chars, prev, chars.size)
        return sb.toString()
    }

    override fun close() = session.close()

    private fun StringBuilder.appendCodePoints(cps: IntArray, from: Int, to: Int) {
        for (k in from until to) appendCodePoint(cps[k])
    }

    companion object {
        private const val MAT_LECT = "<MAT_LECT>"
        private val NIKUD_CLASSES = listOf(
            "", MAT_LECT, "ּ", "ְ", "ֱ", "ֲ", "ֳ", "ִ", "ֵ", "ֶ", "ַ", "ָ",
            "ֹ", "ֺ", "ֻ", "ְּ", "ֱּ", "ֲּ", "ֳּ", "ִּ",
            "ֵּ", "ֶּ", "ַּ", "ָּ", "ֹּ", "ֺּ", "ֻּ",
            "ׇ", "ׇּ",
        )
        private val SHIN_CLASSES = listOf("ׁ", "ׂ")
        const val STRESS_CHAR = "֫"
        const val VOCAL_SHVA_CHAR = "ֽ"
        const val PREFIX_CHAR = "|"
        private const val MAX_CHUNK = 2046
        private val NIKUD = Regex("[֐-ׇ|]")

        /** The tokenizer's normalizer turns runs of characters outside these ranges into one [UNK]. */
        private fun inRange(c: Int) = c in 0x0590..0x05FF || c in 0x0000..0x007F || c in 0x200C..0x203F ||
            c in 0x20A0..0x20BF || c in 0x2200..0x22FF || c in 0x2150..0x218B || c in 0xFB00..0xFB4F

        private fun argmax(v: FloatArray): Int {
            var best = 0
            for (k in 1 until v.size) if (v[k] > v[best]) best = k
            return best
        }

        private fun chunks(text: String): List<String> {
            if (text.codePointCount(0, text.length) <= MAX_CHUNK) return listOf(text)
            val out = ArrayList<String>()
            var buf = ""
            for (part in text.split(Regex("(?<=[.\\n])"))) {
                if ((buf + part).codePointCount(0, (buf + part).length) <= MAX_CHUNK) {
                    buf += part
                } else {
                    if (buf.isNotEmpty()) out += buf
                    buf = part
                }
            }
            if (buf.isNotEmpty()) out += buf
            return out.flatMap { c ->
                val cps = c.codePoints().toArray()
                if (cps.size <= MAX_CHUNK) listOf(c)
                else cps.toList().chunked(MAX_CHUNK).map { ch -> String(ch.toIntArray(), 0, ch.size) }
            }
        }
    }
}

/** Phonikud's rules: vocalized Hebrew (with its phonetic marks) to IPA. */
object PhonikudPhonemizer {
    private const val VOCAL_SHVA = "ֽ"
    private const val HATAMA = "֫"
    private const val PREFIX = "|"
    private const val NIKUD_HASER = "֯"
    private val ENHANCED = setOf(HATAMA, PREFIX, VOCAL_SHVA)
    const val STRESS = "ˈ"

    private const val SHVA = "ְ"
    private const val SIN = "ׂ"
    private const val PATAH = "ַ"
    private const val KAMATZ = "ָ"
    private const val HATAF_KAMATZ = "ֳ"
    private const val DAGESH = "ּ"
    private const val HOLAM = "ֹ"
    private const val HIRIK = "ִ"
    private const val KUBUTS = "ֻ"
    private const val TSERE = "ֵ"
    private const val SEGOL = "ֶ"

    private val HE_PATTERN = Regex("[ְ-ת$VOCAL_SHVA$HATAMA|$NIKUD_HASER'\"]+")
    private val LETTERS = Regex("(\\p{L})([\\p{M}'|]*)")
    private val MARKS = Regex("(\\p{L})(\\p{M}+)")

    private val GERESH = mapOf("ג" to "dʒ", "ז" to "ʒ", "ת" to "ta", "צ" to "tʃ", "ץ" to "tʃ")
    private val CONSONANTS = mapOf(
        "א" to "ʔ", "ב" to "v", "ג" to "g", "ד" to "d", "ה" to "h", "ו" to "v", "ז" to "z", "ח" to "x", "ט" to "t",
        "י" to "j", "ך" to "x", "כ" to "x", "ל" to "l", "ם" to "m", "מ" to "m", "ן" to "n", "נ" to "n", "ס" to "s",
        "ע" to "ʔ", "פ" to "f", "ף" to "f", "ץ" to "ts", "צ" to "ts", "ק" to "k", "ר" to "r", "ש" to "ʃ", "ת" to "t",
        "בּ" to "b", "כּ" to "k", "פּ" to "p", "שׁ" to "ʃ", "שׂ" to "s", "'" to "",
    )
    private val VOWELS = mapOf(
        "ִ" to "i", "ֱ" to "e", "ֵ" to "e", "ֶ" to "e", "ֲ" to "a", "ַ" to "a",
        "ׇ" to "o", "ֹ" to "o", "ֺ" to "o", "ֻ" to "u", "ֳ" to "o", "ָ" to "a",
        HATAMA to STRESS, VOCAL_SHVA to "e",
    )
    private val MODERN = linkedMapOf("x" to "χ", "r" to "ʁ", "g" to "ɡ")

    /** Single characters that may appear in the output (Phonikud's phoneme set). */
    private val PHONEME_CHARS: Set<String> =
        (VOWELS.values + CONSONANTS.values + GERESH.values + MODERN.values + "w").filter { it.length == 1 }.toSet()
    private val PUNCTUATION = setOf(".", ",", "!", "?", " ")

    private class Letter(val char: String, diac: String) {
        var allDiac: String = sortedMarks(diac)
        val diac: String get() = allDiac.filter { it.toString() !in ENHANCED }
        override fun toString() = char + allDiac
    }

    private fun sortedMarks(s: String): String {
        val cps = s.codePoints().toArray().sorted()
        return String(cps.toIntArray(), 0, cps.size)
    }

    /** NFD, diacritics sorted after each letter, Hebrew geresh/gershayim and makaf unified. */
    fun normalize(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFD)
        s = MARKS.replace(s) { m -> m.groupValues[1] + sortedMarks(m.groupValues[2]) }
        return s.replace("״", "\"").replace("׳", "'").replace("־", "-")
    }

    /** IPA for [vocalized] text; [keepStress] false leaves out the stress marks. */
    fun phonemize(vocalized: String, keepStress: Boolean = true): String {
        val text = normalize(vocalized)
        var out = HE_PATTERN.replace(text) { m ->
            if (m.range.first > 0 && text[m.range.first - 1] == '[') return@replace m.value
            var word = m.value
            if (HATAMA !in word) word = addMilraHatama(word)
            val letters = sortHatama(letters(word))
            var phonemes = postNormalize(phonemizeHebrew(letters).joinToString(""))
            for ((k, v) in MODERN) phonemes = phonemes.replace(k, v)
            phonemes
        }
        if (!keepStress) out = out.replace(STRESS, "")
        return postClean(out)
    }

    private fun letters(word: String) = LETTERS.findAll(word).map { Letter(it.groupValues[1], it.groupValues[2]) }.toMutableList()

    private fun sortHatama(letters: MutableList<Letter>): MutableList<Letter> {
        for (i in 0 until letters.size - 1) {
            val d = letters[i].allDiac
            if (HATAMA in d && NIKUD_HASER in d) {
                letters[i].allDiac = d.replaceFirst(HATAMA, "")
                letters[i + 1].allDiac += HATAMA
            }
        }
        return letters
    }

    private fun postNormalize(phonemes: String) = phonemes.split(" ").joinToString(" ") { w ->
        w.replace(Regex("ʔ$"), "").replace(Regex("h$"), "").replace(Regex("ˈh$"), "").replace(Regex("ij$"), "i")
    }

    private fun postClean(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val c = String(Character.toChars(cp))
            when {
                c == "-" -> sb.append(' ')
                c in PHONEME_CHARS || c in PUNCTUATION -> sb.append(c)
            }
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    // --- Syllables and default (final-syllable) stress ------------------------------------

    private val VOWEL_DIACS: Set<Char> = ((0x05B1 until 0x05BC).map { it.toChar() } + 'ׇ' + 'ֽ').toSet()

    private fun hasVowelDiacs(s: String) = s == "וּ" || s.any { it in VOWEL_DIACS }

    private fun syllables(word: String): MutableList<String> {
        val letters = letters(word)
        val syllables = ArrayList<String>()
        var cur = ""
        var vowelState = false
        var i = 0
        while (i < letters.size) {
            val letter = letters[i]
            val hasVowel = hasVowelDiacs(letter.toString()) || (i == 0 && SHVA in letter.allDiac)
            val vav1 = i + 2 < letters.size && letters[i + 2].char == "ו"
            val vav2 = i + 3 < letters.size && letters[i + 3].char == "ו"
            if (hasVowel) {
                if (vowelState) {
                    syllables += cur
                    cur = letter.toString()
                } else {
                    cur += letter.toString()
                }
                vowelState = true
            } else {
                cur += letter.toString()
            }
            i++
            if (vav1 && vav2) {
                if (cur.isNotEmpty()) {
                    syllables += cur + letters[i].toString()
                    cur = ""
                }
                cur = letters[i + 1].toString() + letters[i + 2].toString()
                i += 3
                vowelState = true
            } else if (vav1 && letters[i + 1].diac.isNotEmpty()) {
                if (cur.isNotEmpty()) {
                    syllables += cur
                    cur = ""
                }
                vowelState = false
            }
        }
        if (cur.isNotEmpty()) syllables += cur
        return syllables
    }

    private fun addMilraHatama(word: String): String {
        val syllables = syllables(word)
        if (syllables.isEmpty()) return word
        val letters = letters(syllables.last())
        if (letters.isEmpty()) return word
        letters[0].allDiac += HATAMA
        syllables[syllables.size - 1] = letters.joinToString("")
        return syllables.joinToString("")
    }

    // --- Letters to phonemes ----------------------------------------------------------------

    private fun phonemizeHebrew(letters: List<Letter>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < letters.size) {
            val (phonemes, skip) = letterToPhonemes(letters[i], letters.getOrNull(i - 1), letters.getOrNull(i + 1))
            out += phonemes
            i += skip + 1
        }
        return out
    }

    private fun handleYud(cur: Letter, prev: Letter?, next: Letter?) =
        next != null && cur.diac.isEmpty() && prev != null && prev.char + prev.diac != "אֵ" &&
            !(next.char == "ו" && next.diac.isNotEmpty() && "ְ" !in next.diac)

    private data class Vav(val phonemes: List<String>, val skipConsonants: Boolean, val skipDiacritics: Boolean, val offset: Int)

    private fun handleVav(cur: Letter, prev: Letter?, next: Letter?): Vav {
        if (prev != null && SHVA in prev.diac && HOLAM in cur.diac) return Vav(listOf("vo"), true, true, 0)
        if (next != null && next.char == "ו") {
            val diac = cur.diac + next.diac
            return when {
                HOLAM in diac -> Vav(listOf("vo"), true, true, 1)
                cur.diac == next.diac -> Vav(listOf("vu"), true, true, 1)
                HIRIK in cur.diac -> Vav(listOf("vi"), true, true, 0)
                SHVA in cur.diac && next.diac.isEmpty() -> Vav(listOf("v"), true, true, 0)
                KAMATZ in cur.diac || PATAH in cur.diac -> Vav(listOf("va"), true, true, 0)
                TSERE in cur.diac || SEGOL in cur.diac -> Vav(listOf("ve"), true, true, 0)
                else -> Vav(emptyList(), false, false, 0)
            }
        }
        return when {
            PATAH in cur.diac || KAMATZ in cur.diac -> Vav(listOf("va"), true, true, 0)
            TSERE in cur.diac || SEGOL in cur.diac -> Vav(listOf("ve"), true, true, 0)
            HOLAM in cur.diac -> Vav(listOf("o"), true, true, 0)
            KUBUTS in cur.diac || DAGESH in cur.diac -> Vav(listOf("u"), true, true, 0)
            SHVA in cur.diac && prev == null -> Vav(listOf("ve"), true, true, 0)
            HIRIK in cur.diac -> Vav(listOf("vi"), true, true, 0)
            next != null && cur.diac.isEmpty() -> Vav(emptyList(), true, true, 0)
            else -> Vav(listOf("v"), true, true, 0)
        }
    }

    private fun letterToPhonemes(cur: Letter, prev: Letter?, next: Letter?): Pair<List<String>, Int> {
        val phonemes = ArrayList<String>()
        var skipDiacritics = false
        var skipConsonants = false
        var skipOffset = 0

        if (NIKUD_HASER in cur.allDiac) {
            skipConsonants = true
            skipDiacritics = true
        } else if (cur.char == "א" && cur.diac.isEmpty() && prev != null) {
            if (next != null && next.char != "ו") skipConsonants = true
        } else if (cur.char == "י" && handleYud(cur, prev, next)) {
            skipConsonants = true
        } else if (cur.char == "ש" && SIN in cur.diac) {
            if (next != null && next.char == "ש" && next.diac.isEmpty() && Regex("[ַָ]").containsMatchIn(cur.diac)) {
                phonemes += "sa"
                skipConsonants = true
                skipDiacritics = true
                skipOffset += 1
            } else {
                phonemes += "s"
                skipConsonants = true
            }
        } else if (cur.char == "ש" && cur.diac.isEmpty() && prev != null && SIN in prev.diac) {
            phonemes += "s"
            skipConsonants = true
        } else if (next == null && cur.char == "ח" && PATAH in cur.diac) {
            phonemes += "ax"
            skipDiacritics = true
            skipConsonants = true
        } else if (next == null && cur.char == "ה" && PATAH in cur.diac) {
            phonemes += "ah"
            skipDiacritics = true
            skipConsonants = true
        } else if (next == null && cur.char == "ע" && PATAH in cur.diac) {
            phonemes += "a"
            skipDiacritics = true
            skipConsonants = true
        }

        if ("'" in cur.diac && cur.char in GERESH) {
            phonemes += GERESH.getValue(cur.char)
            skipConsonants = true
            if (cur.char == "ת") skipDiacritics = true
        } else if (DAGESH in cur.diac && (cur.char + DAGESH) in CONSONANTS) {
            phonemes += CONSONANTS.getValue(cur.char + DAGESH)
            skipConsonants = true
        } else if (cur.char == "ו" && NIKUD_HASER !in cur.allDiac) {
            val vav = handleVav(cur, prev, next)
            phonemes += vav.phonemes
            skipConsonants = vav.skipConsonants
            skipDiacritics = vav.skipDiacritics
            skipOffset += vav.offset
        }

        if (!skipConsonants) phonemes += CONSONANTS[cur.char] ?: ""

        if (KAMATZ in cur.diac && next != null && HATAF_KAMATZ in next.diac) {
            phonemes += "o"
            skipDiacritics = true
        }

        if (!skipDiacritics) {
            for (n in cur.allDiac) phonemes += VOWELS[n.toString()] ?: ""
        } else if (HATAMA in cur.allDiac) {
            phonemes += STRESS
        }
        val sorted = sortStress(phonemes)
        return sorted.filter { p -> p.isNotEmpty() && p.all { it.toString() in PHONEME_CHARS } } to skipOffset
    }

    /** Moves the stress mark right before the first vowel (where TTS models expect it). */
    private fun sortStress(phonemes: List<String>): List<String> {
        val joined = phonemes.joinToString("")
        if (STRESS !in joined || joined.none { it in "aeiou" }) return phonemes
        val out = phonemes.filter { it != STRESS }.toMutableList()
        for (i in out.indices) {
            val j = out[i].indexOfFirst { it in "aeiou" }
            if (j >= 0) {
                out[i] = out[i].substring(0, j) + STRESS + out[i].substring(j)
                return out
            }
        }
        return out
    }
}

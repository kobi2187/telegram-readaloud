package io.github.kobi2187.readaloud.text

/** Languages we pick a dedicated voice for. Detection is by script, which is reliable for these. */
enum class Lang(val tag: String) {
    HE("he-IL"), EN("en-US"), AR("ar-SA"), RU("ru-RU");
}

data class Segment(val lang: Lang, val text: String)

/**
 * Splits mixed-language text into runs that each go to a single voice. Splitting happens at
 * sentence and line boundaries only, so a Hebrew sentence with an English brand name stays in one
 * Hebrew run (Hebrew neural voices read embedded Latin words well), while a whole English sentence
 * in a Hebrew post is read by the English voice.
 */
object LanguageSegmenter {

    private val sentenceSplit = Regex("""(?<=[.!?…:;])\s+|\n+""")

    fun segment(text: String, fallback: Lang = Lang.EN): List<Segment> {
        val pieces = splitKeepingText(text)
        if (pieces.isEmpty()) return emptyList()
        val tagged = pieces.map { it to detect(it) }
        val defaultLang = dominant(text) ?: fallback

        val segments = mutableListOf<Segment>()
        var pending = StringBuilder() // neutral text waiting for a language
        for ((piece, lang) in tagged) {
            if (lang == null) {
                if (segments.isNotEmpty()) {
                    val last = segments.removeAt(segments.lastIndex)
                    segments += last.copy(text = join(last.text, piece))
                } else {
                    pending = StringBuilder(join(pending.toString(), piece))
                }
                continue
            }
            val withPending = if (pending.isNotEmpty()) join(pending.toString(), piece) else piece
            pending = StringBuilder()
            val last = segments.lastOrNull()
            if (last != null && last.lang == lang) {
                segments[segments.lastIndex] = last.copy(text = join(last.text, withPending))
            } else {
                segments += Segment(lang, withPending)
            }
        }
        if (pending.isNotEmpty()) segments += Segment(defaultLang, pending.toString())
        return segments
    }

    /** The language with the most letters in [text], or null when it has no letters at all. */
    fun dominant(text: String): Lang? = detect(text)

    fun detect(text: String): Lang? {
        var he = 0
        var ar = 0
        var ru = 0
        var en = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                cp in 0x0590..0x05FF || cp in 0xFB1D..0xFB4F -> he++
                cp in 0x0600..0x06FF || cp in 0x0750..0x077F || cp in 0xFB50..0xFDFF ||
                    cp in 0xFE70..0xFEFF -> ar++
                cp in 0x0400..0x04FF -> ru++
                Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN -> en++
            }
            i += Character.charCount(cp)
        }
        val max = maxOf(he, ar, ru, en)
        if (max == 0) return null
        return when (max) {
            he -> Lang.HE
            en -> Lang.EN
            ar -> Lang.AR
            else -> Lang.RU
        }
    }

    private fun splitKeepingText(text: String): List<String> =
        text.split(sentenceSplit).map { it.trim() }.filter { it.isNotEmpty() }

    private fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a\n$b"
    }
}

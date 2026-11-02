package io.github.kobi2187.readaloud.voice

/**
 * Spells out what the offline Hebrew voice cannot read: numbers, percentages, currencies,
 * times and dates become Hebrew words. Numbers use the feminine counting forms, as they are
 * read aloud in Israeli Hebrew ("שישים ושתיים אחוז").
 */
object HebrewText {

    private val onesF = listOf("אפס", "אחת", "שתיים", "שלוש", "ארבע", "חמש", "שש", "שבע", "שמונה", "תשע")
    private val teensF = listOf(
        "עשר", "אחת עשרה", "שתים עשרה", "שלוש עשרה", "ארבע עשרה",
        "חמש עשרה", "שש עשרה", "שבע עשרה", "שמונה עשרה", "תשע עשרה",
    )
    private val onesM = listOf("אפס", "אחד", "שניים", "שלושה", "ארבעה", "חמישה", "שישה", "שבעה", "שמונה", "תשעה")
    private val teensM = listOf(
        "עשרה", "אחד עשר", "שנים עשר", "שלושה עשר", "ארבעה עשר",
        "חמישה עשר", "שישה עשר", "שבעה עשר", "שמונה עשר", "תשעה עשר",
    )
    private val tens = listOf("", "", "עשרים", "שלושים", "ארבעים", "חמישים", "שישים", "שבעים", "שמונים", "תשעים")
    private val hundreds = listOf(
        "", "מאה", "מאתיים", "שלוש מאות", "ארבע מאות", "חמש מאות", "שש מאות", "שבע מאות", "שמונה מאות", "תשע מאות",
    )
    private val thousandsCount = listOf(
        "", "אלף", "אלפיים", "שלושת אלפים", "ארבעת אלפים", "חמשת אלפים",
        "ששת אלפים", "שבעת אלפים", "שמונת אלפים", "תשעת אלפים", "עשרת אלפים",
    )
    private val monthsOrdinal = listOf(
        "", "לראשון", "לשני", "לשלישי", "לרביעי", "לחמישי", "לשישי",
        "לשביעי", "לשמיני", "לתשיעי", "לעשירי", "לאחד עשר", "לשנים עשר",
    )

    /** Words for a non-negative number; masculine forms when counting a noun such as אלף. */
    fun number(n: Long, masculine: Boolean = false): String {
        if (n == 0L) return onesF[0]
        val parts = ArrayList<String>()
        addParts(n, masculine, parts)
        if (parts.size > 1) parts[parts.lastIndex] = "ו" + parts.last()
        return parts.joinToString(" ")
    }

    private fun addParts(n: Long, masculine: Boolean, parts: MutableList<String>) {
        var rest = n
        val scales = listOf(1_000_000_000L to "מיליארד", 1_000_000L to "מיליון")
        for ((scale, name) in scales) {
            val count = rest / scale
            if (count > 0) {
                parts += when (count) {
                    1L -> name
                    2L -> "שני $name"
                    else -> number(count, masculine = true) + " " + name
                }
                rest %= scale
            }
        }
        val thousands = rest / 1000
        if (thousands > 0) {
            parts += if (thousands <= 10) thousandsCount[thousands.toInt()] else number(thousands, masculine = true) + " אלף"
            rest %= 1000
        }
        val h = (rest / 100).toInt()
        if (h > 0) parts += hundreds[h]
        val below = (rest % 100).toInt()
        when {
            below == 0 -> Unit
            below < 10 -> parts += (if (masculine) onesM else onesF)[below]
            below < 20 -> parts += (if (masculine) teensM else teensF)[below - 10]
            else -> {
                parts += tens[below / 10]
                if (below % 10 != 0) parts += (if (masculine) onesM else onesF)[below % 10]
            }
        }
    }

    private fun digits(s: String) = s.map { onesF[it - '0'] }.joinToString(" ")

    /** A digit string as words: long codes and leading zeros are read digit by digit. */
    private fun integer(raw: String): String {
        val s = raw.replace(",", "")
        return if (s.length > 12 || (s.length > 1 && s.startsWith("0"))) digits(s) else number(s.toLong())
    }

    private fun decimal(intPart: String, frac: String): String {
        val fracWords = if (frac.length <= 2 && !frac.startsWith("0")) number(frac.toLong()) else digits(frac)
        return integer(intPart) + " נקודה " + fracWords
    }

    private val num = """\d{1,3}(?:,\d{3})+|\d+"""
    private val time = Regex("""(?<![\d:])([01]?\d|2[0-3]):([0-5]\d)(?![\d:])""")
    // Dates: d/m with an optional year, or d.m.yyyy ("3.5" alone is a decimal, not May 3rd).
    private val date = Regex(
        """(?<![\d./])(0?[1-9]|[12]\d|3[01])(?:/(0?[1-9]|1[0-2])(?:/(\d{4}|\d{2}))?|\.(0?[1-9]|1[0-2])\.(\d{4}))(?![\d./])""",
    )
    private val percent = Regex("""(?:%\s?($num(?:\.\d+)?)|($num(?:\.\d+)?)\s?%)""")
    private val currencyBefore = Regex("""([₪$€£])\s?($num(?:\.\d+)?)""")
    private val currencyAfter = Regex("""($num(?:\.\d+)?)\s?(₪|ש"ח|ש״ח|\$|€|£)""")
    private val range = Regex("""(?<![\d.,])([1-9]\d{0,3})\s?[-–]\s?([1-9]\d{0,3})(?![\d.,])""")
    private val decimalNumber = Regex("""(?<![\d.,])($num)\.(\d+)(?![\d.])""")
    private val plainNumber = Regex("""(?<![\d.,])($num)(?![\d])""")
    private val prefixedNumber = Regex("""(?<=[בהולמשכ])[-־](?=\d)""")

    private fun amount(text: String): String {
        val dot = text.indexOf('.')
        return if (dot > 0) decimal(text.substring(0, dot), text.substring(dot + 1)) else integer(text)
    }

    private fun currencyName(symbol: String, words: String): String = when (symbol) {
        "₪", "ש\"ח", "ש״ח" -> if (words == "אחת") "שקל אחד" else "$words שקלים"
        "$" -> "$words דולר"
        "€" -> "$words אירו"
        else -> "$words לירות שטרלינג"
    }

    fun normalize(text: String): String {
        var s = text
        // "ב-2026", "ה־62": drop the hyphen so the prefix joins the spelled-out number.
        s = prefixedNumber.replace(s, "")
        s = time.replace(s) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (min == 0) number(h.toLong()) else number(h.toLong()) + " " + number(min.toLong())
        }
        s = date.replace(s) { m ->
            val g = m.groupValues
            val day = number(g[1].toLong())
            val month = monthsOrdinal[g[2].ifEmpty { g[4] }.toInt()]
            val year = g[3].ifEmpty { g[5] }.takeIf { it.isNotEmpty() }?.let { y ->
                " " + number(if (y.length == 2) 2000L + y.toLong() else y.toLong())
            }.orEmpty()
            "$day $month$year"
        }
        s = percent.replace(s) { m -> amount(m.groupValues[1].ifEmpty { m.groupValues[2] }) + " אחוז" }
        s = currencyBefore.replace(s) { m -> currencyName(m.groupValues[1], amount(m.groupValues[2])) }
        s = currencyAfter.replace(s) { m -> currencyName(m.groupValues[2], amount(m.groupValues[1])) }
        s = range.replace(s) { m -> integer(m.groupValues[1]) + " עד " + integer(m.groupValues[2]) }
        s = decimalNumber.replace(s) { m -> decimal(m.groupValues[1], m.groupValues[2]) }
        s = plainNumber.replace(s) { m -> integer(m.groupValues[1]) }
        s = s.replace("&", " ו").replace("+", " פלוס ")
        return s.replace(Regex("[ \t]{2,}"), " ")
    }

    /** Splits into utterances short enough for the voice model, keeping the end punctuation. */
    fun sentences(text: String, maxLength: Int = 280): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>() // text to paragraph-break-after
        for (paragraph in text.split(Regex("\n\\s*\n"))) {
            val pieces = paragraph.split(Regex("(?<=[.!?…:;])\\s+|\n+")).map { it.trim() }.filter { p -> p.any { it.isLetterOrDigit() } }
            for ((i, piece) in pieces.withIndex()) {
                val chunks = if (piece.length <= maxLength) listOf(piece) else splitLong(piece, maxLength)
                chunks.forEachIndexed { k, c -> out += c to (i == pieces.lastIndex && k == chunks.lastIndex) }
            }
        }
        return out
    }

    private fun splitLong(s: String, maxLength: Int): List<String> {
        val out = ArrayList<String>()
        var rest = s
        while (rest.length > maxLength) {
            var cut = rest.lastIndexOf(", ", maxLength).let { if (it > maxLength / 3) it + 1 else -1 }
            if (cut <= 0) cut = rest.lastIndexOf(' ', maxLength)
            if (cut <= 0) cut = maxLength
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}

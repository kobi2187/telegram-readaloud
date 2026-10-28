package io.github.kobi2187.readaloud.text

/**
 * Turns a Telegram post into text that sounds good when spoken: no URLs, no emoji,
 * no decorative separators, and sentence punctuation at line ends so TTS engines pause.
 */
object SpeechText {

    private val urlRegex = Regex(
        """(?i)\b(?:https?://|www\.)\S+|\b(?:t\.me|telegram\.me)/\S+""",
    )
    private val bareDomainRegex = Regex(
        """(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)*\.(?:com|net|org|co\.il|org\.il|gov\.il|ac\.il|io|me|ly|news|tv|info)(?:/\S*)?""",
    )
    private val hashtagRegex = Regex("""#([\p{L}\p{N}_]+)""")
    private val mentionRegex = Regex("""(?<![\p{L}\p{N}])@([A-Za-z0-9_]{3,})""")
    private val separatorRunRegex = Regex("""([\-=_*~.·•|/\\—–]\s*){3,}""")
    private val leadingBulletRegex = Regex("""(?m)^\s*[-*•·>]+\s+""")
    private val spacesRegex = Regex("""[ \t  -​  　]+""")
    private val blankLinesRegex = Regex("""\n{3,}""")
    private val spaceBeforePunctRegex = Regex("""\s+([,.;:!?])""")
    private val repeatedPunctRegex = Regex("""([!?.])\1{2,}""")
    private val emptyParensRegex = Regex("""\(\s*\)|\[\s*]""")

    private const val SENTENCE_END = ".!?:;,…\"'”’)]»"

    fun clean(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw.replace("\r\n", "\n").replace('\r', '\n')
        s = urlRegex.replace(s, " ")
        s = bareDomainRegex.replace(s, " ")
        s = stripEmoji(s)
        s = hashtagRegex.replace(s) { it.groupValues[1].replace('_', ' ') }
        s = mentionRegex.replace(s) { it.groupValues[1].replace('_', ' ') }
        s = separatorRunRegex.replace(s, "\n")
        s = leadingBulletRegex.replace(s, "")
        s = emptyParensRegex.replace(s, " ")
        s = spacesRegex.replace(s, " ")
        s = repeatedPunctRegex.replace(s, "$1")

        val lines = s.split('\n').map { it.trim() }
        val out = StringBuilder()
        for (line in lines) {
            if (line.isEmpty()) {
                if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append('\n')
                continue
            }
            if (!line.any { it.isLetterOrDigit() }) continue
            if (out.isNotEmpty() && !out.endsWith("\n")) out.append('\n')
            out.append(line)
            if (line.last() !in SENTENCE_END) out.append('.')
        }
        s = out.toString()
        s = spaceBeforePunctRegex.replace(s, "$1")
        s = blankLinesRegex.replace(s, "\n\n")
        return s.trim()
    }

    /** Removes emoji, pictographs, flags, keycap and variation selectors, and box drawing. */
    fun stripEmoji(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val n = Character.charCount(cp)
            if (!isEmojiLike(cp)) {
                sb.appendCodePoint(cp)
            } else if (sb.isNotEmpty() && !sb.last().isWhitespace() && isSpacingSymbol(cp)) {
                sb.append(' ')
            }
            i += n
        }
        return sb.toString()
    }

    private fun isSpacingSymbol(cp: Int) = cp != 0x200D && cp !in 0xFE00..0xFE0F && cp != 0x20E3 &&
        cp !in 0x1F3FB..0x1F3FF && cp !in 0xE0020..0xE007F

    fun isEmojiLike(cp: Int): Boolean = when {
        cp in 0x1F000..0x1FAFF -> true // pictographs, emoticons, transport, flags, symbols ext
        cp in 0x2600..0x27BF -> true // misc symbols, dingbats
        cp in 0x2B00..0x2BFF -> true // misc symbols and arrows
        cp in 0x2190..0x21FF -> true // arrows
        cp in 0x2300..0x23FF -> true // misc technical (watch, hourglass, play buttons)
        cp in 0x2500..0x25FF -> true // box drawing, blocks, geometric shapes
        cp in 0xFE00..0xFE0F -> true // variation selectors
        cp in 0xE0020..0xE007F -> true // tag characters (subdivision flags)
        cp == 0x200D || cp == 0x20E3 -> true // ZWJ, keycap
        cp == 0x00A9 || cp == 0x00AE || cp == 0x2122 || cp == 0x2139 -> true
        cp == 0x24C2 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299 -> true
        else -> false
    }
}

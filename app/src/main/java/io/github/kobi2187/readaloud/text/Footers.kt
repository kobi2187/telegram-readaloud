package io.github.kobi2187.readaloud.text

/**
 * Channel signatures: a closing paragraph (or line) that repeats at the end of several posts,
 * such as "Join our channel" or "Kan News – reliable updates", is not read aloud.
 */
object Footers {
    /** Posts that must end with the same text for it to count as a signature. */
    const val MIN_REPEATS = 3

    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val SPACES = Regex("\\s+")

    private fun norm(s: String) = s.replace(SPACES, " ").trim().lowercase()

    /** The closing paragraph and the closing line of [speech] (normalized), when the post has more before them. */
    private fun endings(speech: String): Set<String> {
        val text = speech.trim()
        val out = HashSet<String>()
        val paragraphs = text.split(PARAGRAPH_BREAK)
        if (paragraphs.size > 1) out += norm(paragraphs.last())
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.size > 1) out += norm(lines.last())
        out.removeAll { it.length < 3 }
        return out
    }

    /**
     * The signatures among a channel's posts: endings shared by at least [MIN_REPEATS] posts. A
     * signature above another one ("Join us" over "Follow @news") is found once the lower one is set aside.
     */
    fun detect(speeches: Iterable<String>): Set<String> {
        val found = HashSet<String>()
        var texts = speeches.toList()
        repeat(4) {
            val counts = HashMap<String, Int>()
            for (s in texts) for (e in endings(s)) counts[e] = (counts[e] ?: 0) + 1
            val fresh = counts.filter { it.value >= MIN_REPEATS && it.key !in found }.keys
            if (fresh.isEmpty()) return found
            found += fresh
            texts = texts.map { strip(it, found) }
        }
        return found
    }

    /** [speech] without the signatures it ends with; a post that is nothing but a signature is kept. */
    fun strip(speech: String, footers: Set<String>): String {
        if (footers.isEmpty()) return speech
        var text = speech.trim()
        while (true) {
            val paragraphs = text.split(PARAGRAPH_BREAK)
            if (paragraphs.size > 1 && norm(paragraphs.last()) in footers) {
                text = paragraphs.dropLast(1).joinToString("\n\n").trim()
                continue
            }
            val lines = text.split('\n').dropLastWhile { it.isBlank() }
            if (lines.count { it.isNotBlank() } > 1 && norm(lines.last()) in footers) {
                text = lines.dropLast(1).joinToString("\n").trim()
                continue
            }
            return text
        }
    }
}

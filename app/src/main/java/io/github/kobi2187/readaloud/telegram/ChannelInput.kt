package io.github.kobi2187.readaloud.telegram

object ChannelInput {
    private val username = Regex("^[A-Za-z][A-Za-z0-9_]{3,31}$")
    private val linkRegex = Regex(
        """(?i)(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me|telegram\.dog)/(?:s/)?([A-Za-z][A-Za-z0-9_]{3,31})""",
    )

    /**
     * Accepts "@name", "name", "t.me/name", "https://t.me/s/name/123" or text that contains such
     * a link (e.g. shared from Telegram) and returns the lowercase username, or null.
     */
    fun parse(input: String): String? {
        val trimmed = input.trim()
        linkRegex.find(trimmed)?.let { m ->
            val name = m.groupValues[1]
            if (name.lowercase() !in RESERVED) return name.lowercase()
        }
        val bare = trimmed.removePrefix("@").trim()
        return if (username.matches(bare)) bare.lowercase() else null
    }

    private val RESERVED = setOf("joinchat", "addstickers", "share", "proxy", "socks", "addlist", "boost", "iv")
}

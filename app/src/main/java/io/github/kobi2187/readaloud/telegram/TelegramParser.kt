package io.github.kobi2187.readaloud.telegram

import io.github.kobi2187.readaloud.text.SpeechText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.time.OffsetDateTime

data class TgMessage(
    val id: Int,
    val dateMillis: Long,
    /** Readable text for the screen (keeps links and emoji). */
    val text: String,
    /** Text prepared for speech; empty when there is nothing worth reading. */
    val speech: String,
    /** Comma-separated media kinds such as "photo,video"; null for text-only posts. */
    val media: String?,
    val isService: Boolean,
    val edited: Boolean,
    val forwardedFrom: String?,
)

data class TgPage(
    val username: String,
    val title: String?,
    val description: String?,
    val avatarUrl: String?,
    /** Messages in ascending id order, including service messages (needed for paging). */
    val messages: List<TgMessage>,
    /** Value for `?before=` to load older posts, when there are any. */
    val prevBefore: Int?,
    /** Value for `?after=` to load newer posts, when there are any. */
    val nextAfter: Int?,
) {
    val minId: Int? get() = messages.minOfOrNull { it.id }
    val maxId: Int? get() = messages.maxOfOrNull { it.id }
}

/** Parses the public web preview at https://t.me/s/<channel>. */
object TelegramParser {

    private val beforeRegex = Regex("""[?&]before=(\d+)""")
    private val afterRegex = Regex("""[?&]after=(\d+)""")

    fun parse(html: String, username: String): TgPage {
        val doc = Jsoup.parse(html, "https://t.me/s/$username")

        val title = doc.selectFirst(".tgme_channel_info_header_title")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
        val description = doc.selectFirst(".tgme_channel_info_description")?.let { htmlToText(it) }
            ?.takeIf { it.isNotBlank() }
        val avatar = doc.selectFirst(".tgme_page_photo_image img")?.absUrl("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") }

        val messages = doc.select("div.tgme_widget_message[data-post]").mapNotNull { parseMessage(it) }
            .distinctBy { it.id }
            .sortedBy { it.id }

        val prev = doc.selectFirst("link[rel=prev]")?.attr("href")?.let { beforeRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val next = doc.selectFirst("link[rel=next]")?.attr("href")?.let { afterRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }

        return TgPage(username, title, description, avatar, messages, prev, next)
    }

    private fun parseMessage(el: Element): TgMessage? {
        val post = el.attr("data-post")
        val id = post.substringAfterLast('/').toIntOrNull() ?: return null
        val datetime = el.selectFirst(".tgme_widget_message_date time[datetime]")?.attr("datetime")
            ?: el.selectFirst("time[datetime]")?.attr("datetime")
            ?: return null
        val date = runCatching { OffsetDateTime.parse(datetime).toInstant().toEpochMilli() }.getOrNull() ?: return null
        val isService = el.hasClass("service_message")

        val textEl = el.select(".js-message_text").firstOrNull { e ->
            e.parents().none { it.hasClass("tgme_widget_message_reply") }
        }
        var text = textEl?.let { htmlToText(it) }.orEmpty()
        var speech = SpeechText.clean(text)

        val poll = el.selectFirst(".tgme_widget_message_poll")
        if (poll != null) {
            val question = poll.selectFirst(".tgme_widget_message_poll_question")?.text().orEmpty()
            val options = poll.select(".tgme_widget_message_poll_option_text").map { it.text() }
            val pollText = buildString {
                append(question)
                options.forEach { append("\n• ").append(it) }
            }
            text = if (text.isBlank()) pollText else "$text\n\n$pollText"
            speech = SpeechText.clean(listOf(speech, question, options.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n"))
        }

        if (speech.isBlank()) {
            // A post that is only a link: read the link preview's title instead.
            val previewTitle = el.selectFirst(".tgme_widget_message_link_preview .link_preview_title")?.text()
            if (!previewTitle.isNullOrBlank()) speech = SpeechText.clean(previewTitle)
        }

        val media = buildList {
            if (el.selectFirst(".tgme_widget_message_photo_wrap") != null) add("photo")
            if (el.selectFirst(".tgme_widget_message_video_player, .tgme_widget_message_roundvideo_player") != null) add("video")
            if (el.selectFirst(".tgme_widget_message_voice") != null) add("voice")
            if (el.selectFirst(".tgme_widget_message_document") != null) add("file")
            if (el.selectFirst(".tgme_widget_message_sticker_wrap, .tgme_widget_message_sticker") != null) add("sticker")
            if (el.selectFirst(".tgme_widget_message_location_wrap") != null) add("location")
            if (poll != null) add("poll")
        }.distinct().joinToString(",").ifEmpty { null }

        val edited = el.selectFirst(".tgme_widget_message_meta")?.ownText()?.contains("edited") == true
        val forwarded = el.selectFirst(".tgme_widget_message_forwarded_from_name")?.text()?.takeIf { it.isNotBlank() }

        return TgMessage(
            id = id,
            dateMillis = date,
            text = text,
            speech = if (isService) "" else speech,
            media = media,
            isService = isService,
            edited = edited,
            forwardedFrom = forwarded,
        )
    }

    /** Converts message HTML to plain text, keeping line breaks and emoji. */
    fun htmlToText(root: Element): String {
        val sb = StringBuilder()
        appendNode(root, sb)
        return sb.toString()
            .replace(' ', ' ')
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun appendNode(node: Node, sb: StringBuilder) {
        when (node) {
            is TextNode -> sb.append(node.wholeText)
            is Element -> when (node.tagName().lowercase()) {
                "br" -> sb.append('\n')
                "blockquote", "pre" -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    node.childNodes().forEach { appendNode(it, sb) }
                    if (!sb.endsWith("\n")) sb.append('\n')
                }
                "div" -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.append('\n')
                    node.childNodes().forEach { appendNode(it, sb) }
                }
                "script", "style", "svg" -> Unit
                else -> node.childNodes().forEach { appendNode(it, sb) }
            }
        }
    }
}

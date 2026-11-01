package io.github.kobi2187.readaloud.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class FeedItem(
    /** Stable identity: the guid/id, else the link. */
    val guid: String,
    val link: String?,
    val title: String,
    /** Epoch millis, or null when the feed gives no usable date. */
    val date: Long?,
    /** Description or summary, as plain text. */
    val summary: String,
    /** Full content when the feed carries it (content:encoded, Atom content), as plain text. */
    val content: String,
)

data class Feed(val title: String?, val link: String?, val items: List<FeedItem>)

/** Reads RSS 2.0, RSS 1.0 (RDF) and Atom feeds. */
object FeedParser {

    fun looksLikeFeed(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 1024), Charsets.ISO_8859_1).lowercase()
        return "<rss" in head || "<feed" in head || "<rdf:rdf" in head
    }

    /** Parses [bytes]; the XML declaration decides the encoding (Hebrew feeds may use windows-1255). */
    fun parse(bytes: ByteArray, baseUrl: String = ""): Feed {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, baseUrl, Parser.xmlParser())
        return parse(doc)
    }

    fun parse(doc: Document): Feed {
        val atom = doc.selectFirst("feed") != null && doc.selectFirst("rss, channel") == null
        return if (atom) parseAtom(doc) else parseRss(doc)
    }

    private fun parseRss(doc: Document): Feed {
        val channel = doc.selectFirst("channel")
        val items = doc.getElementsByTag("item").mapNotNull { item ->
            val link = child(item, "link")?.takeIf { it.isNotBlank() }
                ?: item.getElementsByTag("guid").firstOrNull { it.attr("isPermaLink") != "false" }?.text()?.takeIf { it.startsWith("http") }
            val guid = child(item, "guid")?.takeIf { it.isNotBlank() } ?: link ?: return@mapNotNull null
            FeedItem(
                guid = guid.trim(),
                link = link?.trim(),
                title = plain(child(item, "title").orEmpty()),
                date = parseDate(child(item, "pubDate") ?: child(item, "dc:date") ?: child(item, "date")),
                summary = plain(child(item, "description").orEmpty()),
                content = plain(child(item, "content:encoded").orEmpty()),
            )
        }
        return Feed(channel?.let { child(it, "title") }?.let(::plain), channel?.let { child(it, "link") }, items)
    }

    private fun parseAtom(doc: Document): Feed {
        val feed = doc.selectFirst("feed")!!
        val items = feed.getElementsByTag("entry").mapNotNull { entry ->
            val links = entry.getElementsByTag("link")
            val link = (links.firstOrNull { it.attr("rel").ifEmpty { "alternate" } == "alternate" } ?: links.firstOrNull())
                ?.attr("href")?.takeIf { it.isNotBlank() }
            val guid = child(entry, "id")?.takeIf { it.isNotBlank() } ?: link ?: return@mapNotNull null
            FeedItem(
                guid = guid.trim(),
                link = link?.trim(),
                title = plain(child(entry, "title").orEmpty()),
                date = parseDate(child(entry, "published") ?: child(entry, "updated")),
                summary = plain(child(entry, "summary").orEmpty()),
                content = plain(child(entry, "content").orEmpty()),
            )
        }
        val siteLink = feed.children().firstOrNull { it.tagName() == "link" && it.attr("rel").ifEmpty { "alternate" } == "alternate" }?.attr("href")
        return Feed(child(feed, "title")?.let(::plain), siteLink, items)
    }

    /** Text of a direct child element (feeds nest items, so a deep search would be wrong). */
    private fun child(parent: Element, tag: String): String? =
        parent.children().firstOrNull { it.tagName().equals(tag, ignoreCase = true) }?.let { el ->
            // Feeds often put escaped HTML or CDATA in text nodes; keep it for plain() to strip.
            el.wholeText().ifBlank { el.text() }
        }

    /** HTML (escaped or not) to plain text with paragraph breaks. */
    fun plain(htmlOrText: String): String {
        val s = htmlOrText.trim()
        if (s.isEmpty()) return ""
        if ('<' !in s && '&' !in s) return s
        val body = Jsoup.parseBodyFragment(s).body()
        return HtmlText.blocks(body).joinToString("\n\n")
    }

    private val rfc822 = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH),
    )

    fun parseDate(raw: String?): Long? {
        val s = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (s.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(s).toInstant().toEpochMilli() }
        for (f in rfc822) runCatching { return ZonedDateTime.parse(s, f).toInstant().toEpochMilli() }
        // "2026-11-01 10:00:00" style, taken as UTC.
        runCatching { return java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        return null
    }
}

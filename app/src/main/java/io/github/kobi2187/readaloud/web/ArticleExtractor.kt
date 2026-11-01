package io.github.kobi2187.readaloud.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class Article(
    val title: String,
    /** Paragraphs separated by blank lines. */
    val text: String,
    /** Only a teaser was available (paywall, or the page could not be read). */
    val summaryOnly: Boolean,
)

/**
 * Pulls the readable article out of a news page: the publisher's own structured data
 * (schema.org `articleBody`) when present, otherwise the block of the page with the most
 * paragraph text, the way browser reader modes work.
 */
object ArticleExtractor {
    private const val MIN_ARTICLE = 300
    private const val PAYWALL_FULL_TEXT = 1500

    fun extract(doc: Document): Article {
        val ld = jsonLdArticles(doc)
        val title = ld.firstNotNullOfOrNull { it.string("headline") }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val cleanTitle = title.replace(Regex("\\s+[|–—-]\\s+[^|–—-]{1,30}$"), "").trim()
        val paywalled = ld.any { (it["isAccessibleForFree"] as? JsonPrimitive)?.let { p -> p.booleanOrNull == false || p.contentOrNull == "False" } == true }
        val description = ld.firstNotNullOfOrNull { it.string("description") }
            ?: doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content").orEmpty()

        val fromLd = ld.mapNotNull { it.string("articleBody") }.maxByOrNull { it.length }?.let(::paragraphs).orEmpty()
        val fromPage = if (fromLd.length >= MIN_ARTICLE && !paywalled) "" else readerText(doc)
        val body = listOf(fromLd, fromPage).maxBy { it.length }
        return when {
            // Metered paywalls still serve the whole article to every visitor; hard ones only a teaser.
            paywalled && fromPage.length >= PAYWALL_FULL_TEXT && fromPage.split("\n\n").size >= 5 ->
                Article(cleanTitle, fromPage, summaryOnly = false)
            paywalled -> Article(cleanTitle, listOf(description, fromLd).maxBy { it.length }.trim(), summaryOnly = true)
            body.length >= MIN_ARTICLE -> Article(cleanTitle, body, summaryOnly = false)
            else -> Article(cleanTitle, listOf(body, description).maxBy { it.length }.trim(), summaryOnly = true)
        }
    }

    /** NewsArticle / Article objects from JSON-LD, including inside @graph arrays. */
    private fun jsonLdArticles(doc: Document): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        fun visit(e: JsonElement) {
            when (e) {
                is JsonArray -> e.forEach(::visit)
                is JsonObject -> {
                    val type = e["@type"]
                    val types = when (type) {
                        is JsonArray -> type.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        is JsonPrimitive -> listOfNotNull(type.contentOrNull)
                        else -> emptyList()
                    }
                    if (types.any { it.endsWith("Article") || it == "BlogPosting" || it == "Report" }) out += e
                    e["@graph"]?.let(::visit)
                }
                else -> Unit
            }
        }
        for (script in doc.select("script[type=application/ld+json]")) {
            runCatching { Json { isLenient = true }.parseToJsonElement(script.data()) }.getOrNull()?.let(::visit)
        }
        return out
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let(FeedParser::plain)?.takeIf { it.isNotBlank() }

    /** Text that may contain HTML or bare newlines, as paragraphs. */
    private fun paragraphs(text: String): String =
        FeedParser.plain(text).split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

    private val noise = Regex(
        "comment|footer|header|menu|nav|related|share|social|sidebar|promo|advert|banner|newsletter|subscribe|recommend|outbrain|taboola|breadcrumb|tags|author-bio|cookie",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Reader-mode heuristic: the container whose own paragraphs hold the most text. Blocks whose
     * class names look like menus or promos are skipped, unless that leaves too little.
     */
    fun readerText(doc: Document): String =
        readerText(doc, skipNoise = true).takeIf { it.length >= MIN_ARTICLE } ?: readerText(doc, skipNoise = false)

    private fun readerText(doc: Document, skipNoise: Boolean): String {
        val body = doc.body() ?: return ""
        val clone = body.clone()
        clone.select("script, style, noscript, nav, header, footer, aside, iframe, svg, figure, button, [aria-hidden=true]").remove()
        // ASP.NET sites wrap the whole page in a form; only small forms (search, newsletter) are noise.
        clone.select("form").filter { it.text().length < 500 }.forEach { it.remove() }
        if (skipNoise) clone.select("[class], [id]").filter { el ->
            val key = el.className() + " " + el.id()
            el.tagName() != "article" && el.tagName() != "main" && el.tagName() != "body" && noise.containsMatchIn(key) && el.text().length < 2000
        }.forEach { it.remove() }

        val scores = HashMap<Element, Int>()
        for (p in clone.select("p")) {
            val len = p.text().trim().length
            if (len < 40) continue
            val parent = p.parent() ?: continue
            scores[parent] = (scores[parent] ?: 0) + len
            parent.parent()?.let { gp -> scores[gp] = (scores[gp] ?: 0) + len / 2 }
        }
        val best = scores.maxByOrNull { it.value }?.key
            ?: clone.selectFirst("article, [itemprop=articleBody], main")
            ?: return ""
        return best.select("p, h2, h3, li").map { it.text().trim() }
            .filter { it.length >= 25 || it.endsWith(":") }
            .distinct()
            .joinToString("\n\n")
    }
}

/** How an article is shown and spoken in a post. */
object ArticleText {
    fun display(title: String, article: Article?): String =
        listOf(title, article?.text.orEmpty()).filter { it.isNotBlank() }.distinct().joinToString("\n\n")

    fun speech(title: String, article: Article?): String {
        val heading = title.trim().let { if (it.isEmpty() || it.last() in ".!?:") it else "$it." }
        val body = article?.text.orEmpty().takeIf { it.trim() != title.trim() }.orEmpty()
        val note = if (article?.summaryOnly == true && body.isNotBlank()) {
            if ((title + body).any { it in 'א'..'ת' }) "זהו תקציר בלבד." else "This is only a summary."
        } else {
            ""
        }
        return io.github.kobi2187.readaloud.text.SpeechText.clean(listOf(heading, body, note).filter { it.isNotBlank() }.joinToString("\n\n"))
    }
}

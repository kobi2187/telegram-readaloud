package io.github.kobi2187.readaloud.web

import io.github.kobi2187.readaloud.net.await
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.IOException

class SiteRefusedException(val url: String, val code: Int) : IOException("$url refused the request (HTTP $code)")
class NoFeedException(val url: String) : IOException("No news feed found at $url")

data class DiscoveredFeed(val url: String, val title: String?, val feed: Feed)

/** Fetches news feeds and article pages from ordinary websites. */
class WebClient(private val http: OkHttpClient) {

    private suspend fun get(url: HttpUrl): Pair<HttpUrl, ByteArray> {
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "he,en;q=0.8")
            .build()
        http.newCall(request).await().use { response ->
            if (response.code == 401 || response.code == 403 || response.code == 429) throw SiteRefusedException(url.toString(), response.code)
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val bytes = response.body?.bytes() ?: throw IOException("Empty response for $url")
            return response.request.url to bytes
        }
    }

    suspend fun fetchFeed(url: String): Feed {
        val u = url.toHttpUrlOrNull() ?: throw NoFeedException(url)
        val (finalUrl, bytes) = get(u)
        if (!FeedParser.looksLikeFeed(bytes)) throw NoFeedException(url)
        return FeedParser.parse(bytes, finalUrl.toString())
    }

    /**
     * Finds the news feed behind what the user typed: a feed URL, or a site whose page links its
     * feed (`<link rel="alternate" type="application/rss+xml">`), or a feed at a common path.
     */
    suspend fun discover(input: String): DiscoveredFeed {
        val url = normalize(input) ?: throw NoFeedException(input)
        val (finalUrl, bytes) = get(url)
        if (FeedParser.looksLikeFeed(bytes)) {
            val feed = FeedParser.parse(bytes, finalUrl.toString())
            return DiscoveredFeed(finalUrl.toString(), feed.title, feed)
        }
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, finalUrl.toString())
        val linked = doc.select("link[rel=alternate][type~=(rss|atom)\\+xml]").map { it.absUrl("href") }.filter { it.isNotBlank() }
        val guesses = listOf("feed", "rss", "rss.xml", "feed.xml", "atom.xml", "index.xml").mapNotNull { finalUrl.resolve("/$it")?.toString() }
        for (candidate in (linked + guesses).distinct()) {
            val feed = runCatching { fetchFeed(candidate) }.getOrNull() ?: continue
            if (feed.items.isEmpty()) continue
            return DiscoveredFeed(candidate, feed.title ?: doc.title().takeIf { it.isNotBlank() }, feed)
        }
        throw NoFeedException(input)
    }

    suspend fun fetchArticle(url: String): Article {
        val u = url.toHttpUrlOrNull() ?: throw IOException("Bad link $url")
        val (finalUrl, bytes) = get(u)
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, finalUrl.toString())
        return ArticleExtractor.extract(doc)
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

        /** "ynet.co.il", "www.bbc.com/news" or a full URL, as an https URL. */
        fun normalize(input: String): HttpUrl? {
            val t = input.trim().substringBefore(' ')
            if (t.isEmpty() || (!t.contains('.') && !t.startsWith("http"))) return null
            return (if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t").toHttpUrlOrNull()
        }

        /** The first web link in shared text ("Look at this https://…"). */
        fun firstUrl(text: String): String? = Regex("""https?://[^\s<>"']+""").find(text)?.value?.trimEnd('.', ',', ')', ']', '»', '"')
    }
}

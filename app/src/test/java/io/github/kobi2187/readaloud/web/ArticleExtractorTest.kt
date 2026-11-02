package io.github.kobi2187.readaloud.web

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ArticleExtractorTest {
    private val para = "זוהי פסקה ארוכה מספיק כדי להיחשב חלק מגוף הכתבה, עם כמה מילים נוספות לאורך."

    @Test
    fun prefersThePublishersArticleBody() {
        val body = (1..6).joinToString("\\n") { "$para $it" }
        val html = """<html><head><title>Site | Title</title>
            <script type="application/ld+json">{"@context":"https://schema.org","@graph":[
              {"@type":"WebPage","name":"x"},
              {"@type":["NewsArticle"],"headline":"הכותרת","description":"תקציר","articleBody":"$body"}]}</script>
            </head><body><nav>תפריט</nav><p>Unrelated footer text that is long enough to count as a paragraph.</p></body></html>"""
        val a = ArticleExtractor.extract(Jsoup.parse(html))
        assertEquals("הכותרת", a.title)
        assertFalse(a.summaryOnly)
        assertEquals(6, a.text.split("\n\n").size)
        assertTrue(a.text.startsWith(para))
    }

    @Test
    fun readerModeFindsTheMainText() {
        val paragraphs = (1..5).joinToString("") { "<p>$para $it</p>" }
        val html = """<html><head><meta property="og:title" content="Story title"></head><body>
            <header><p>Header text that is quite long and should never be part of the article.</p></header>
            <div class="sidebar related"><p>Related story with a long enough teaser sentence to count.</p></div>
            <div class="story"><h1>Story title</h1>$paragraphs<p>short</p></div>
            <footer><p>Copyright notice that is long enough to look like a paragraph here.</p></footer>
            </body></html>"""
        val a = ArticleExtractor.extract(Jsoup.parse(html))
        assertEquals("Story title", a.title)
        assertFalse(a.summaryOnly)
        assertEquals(5, a.text.split("\n\n").size)
        assertFalse(a.text.contains("Header") || a.text.contains("Related") || a.text.contains("Copyright"))
    }

    @Test
    fun paywalledArticlesAreSummaries() {
        val html = """<html><head><meta property="og:description" content="Teaser of the story.">
            <script type="application/ld+json">{"@type":"NewsArticle","headline":"Premium","isAccessibleForFree":false,
              "articleBody":"Only the first lines."}</script></head>
            <body><div><p>${para.repeat(3)}</p><p>${para.repeat(3)}</p></div></body></html>"""
        val a = ArticleExtractor.extract(Jsoup.parse(html))
        assertTrue(a.summaryOnly)
        assertEquals("Only the first lines.", a.text)
        val speech = ArticleText.speech(a.title, a)
        assertTrue(speech, speech.startsWith("Premium.") && speech.endsWith("This is only a summary."))
    }

    @Test
    fun pagesWithoutTextFallBackToTheDescription() {
        val html = """<html><head><meta name="description" content="What the page is about."></head><body><div id="app"></div></body></html>"""
        val a = ArticleExtractor.extract(Jsoup.parse(html))
        assertTrue(a.summaryOnly)
        assertEquals("What the page is about.", a.text)
    }

    /** Real pages saved from news sites (set NEWS_FIXTURES); prints what was extracted. */
    @Test
    fun realNewsPages() {
        val dir = File(System.getenv("NEWS_FIXTURES").orEmpty())
        assumeTrue(dir.isDirectory)
        for (f in dir.listFiles { it -> it.name.endsWith(".html") }!!.sorted()) {
            val a = ArticleExtractor.extract(Jsoup.parse(f, null))
            println("${f.name}: ${a.title.take(60)} | ${a.text.length} chars | summaryOnly=${a.summaryOnly}\n   ${a.text.take(160).replace("\n", " / ")}")
            assertTrue(f.name, a.text.length > 100)
        }
    }
}

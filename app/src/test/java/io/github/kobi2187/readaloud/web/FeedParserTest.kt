package io.github.kobi2187.readaloud.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private val rss = """<?xml version="1.0" encoding="utf-8"?>
        <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/">
          <channel>
            <title>חדשות ynet</title><link>https://www.ynet.co.il</link>
            <item>
              <title><![CDATA[ראש הממשלה נפגש עם שר הביטחון]]></title>
              <link>https://www.ynet.co.il/news/article/abc</link>
              <guid isPermaLink="false">abc-1</guid>
              <pubDate>Sun, 01 Nov 2026 10:00:00 +0200</pubDate>
              <description><![CDATA[<div><img src="x.jpg"/>הפגישה נמשכה <b>שעתיים</b>.</div>]]></description>
            </item>
            <item>
              <title>Second &amp; last</title>
              <link>https://www.ynet.co.il/news/article/def</link>
              <pubDate>Sun, 1 Nov 2026 08:30:00 GMT</pubDate>
              <description>&lt;p&gt;Escaped &lt;i&gt;HTML&lt;/i&gt; summary&lt;/p&gt;</description>
              <content:encoded><![CDATA[<p>Paragraph one.</p><p>Paragraph two.</p>]]></content:encoded>
            </item>
          </channel>
        </rss>"""

    @Test
    fun readsRss() {
        val feed = FeedParser.parse(rss.toByteArray())
        assertEquals("חדשות ynet", feed.title)
        assertEquals(2, feed.items.size)
        val a = feed.items[0]
        assertEquals("abc-1", a.guid)
        assertEquals("https://www.ynet.co.il/news/article/abc", a.link)
        assertEquals("ראש הממשלה נפגש עם שר הביטחון", a.title)
        assertEquals(Instant.parse("2026-11-01T08:00:00Z").toEpochMilli(), a.date)
        assertEquals("הפגישה נמשכה שעתיים.", a.summary)
        val b = feed.items[1]
        assertEquals("the link is the identity without a guid", b.link, b.guid)
        assertEquals("Second & last", b.title)
        assertEquals("Escaped HTML summary", b.summary)
        assertEquals("Paragraph one.\n\nParagraph two.", b.content)
    }

    @Test
    fun readsAtom() {
        val atom = """<?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example blog</title>
              <link rel="alternate" href="https://example.com/"/>
              <entry>
                <title type="html">Hello &lt;em&gt;world&lt;/em&gt;</title>
                <link rel="alternate" href="https://example.com/hello"/>
                <id>tag:example.com,2026:1</id>
                <published>2026-10-31T21:15:00+02:00</published>
                <summary>Short.</summary>
                <content type="html">&lt;p&gt;Long text.&lt;/p&gt;</content>
              </entry>
            </feed>"""
        val feed = FeedParser.parse(atom.toByteArray())
        assertEquals("Example blog", feed.title)
        assertEquals("https://example.com/", feed.link)
        val e = feed.items.single()
        assertEquals("tag:example.com,2026:1", e.guid)
        assertEquals("https://example.com/hello", e.link)
        assertEquals("Hello world", e.title)
        assertEquals(Instant.parse("2026-10-31T19:15:00Z").toEpochMilli(), e.date)
        assertEquals("Long text.", e.content)
    }

    @Test
    fun honoursTheDeclaredEncoding() {
        val xml = """<?xml version="1.0" encoding="windows-1255"?><rss><channel><title>שלום</title>
            <item><title>כותרת</title><link>https://x.co.il/1</link></item></channel></rss>"""
        val feed = FeedParser.parse(xml.toByteArray(charset("windows-1255")))
        assertEquals("שלום", feed.title)
        assertEquals("כותרת", feed.items.single().title)
        assertTrue(FeedParser.looksLikeFeed(xml.toByteArray()))
        assertTrue(!FeedParser.looksLikeFeed("<!DOCTYPE html><html><body>".toByteArray()))
    }

    @Test
    fun parsesCommonDateFormats() {
        val expected = Instant.parse("2026-11-01T08:00:00Z").toEpochMilli()
        assertEquals(expected, FeedParser.parseDate("Sun, 01 Nov 2026 10:00:00 +0200"))
        assertEquals(expected, FeedParser.parseDate("Sun, 1 Nov 2026 08:00:00 GMT"))
        assertEquals(expected, FeedParser.parseDate("2026-11-01T08:00:00Z"))
        assertEquals(expected, FeedParser.parseDate("2026-11-01T10:00:00+02:00"))
        assertNull(FeedParser.parseDate("yesterday"))
    }
}

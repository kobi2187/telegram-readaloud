package io.github.kobi2187.readaloud.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Fixtures are real t.me/s pages saved on 2026-10-28. */
class TelegramParserTest {
    private fun fixture(name: String) =
        javaClass.classLoader!!.getResource("telegram/$name")!!.readText()

    @Test
    fun parsesChannelPageAndPaging() {
        val page = TelegramParser.parse(fixture("kann_news_latest.html"), "kann_news")
        assertEquals("חדשות קאן", page.title)
        assertNotNull(page.avatarUrl)
        assertEquals(19, page.messages.size)
        assertEquals(3769, page.minId)
        assertEquals(3788, page.maxId)
        assertEquals(3769, page.prevBefore)
        assertNull(page.nextAfter)
        assertEquals(page.messages.map { it.id }.sorted(), page.messages.map { it.id })
    }

    @Test
    fun keepsLinksOnScreenButNotInSpeech() {
        val page = TelegramParser.parse(fixture("kann_news_latest.html"), "kann_news")
        val m = page.messages.first { it.id == 3786 }
        assertEquals(Instant.parse("2026-03-24T15:26:58Z").toEpochMilli(), m.dateMillis)
        assertTrue(m.text.startsWith("הדיל הסודי של טראמפ"))
        assertTrue(m.text.contains("youtube.com"))
        assertTrue(m.speech.startsWith("הדיל הסודי של טראמפ"))
        assertFalse(m.speech.contains("youtube"))
        assertFalse(m.speech.contains("http"))
    }

    @Test
    fun photoWithoutCaptionHasNothingToRead() {
        val page = TelegramParser.parse(fixture("kann_news_latest.html"), "kann_news")
        val m = page.messages.first { it.id == 3788 }
        assertEquals("photo", m.media)
        assertEquals("", m.speech)
    }

    @Test
    fun ignoresQuotedReplyAndDetectsEdits() {
        val page = TelegramParser.parse(fixture("abualiexpress_latest.html"), "abualiexpress")
        val m = page.messages.first { it.id == 132689 }
        assertTrue(m.text, m.text.startsWith("חצי שעה לסגירת הקלפיות."))
        assertFalse(m.text.contains("הציר השיעי"))
        assertTrue(m.edited)
        assertEquals("video", page.messages.first { it.id == 132688 }.media)
    }

    @Test
    fun stripsEmojiFromSpeech() {
        val page = TelegramParser.parse(fixture("durov_latest.html"), "durov")
        val designers = page.messages.first { it.id == 552 }
        assertTrue(designers.text.contains("✨"))
        assertFalse(designers.speech.contains("✨"))
        assertTrue(designers.speech.startsWith("Designers × AI"))
        assertEquals("video", designers.media)
        val birthday = page.messages.first { it.id == 541 }
        assertTrue(birthday.speech, birthday.speech.startsWith("13 Telegram turns 13 today"))
        assertEquals("", page.messages.first { it.id == 536 }.speech) // only flag and emoji
    }

    @Test
    fun marksServiceMessages() {
        val page = TelegramParser.parse(fixture("kann_news_before_20.html"), "kann_news")
        val created = page.messages.first { it.id == 1 }
        assertTrue(created.isService)
        assertEquals("", created.speech)
        assertEquals(18, page.nextAfter)
        assertFalse(page.messages.first { it.id == 4 }.isService)
    }

    @Test
    fun linkOnlyPostReadsPreviewTitle() {
        val html = """
            <div class="tgme_widget_message" data-post="x/5"><div class="tgme_widget_message_bubble">
            <div class="tgme_widget_message_text js-message_text"><a href="https://a.co/b">https://a.co/b</a></div>
            <a class="tgme_widget_message_link_preview"><div class="link_preview_title">Big news today</div></a>
            <a class="tgme_widget_message_date"><time datetime="2026-01-01T10:00:00+00:00"></time></a>
            </div></div>
        """.trimIndent()
        val m = TelegramParser.parse(html, "x").messages.single()
        assertEquals("Big news today.", m.speech)
    }

    @Test
    fun readsPolls() {
        val html = """
            <div class="tgme_widget_message" data-post="x/7"><div class="tgme_widget_message_bubble">
            <div class="tgme_widget_message_poll"><div class="tgme_widget_message_poll_question">Who will win?</div>
            <div class="tgme_widget_message_poll_option_text">Alice</div><div class="tgme_widget_message_poll_option_text">Bob</div></div>
            <a class="tgme_widget_message_date"><time datetime="2026-01-01T10:00:00+00:00"></time></a>
            </div></div>
        """.trimIndent()
        val m = TelegramParser.parse(html, "x").messages.single()
        assertEquals("poll", m.media)
        assertEquals("Who will win?\nAlice.\nBob.", m.speech)
    }
}

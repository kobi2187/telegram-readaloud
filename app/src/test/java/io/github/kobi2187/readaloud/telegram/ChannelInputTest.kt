package io.github.kobi2187.readaloud.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChannelInputTest {
    @Test
    fun acceptsCommonForms() {
        assertEquals("kann_news", ChannelInput.parse("kann_news"))
        assertEquals("kann_news", ChannelInput.parse("@Kann_News"))
        assertEquals("kann_news", ChannelInput.parse("https://t.me/kann_news"))
        assertEquals("kann_news", ChannelInput.parse("t.me/s/kann_news"))
        assertEquals("kann_news", ChannelInput.parse("https://t.me/kann_news/3786"))
        assertEquals("durov", ChannelInput.parse("Check this out: https://t.me/durov/552 cool"))
    }

    @Test
    fun rejectsInvalid() {
        assertNull(ChannelInput.parse("ab"))
        assertNull(ChannelInput.parse("https://t.me/joinchat/AAAA"))
        assertNull(ChannelInput.parse("hello world"))
        assertNull(ChannelInput.parse(""))
    }
}

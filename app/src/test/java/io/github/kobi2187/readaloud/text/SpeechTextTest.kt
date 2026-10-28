package io.github.kobi2187.readaloud.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {
    @Test fun removesEmoji() = assertEquals("Hello world.", SpeechText.clean("Hello 🔥 world"))
    @Test fun keepsWordsApartWhenEmojiSeparatesThem() = assertEquals("Done Next.", SpeechText.clean("Done✅Next"))
    @Test fun keycapDigits() = assertEquals("13 years.", SpeechText.clean("1️⃣3️⃣ years"))
    @Test fun flagsAndZwjSequences() = assertEquals("Team.", SpeechText.clean("Team 👨‍👩‍👧‍👦🇮🇱"))
    @Test fun removesUrls() = assertEquals("Read more:", SpeechText.clean("Read more: https://www.ynet.co.il/news/article/abc?x=1"))
    @Test fun removesBareDomains() = assertEquals("Full story on.", SpeechText.clean("Full story on ynet.co.il"))
    @Test fun hashtagsAndMentions() = assertEquals("breaking news via kann news.", SpeechText.clean("#breaking_news via @kann_news"))
    @Test fun addsSentenceEnds() = assertEquals("Line one.\nLine two!", SpeechText.clean("Line one\nLine two!"))
    @Test fun dropsSeparatorLines() = assertEquals("Title.\nBody.", SpeechText.clean("Title\n——————\nBody"))
    @Test fun dropsBullets() = assertEquals("first.\nsecond.", SpeechText.clean("• first\n▪️ second"))
    @Test fun collapsesRepeatedPunctuation() = assertEquals("Wow!", SpeechText.clean("Wow!!!!"))
    @Test fun hebrew() = assertEquals("פסח הוא חג החירות - אבל לא של כולם.", SpeechText.clean("פסח הוא חג החירות - אבל לא של כולם 😥"))
    @Test fun emptyWhenOnlySymbols() = assertEquals("", SpeechText.clean("🇦🇫😈\n———"))
    @Test fun keepsParagraphBreaks() = assertEquals("One.\n\nTwo.", SpeechText.clean("One\n\n\n\nTwo"))
}

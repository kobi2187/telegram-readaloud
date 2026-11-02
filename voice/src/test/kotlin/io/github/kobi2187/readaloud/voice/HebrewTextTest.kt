package io.github.kobi2187.readaloud.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class HebrewTextTest {
    private fun n(x: Long) = HebrewText.number(x)

    @Test
    fun cardinals() {
        assertEquals("אפס", n(0))
        assertEquals("שבע", n(7))
        assertEquals("שתים עשרה", n(12))
        assertEquals("עשרים", n(20))
        assertEquals("שישים ושתיים", n(62))
        assertEquals("מאה", n(100))
        assertEquals("מאה עשרים ואחת", n(121))
        assertEquals("מאתיים וחמש", n(205))
        assertEquals("אלף", n(1000))
        assertEquals("אלף וחמש מאות", n(1500))
        assertEquals("אלפיים עשרים ושש", n(2026))
        assertEquals("שלושת אלפים", n(3000))
        assertEquals("עשרים ואחד אלף", n(21_000))
        assertEquals("מאה עשרים וחמישה אלף", n(125_000))
        assertEquals("מיליון וחמש מאות אלף", n(1_500_000))
        assertEquals("שני מיליון", n(2_000_000))
        assertEquals("שלושה מיליארד", n(3_000_000_000))
    }

    private fun norm(s: String) = HebrewText.normalize(s)

    @Test
    fun percentagesCurrenciesAndDecimals() {
        assertEquals("שיעור ההצבעה: שישים ושתיים אחוז", norm("שיעור ההצבעה: 62%"))
        assertEquals("חמישים ואחת נקודה שמונה אחוז", norm("51.8%"))
        assertEquals("עלה בשלוש נקודה חמש מיליון", norm("עלה ב-3.5 מיליון"))
        assertEquals("מאה שקלים", norm("100 ₪"))
        assertEquals("חמישים דולר", norm("$50"))
        assertEquals("שקל אחד", norm("1₪"))
        assertEquals("אלף ומאתיים", norm("1,200"))
    }

    @Test
    fun timesDatesRangesAndPrefixes() {
        assertEquals("בשעה עשרים ושתיים", norm("בשעה 22:00"))
        assertEquals("תשע עשרה עשרים ושמונה", norm("19:28"))
        assertEquals("עשרים ושמונה לעשירי", norm("28/10"))
        assertEquals("עשרים ושמונה לעשירי אלפיים עשרים ושש", norm("28.10.2026"))
        assertEquals("עשר עד שתים עשרה מעלות", norm("10-12 מעלות"))
        assertEquals("באלפיים עשרים ושש", norm("ב-2026"))
        assertEquals("השישים", norm("ה־60"))
        assertEquals("טלפון אפס שלוש", norm("טלפון 03"))
    }

    @Test
    fun splitsSentencesAndParagraphs() {
        val s = HebrewText.sentences("שלום. מה נשמע?\n\nפסקה חדשה")
        assertEquals(listOf("שלום." to false, "מה נשמע?" to true, "פסקה חדשה" to true), s)
        val long = ("מילה ".repeat(100)).trim()
        HebrewText.sentences(long).forEach { (chunk, _) -> assert(chunk.length <= 280) }
    }
}

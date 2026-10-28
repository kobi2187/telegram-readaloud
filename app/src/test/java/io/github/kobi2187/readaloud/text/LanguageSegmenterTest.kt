package io.github.kobi2187.readaloud.text

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageSegmenterTest {
    @Test
    fun splitsSentencesByLanguage() {
        val s = LanguageSegmenter.segment("שלום עולם. Hello world. עוד משפט.")
        assertEquals(listOf(Lang.HE, Lang.EN, Lang.HE), s.map { it.lang })
        assertEquals("Hello world.", s[1].text)
    }

    @Test
    fun keepsBrandNamesInsideHebrewSentence() {
        val s = LanguageSegmenter.segment("הודעה מ-Google היום על מוצר חדש.")
        assertEquals(1, s.size)
        assertEquals(Lang.HE, s[0].lang)
    }

    @Test
    fun mergesNeighboursAndAttachesNeutralText() {
        val s = LanguageSegmenter.segment("1.\nחדשות היום.\nעוד חדשות.\n12:30")
        assertEquals(1, s.size)
        assertEquals(Lang.HE, s[0].lang)
        assertEquals("1.\nחדשות היום.\nעוד חדשות.\n12:30", s[0].text)
    }

    @Test
    fun detectsOtherScripts() {
        assertEquals(Lang.AR, LanguageSegmenter.detect("الحمد لله"))
        assertEquals(Lang.RU, LanguageSegmenter.detect("Привет мир"))
        assertEquals(null, LanguageSegmenter.detect("12:30 !!"))
    }

    @Test
    fun numbersOnlyUseFallback() {
        val s = LanguageSegmenter.segment("12:30", fallback = Lang.HE)
        assertEquals(listOf(Segment(Lang.HE, "12:30")), s)
    }
}

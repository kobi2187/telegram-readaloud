package io.github.kobi2187.readaloud.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FootersTest {
    private val signature = "להצטרפות לערוץ כאן חדשות."
    private val posts = listOf(
        "ראש הממשלה נפגש היום עם השר.\n\n$signature",
        "גשם בצפון.\n\n$signature",
        "הבורסה עלתה.\nפרטים בהמשך.\n$signature",
        "הודעה בלי חתימה.",
        "שתי פסקאות.\n\nזו הפסקה האחרונה.",
    )

    @Test
    fun aClosingParagraphOrLineRepeatedInThreePostsIsASignature() {
        val footers = Footers.detect(posts)
        assertEquals(setOf(signature), footers)
        assertEquals("ראש הממשלה נפגש היום עם השר.", Footers.strip(posts[0], footers))
        assertEquals("הבורסה עלתה.\nפרטים בהמשך.", Footers.strip(posts[2], footers))
        assertEquals("an ending seen once is kept", posts[4], Footers.strip(posts[4], footers))
    }

    @Test
    fun twoPostsAreNotEnough() = assertTrue(Footers.detect(posts.take(2)).isEmpty())

    @Test
    fun spacingAndCaseDoNotMatterAndStackedSignaturesAllGo() {
        val footers = Footers.detect(
            listOf(
                "One.\n\nJoin   us\n\nFollow @news",
                "Two.\n\nJOIN US\n\nFollow @news",
                "Three.\n\njoin us\n\nFollow  @news",
            ),
        )
        assertTrue(footers.containsAll(listOf("join us", "follow @news")))
        assertEquals("Four.", Footers.strip("Four.\n\nJoin us\n\nFollow @news", footers))
    }

    @Test
    fun aPostThatIsOnlyTheSignatureIsStillRead() = assertEquals("Join us", Footers.strip("Join us", setOf("join us")))
}

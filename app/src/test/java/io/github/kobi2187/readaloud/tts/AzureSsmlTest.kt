package io.github.kobi2187.readaloud.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class AzureSsmlTest {
    @Test
    fun buildsOneVoiceElementPerLanguageRun() {
        val ssml = AzureTts.buildSsml(
            listOf("he-IL-AvriNeural" to "שלום & ברוכים <הבאים>", "en-US-AndrewNeural" to "It's \"news\""),
            trailingSilenceMs = 700,
        )
        assertEquals(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='he-IL'>" +
                "<voice name='he-IL-AvriNeural'>שלום &amp; ברוכים &lt;הבאים&gt;</voice>" +
                "<voice name='en-US-AndrewNeural'>It&apos;s &quot;news&quot;<break time='700ms'/></voice>" +
                "</speak>",
            ssml,
        )
    }

    @Test
    fun clampsBreakAndCleansControlCharacters() {
        val ssml = AzureTts.buildSsml(listOf("en-US-AvaNeural" to "a\u000Bb"), trailingSilenceMs = 9000)
        assertEquals(
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='en-US-AvaNeural'>a b<break time='5000ms'/></voice></speak>",
            ssml,
        )
    }
}

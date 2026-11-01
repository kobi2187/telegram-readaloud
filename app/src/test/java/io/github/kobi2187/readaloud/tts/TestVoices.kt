package io.github.kobi2187.readaloud.tts

/** No offline voice installed. */
object NoOfflineHebrew : OfflineHebrew {
    override val activeId: String? = null
    override fun synthesize(text: String, voiceId: String?): Pcm? = null
}

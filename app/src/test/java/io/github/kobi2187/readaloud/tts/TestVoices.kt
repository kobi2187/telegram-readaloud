package io.github.kobi2187.readaloud.tts

/** No offline Hebrew voice installed. */
object NoOfflineHebrew : OfflineHebrew {
    override val installed = false
    override fun synthesize(text: String): Pcm? = null
}

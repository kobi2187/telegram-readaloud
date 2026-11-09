package io.github.kobi2187.readaloud.sync

import android.util.Log
import io.github.kobi2187.readaloud.data.ChannelDao
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.MessageDao
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.text.Footers
import io.github.kobi2187.readaloud.text.LanguageSegmenter
import io.github.kobi2187.readaloud.tts.AuthException
import io.github.kobi2187.readaloud.tts.ThrottledException
import io.github.kobi2187.readaloud.web.Translator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps the posts of channels read in another language translated: ahead of time during sync (so
 * they also play offline), or on demand when a post comes up before sync reached it.
 */
class Translations(
    private val messages: MessageDao,
    private val translator: Translator,
    private val settings: SettingsRepository,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var pausedUntil = 0L

    val paused: Boolean get() = now() < pausedUntil

    /**
     * The translation of [m] into [lang], fetching it if needed (blocking); null when it cannot be
     * had now (offline, or Google is limiting requests), so the original is read instead.
     */
    fun ensure(m: MessageEntity, lang: String): String? {
        if (m.translationLang == lang && m.translation != null) return m.translation
        if (m.speech.isBlank()) return null
        // Already written in that language: nothing to ask Google.
        if (LanguageSegmenter.dominant(m.speech)?.name?.lowercase() == lang) {
            messages.setTranslationBlocking(m.channel, m.id, lang, m.speech)
            return m.speech
        }
        if (paused) return null
        return try {
            val t = translator.translate(m.speech, lang, settings.state.value.translateKey)
            val text = if (t.sourceLang == lang || t.text.isBlank()) m.speech else t.text
            messages.setTranslationBlocking(m.channel, m.id, lang, text)
            text
        } catch (e: ThrottledException) {
            pausedUntil = now() + (e.retryAfterSeconds ?: 30 * 60L) * 1000
            Log.w(TAG, "Translation paused: ${e.message}")
            null
        } catch (e: AuthException) {
            pausedUntil = now() + 3600_000L
            Log.w(TAG, "Translation key rejected: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Translation of ${m.channel}/${m.id} failed: ${e.message}")
            null
        }
    }

    /** Translates the next posts of every channel in [channels] that is read in another language. */
    suspend fun translatePending(channels: List<ChannelEntity>, passed: () -> Boolean) = withContext(Dispatchers.IO) {
        for (c in channels) {
            val lang = c.translateTo ?: continue
            for (m in messages.untranslated(c.username, lang, c.cursorId, c.startDate, PER_SYNC)) {
                if (passed() || paused) return@withContext
                ensure(m, lang)
            }
        }
    }

    companion object {
        private const val TAG = "Translations"

        /** Upcoming posts translated per channel and sync. */
        const val PER_SYNC = 60
    }
}

/**
 * What is read aloud for a post: its translation when the channel is read in another language (the
 * original if it cannot be translated now), without the channel's repeated signature. Blocking.
 */
class SpokenText(
    private val messages: MessageDao,
    private val channels: ChannelDao,
    private val translations: Translations?,
) {
    fun of(m: MessageEntity): String {
        val lang = channels.getBlocking(m.channel)?.translateTo
        val text = if (lang != null) translations?.ensure(m, lang) ?: m.speech else m.speech
        val recent = messages.recentWithSpeechBlocking(m.channel, FOOTER_WINDOW)
        return Footers.strip(text, Footers.detect(recent.map { it.speechIn(lang) }))
    }

    companion object {
        /** How many recent posts are compared to find a channel's signature. */
        const val FOOTER_WINDOW = 60
    }
}

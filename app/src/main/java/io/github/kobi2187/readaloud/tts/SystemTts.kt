package io.github.kobi2187.readaloud.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import io.github.kobi2187.readaloud.text.Lang
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Offline fallback: renders text to WAV with the phone's own TTS engine (usually Google's, which
 * has downloadable Hebrew and English voices). All calls block and are serialized.
 */
open class SystemTts(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val engines = ConcurrentHashMap<String, Engine>() // key: engine package or "" for default
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    private inner class Engine(val tts: TextToSpeech, val ok: Boolean)

    private fun engine(packageName: String?): Engine? {
        val key = packageName.orEmpty()
        engines[key]?.let { return it.takeIf { e -> e.ok } }
        val latch = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        var created: TextToSpeech? = null
        main.post {
            created = if (packageName == null) {
                TextToSpeech(appContext) { s -> status = s; latch.countDown() }
            } else {
                TextToSpeech(appContext, { s -> status = s; latch.countDown() }, packageName)
            }
        }
        if (!latch.await(15, TimeUnit.SECONDS)) {
            Log.w(TAG, "TTS engine ${packageName ?: "default"} did not initialize")
            return null
        }
        val tts = created ?: return null
        val engine = Engine(tts, status == TextToSpeech.SUCCESS)
        if (engine.ok) tts.setOnUtteranceProgressListener(progressListener)
        engines[key] = engine
        return engine.takeIf { it.ok }
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) = Unit
        override fun onDone(utteranceId: String) {
            pending.remove(utteranceId)?.countDown()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onError(utteranceId: String) {
            failed += utteranceId
            pending.remove(utteranceId)?.countDown()
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            failed += utteranceId
            pending.remove(utteranceId)?.countDown()
        }
    }

    private fun localesFor(lang: Lang): List<Locale> = when (lang) {
        Lang.HE -> listOf("he-IL", "iw-IL", "he").map(Locale::forLanguageTag)
        Lang.EN -> listOf(Locale.US, Locale.UK, Locale.ENGLISH)
        Lang.AR -> listOf("ar-SA", "ar").map(Locale::forLanguageTag)
        Lang.RU -> listOf("ru-RU", "ru").map(Locale::forLanguageTag)
    }

    /** Selects an engine and locale able to speak [lang], preferring the user's default engine. */
    private fun prepare(lang: Lang): TextToSpeech? {
        val default = engine(null) ?: return null
        if (trySetLanguage(default.tts, lang)) return default.tts
        val others = default.tts.engines.map { it.name }.filter { it != default.tts.defaultEngine }
        for (pkg in others) {
            val e = engine(pkg) ?: continue
            if (trySetLanguage(e.tts, lang)) return e.tts
        }
        return null
    }

    private fun trySetLanguage(tts: TextToSpeech, lang: Lang): Boolean {
        for (locale in localesFor(lang)) {
            val r = runCatching { tts.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            if (r >= TextToSpeech.LANG_AVAILABLE) return true
        }
        return false
    }

    open fun isLanguageAvailable(lang: Lang): Boolean = synchronized(lock) { prepare(lang) != null }

    /**
     * Renders [text] in [lang] and returns the PCM clip; [scratchDir] holds the engine's temporary
     * files. Long text is split and stitched together. Never call from the main thread.
     */
    open fun synthesize(text: String, lang: Lang, scratchDir: File, timeoutMs: Long = 120_000): Wav.Clip = synchronized(lock) {
        val tts = prepare(lang) ?: throw IOException("No device voice for ${lang.tag}")
        val maxLen = (runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000) - 100).coerceAtLeast(500)
        val chunks = chunk(text, maxLen)
        val clips = mutableListOf<Wav.Clip>()
        scratchDir.mkdirs()
        for ((i, part) in chunks.withIndex()) {
            val id = "u${System.nanoTime()}_$i"
            val partFile = File(scratchDir, "tts_$id.wav")
            try {
                val latch = CountDownLatch(1)
                pending[id] = latch
                val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
                val r = tts.synthesizeToFile(part, params, partFile, id)
                if (r != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    throw IOException("Device TTS refused the text")
                }
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    pending.remove(id)
                    throw IOException("Device TTS timed out")
                }
                if (failed.remove(id)) throw IOException("Device TTS failed")
                clips += Wav.read(partFile)
            } finally {
                partFile.delete()
            }
        }
        val format = clips.first().format
        if (clips.any { it.format != format }) throw IOException("Inconsistent device TTS output")
        Wav.Clip(format, clips.fold(ByteArray(0)) { acc, c -> acc + c.pcm })
    }

    fun shutdown() {
        engines.values.forEach { e -> main.post { runCatching { e.tts.shutdown() } } }
        engines.clear()
    }

    private fun chunk(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > maxLen) {
            var cut = rest.lastIndexOf('\n', maxLen)
            if (cut < maxLen / 2) cut = rest.lastIndexOf(". ", maxLen).let { if (it > 0) it + 1 else -1 }
            if (cut < maxLen / 2) cut = rest.lastIndexOf(' ', maxLen)
            if (cut <= 0) cut = maxLen
            out += rest.substring(0, cut).trim()
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out += rest.trim()
        return out.filter { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "SystemTts"
    }
}

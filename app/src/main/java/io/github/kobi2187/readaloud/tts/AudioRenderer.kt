package io.github.kobi2187.readaloud.tts

import android.content.Context
import android.util.Log
import io.github.kobi2187.readaloud.data.AppSettings
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.CloudVoice
import io.github.kobi2187.readaloud.data.MessageDao
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.text.Lang
import io.github.kobi2187.readaloud.text.LanguageSegmenter
import io.github.kobi2187.readaloud.text.Segment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why the online voice is currently not used, for the settings screen. */
enum class CloudStatus { OK, BAD_KEY, QUOTA, THROTTLED }

/** The last known [status] of one online [voice] service. */
data class CloudHealth(val voice: CloudVoice, val status: CloudStatus)

/**
 * Produces the audio file paired with each post. The online voice (Gemini or Azure) is preferred
 * and is pre-downloaded during sync. Without it, posts are voiced on the phone: Hebrew by the
 * downloadable offline neural voice when installed, everything else by the device engine. Every
 * method blocks, so call from a background thread.
 */
class AudioRenderer(
    context: Context,
    private val messages: MessageDao,
    private val settings: SettingsRepository,
    private val gemini: GeminiTts,
    private val azure: AzureTts,
    private val device: SystemTts,
    private val network: NetworkMonitor,
    private val offlineHebrew: OfflineHebrew,
) {
    val audioDir = File(context.filesDir, "audio")
    private val clipsDir = File(audioDir, "_clips")
    private val scratchDir = File(context.cacheDir, "tts")
    private val previewDir = File(scratchDir, "previews")
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private val _cloudHealth = MutableStateFlow(CloudHealth(CloudVoice.NONE, CloudStatus.OK))
    val cloudHealth: StateFlow<CloudHealth> = _cloudHealth.asStateFlow()

    /** After a quota/throttle answer, leave that service alone until this time. */
    private val pausedUntil = ConcurrentHashMap<CloudVoice, Long>()
    private val rejectedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    enum class Prefetch { DONE, NETWORK_FAILED, CLOUD_UNAVAILABLE }

    private class Audio(val bytes: ByteArray, val extension: String)

    private fun <T> locked(key: String, block: () -> T): T =
        locks.getOrPut(key) { ReentrantLock() }.withLock(block)

    private fun messageFile(channel: String, id: Int, extension: String) = File(File(audioDir, channel), "$id.$extension")

    private fun keyId(cfg: AppSettings) = "${cfg.cloudVoice}|${cfg.cloudKey}"

    private fun setStatus(voice: CloudVoice, status: CloudStatus) {
        _cloudHealth.value = CloudHealth(voice, status)
    }

    /** An online voice is configured, its key was not rejected, and we are not backing off. */
    fun cloudReady(cfg: AppSettings): Boolean =
        cfg.cloudEnabled && keyId(cfg) !in rejectedKeys &&
            System.currentTimeMillis() >= (pausedUntil[cfg.cloudVoice] ?: 0L)

    private fun canUseCloudNow(cfg: AppSettings) = cloudReady(cfg) && network.isOnline(cfg.allowMetered)

    /** Which on-phone voice reads [text]: the offline neural one for Hebrew when installed. */
    private fun localVoiceKey(text: String) =
        if (offlineHebrew.installed && text.any { it in 'א'..'ת' }) LOCAL_NEURAL else LOCAL_DEVICE

    /** Device-voiced Hebrew is re-rendered once the offline neural voice is installed. */
    private fun canImproveLocal(m: MessageEntity) =
        m.audioState != AudioState.NEURAL && m.audioVoiceKey != LOCAL_NEURAL && localVoiceKey(m.speech) == LOCAL_NEURAL

    /**
     * Returns a playable file for the post, rendering it now if needed: with the online voice
     * when possible (short timeout, since a listener is waiting), otherwise on the phone.
     */
    fun ensureMessageAudio(channel: String, id: Int): File = locked("$channel/$id") {
        val m = messages.getBlocking(channel, id) ?: throw IOException("Post $channel/$id not found")
        val existing = m.audioPath?.let(::File)?.takeIf { it.length() > 0 }
        if (existing != null && !canImproveLocal(m)) return@locked existing
        val cfg = settings.state.value
        var cloudFailed = m.audioState == AudioState.NEURAL_FAILED
        if (canUseCloudNow(cfg) && !cloudFailed) {
            try {
                return@locked writeCloud(m, cfg, timeoutMs = 25_000, maxWaitMs = 0)
            } catch (e: IOException) {
                Log.w(TAG, "Online voice failed for $channel/$id, rendering on the phone: ${e.message}")
                noteCloudFailure(e, cfg)
                if (e is RejectedTextException) cloudFailed = true
            }
        }
        writeLocal(m, if (cloudFailed) AudioState.NEURAL_FAILED else AudioState.LOCAL)
    }

    /** Downloads online-voice audio for an upcoming post ahead of time. */
    fun prefetchNeural(m: MessageEntity, cfg: AppSettings): Prefetch = locked("${m.channel}/${m.id}") {
        if (!cloudReady(cfg)) return@locked Prefetch.CLOUD_UNAVAILABLE
        val fresh = messages.getBlocking(m.channel, m.id) ?: return@locked Prefetch.DONE
        if (!needsNeural(fresh, cfg)) return@locked Prefetch.DONE
        try {
            // A sync can afford to wait out a short per-minute limit.
            writeCloud(fresh, cfg, timeoutMs = 90_000, maxWaitMs = 65_000)
            Prefetch.DONE
        } catch (e: RejectedTextException) {
            markNeuralFailed(fresh)
            Prefetch.DONE
        } catch (e: IOException) {
            Log.w(TAG, "Prefetch failed for ${m.channel}/${m.id}: ${e.message}")
            if (noteCloudFailure(e, cfg)) Prefetch.CLOUD_UNAVAILABLE else Prefetch.NETWORK_FAILED
        }
    }

    /** Records key/quota problems; returns true if the online voice is now paused. */
    private fun noteCloudFailure(e: IOException, cfg: AppSettings): Boolean = when (e) {
        is AuthException -> {
            rejectedKeys += keyId(cfg)
            setStatus(cfg.cloudVoice, CloudStatus.BAD_KEY)
            true
        }
        is ThrottledException -> {
            val seconds = e.retryAfterSeconds ?: if (e.quota) 6 * 3600L else 60L
            pausedUntil[cfg.cloudVoice] = System.currentTimeMillis() + seconds * 1000
            setStatus(cfg.cloudVoice, if (e.quota) CloudStatus.QUOTA else CloudStatus.THROTTLED)
            true
        }
        else -> false
    }

    fun needsNeural(m: MessageEntity, cfg: AppSettings): Boolean {
        if (m.speech.isBlank() || m.audioState == AudioState.NEURAL_FAILED) return false
        val file = m.audioPath?.let(::File)
        val current = m.audioState == AudioState.NEURAL && m.audioVoiceKey == cfg.voiceKey && file != null && file.length() > 0
        return !current
    }

    /** Remembers that the service cannot voice this text, keeping any phone-rendered file. */
    private fun markNeuralFailed(m: MessageEntity) {
        val localKey = m.audioVoiceKey.takeIf { m.audioState == AudioState.LOCAL }
        messages.setAudioBlocking(m.channel, m.id, AudioState.NEURAL_FAILED, m.audioPath, localKey)
    }

    private fun writeCloud(m: MessageEntity, cfg: AppSettings, timeoutMs: Long, maxWaitMs: Long): File {
        val audio = renderCloud(m.speech, cfg, GAP_AFTER_POST_MS, timeoutMs, maxWaitMs)
        val file = messageFile(m.channel, m.id, audio.extension)
        writeAtomically(file, audio.bytes)
        messages.setAudioBlocking(m.channel, m.id, AudioState.NEURAL, file.path, cfg.voiceKey)
        m.audioPath?.takeIf { it != file.path }?.let { File(it).delete() }
        setStatus(cfg.cloudVoice, CloudStatus.OK)
        return file
    }

    private fun writeLocal(m: MessageEntity, state: Int): File {
        val voiceKey = localVoiceKey(m.speech)
        val bytes = renderLocal(m.speech, GAP_AFTER_POST_MS)
        val file = messageFile(m.channel, m.id, "wav")
        writeAtomically(file, bytes)
        messages.setAudioBlocking(m.channel, m.id, state, file.path, voiceKey)
        m.audioPath?.takeIf { it != file.path }?.let { File(it).delete() }
        return file
    }

    /**
     * Audio for a short spoken phrase (channel name, "all caught up"), cached per text and voice.
     * Only Azure is used online here: Gemini's small free quota is kept for the posts themselves.
     */
    fun ensureClip(key: String, text: String): File {
        val cfg = settings.state.value
        val safeKey = key.replace(Regex("[^A-Za-z0-9_]"), "_")
        return locked("clip/$safeKey") {
            if (cfg.cloudVoice == CloudVoice.AZURE) {
                val cloudFile = File(clipsDir, "${safeKey}_${tag(text + cfg.voiceKey)}.mp3")
                if (cloudFile.length() > 0) return@locked cloudFile
                if (canUseCloudNow(cfg)) {
                    try {
                        writeAtomically(cloudFile, renderCloud(text, cfg, GAP_AFTER_CLIP_MS, 15_000, 0).bytes)
                        return@locked cloudFile
                    } catch (e: IOException) {
                        Log.w(TAG, "Online clip failed: ${e.message}")
                        noteCloudFailure(e, cfg)
                    }
                }
            }
            val localFile = File(clipsDir, "${safeKey}_${tag(text + localVoiceKey(text))}.wav")
            if (localFile.length() > 0) return@locked localFile
            writeAtomically(localFile, renderLocal(text, GAP_AFTER_CLIP_MS))
            localFile
        }
    }

    private fun tag(s: String) = Integer.toHexString(s.hashCode())

    /**
     * A sample of [voice] from the online service selected in [cfg], for the settings screen.
     * Gemini samples are kept, since each one costs a request of the free daily quota.
     */
    fun previewCloud(cfg: AppSettings, voice: String, text: String): File {
        val isGemini = cfg.cloudVoice == CloudVoice.GEMINI
        val file = File(previewDir, if (isGemini) "gemini_${voice}_${tag(text + cfg.geminiKey)}.wav" else "azure.mp3")
        if (isGemini && file.length() > 0) return file
        val bytes = try {
            if (isGemini) {
                val clip = gemini.synthesize(cfg.geminiKey, text, voice, timeoutMs = 30_000)
                Pcm.joinToWav(listOf(Pcm.of(clip)), 0, 200)
            } else {
                azure.synthesize(cfg.azure, listOf(voice to text), timeoutMs = 20_000)
            }
        } catch (e: IOException) {
            noteCloudFailure(e, cfg)
            throw e
        }
        rejectedKeys -= keyId(cfg)
        pausedUntil.remove(cfg.cloudVoice)
        setStatus(cfg.cloudVoice, CloudStatus.OK)
        writeAtomically(file, bytes)
        return file
    }

    /** A sample of the offline Hebrew voice. */
    fun previewOffline(text: String): File {
        val pcm = offlineHebrew.synthesize(text) ?: throw IOException("The offline Hebrew voice is not available")
        val file = File(previewDir, "offline.wav")
        writeAtomically(file, Pcm.joinToWav(listOf(pcm), 0, 200))
        return file
    }

    private fun renderCloud(text: String, cfg: AppSettings, gapMs: Int, timeoutMs: Long, maxWaitMs: Long): Audio =
        when (cfg.cloudVoice) {
            CloudVoice.GEMINI -> {
                // One request per post (the model reads mixed Hebrew/English itself), split only if very long.
                val clips = GeminiTts.chunks(text).map {
                    Pcm.of(gemini.synthesize(cfg.geminiKey, it, cfg.geminiVoice, maxWaitMs, timeoutMs))
                }
                if (clips.isEmpty()) throw RejectedTextException(0)
                Audio(Pcm.joinToWav(clips, PAUSE_MS, gapMs), "gemini.wav")
            }
            CloudVoice.AZURE -> {
                // One request with a voice per language run (the official API accepts several voices).
                val parts = LanguageSegmenter.segment(text).map { seg ->
                    Voices.neuralFor(seg.lang, cfg.hebrewVoice, cfg.englishVoice) to seg.text
                }
                if (parts.isEmpty()) throw RejectedTextException(0)
                Audio(azure.synthesize(cfg.azure, parts, trailingSilenceMs = gapMs, timeoutMs = timeoutMs), "mp3")
            }
            CloudVoice.NONE -> throw IOException("No online voice selected")
        }

    /** On the phone, a voice per language run; one device voice for everything if a run fails. */
    private fun renderLocal(text: String, gapMs: Int): ByteArray {
        val segments = LanguageSegmenter.segment(text)
        val clips = segments.mapNotNull(::renderSegment)
        if (clips.isNotEmpty() && clips.size == segments.size) return Pcm.joinToWav(clips, PAUSE_MS, gapMs)
        val lang = LanguageSegmenter.dominant(text) ?: Lang.EN
        val whole = runCatching { Pcm.of(device.synthesize(text, lang, scratchDir)) }.getOrNull()
        if (whole != null) return Pcm.joinToWav(listOf(whole), PAUSE_MS, gapMs)
        if (clips.isNotEmpty()) return Pcm.joinToWav(clips, PAUSE_MS, gapMs) // part of the post is better than silence
        throw IOException("No voice available for this post")
    }

    private fun renderSegment(seg: Segment): Pcm? {
        if (seg.lang == Lang.HE && offlineHebrew.installed) {
            try {
                offlineHebrew.synthesize(seg.text)?.takeIf { it.samples.isNotEmpty() }?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "Offline Hebrew voice failed, using the device voice: ${e.message}")
            }
        }
        return runCatching { Pcm.of(device.synthesize(seg.text, seg.lang, scratchDir)) }
            .onFailure { Log.w(TAG, "Device TTS skipped a ${seg.lang} run: ${it.message}") }
            .getOrNull()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Could not write $file")
        }
    }

    fun deleteChannelAudio(channel: String) {
        File(audioDir, channel).deleteRecursively()
    }

    fun clearAll() {
        audioDir.deleteRecursively()
        scratchDir.deleteRecursively()
    }

    fun cacheSizeBytes(): Long = audioDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes files that no post references any more (edited posts, removed channels). */
    fun deleteOrphans(referenced: Set<String>) {
        val recent = System.currentTimeMillis() - 10 * 60_000L // files being written right now
        audioDir.listFiles()?.filter { it.isDirectory && it != clipsDir }?.forEach { dir ->
            dir.listFiles()?.forEach { f -> if (f.path !in referenced && f.lastModified() < recent) f.delete() }
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        clipsDir.listFiles()?.forEach { f -> if (f.lastModified() < weekAgo) f.delete() }
    }

    companion object {
        private const val TAG = "AudioRenderer"
        const val GAP_AFTER_POST_MS = 700
        const val GAP_AFTER_CLIP_MS = 300
        private const val PAUSE_MS = 250
        const val LOCAL_DEVICE = "local|device"
        const val LOCAL_NEURAL = "local|he-noa"
    }
}

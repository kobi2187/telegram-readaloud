package io.github.kobi2187.readaloud.tts

import android.content.Context
import android.util.Log
import io.github.kobi2187.readaloud.data.AppSettings
import io.github.kobi2187.readaloud.data.AudioState
import io.github.kobi2187.readaloud.data.MessageDao
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.data.SettingsRepository
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.text.Lang
import io.github.kobi2187.readaloud.text.LanguageSegmenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why cloud voices are currently not used, for the settings screen. */
enum class CloudStatus { OK, BAD_KEY, QUOTA, THROTTLED }

/**
 * Produces the audio file paired with each post. Microsoft neural MP3 (via the user's Azure key)
 * is preferred and is pre-downloaded during sync; the device engine renders WAV offline when
 * needed. Every method blocks, so call from a background thread.
 */
class AudioRenderer(
    context: Context,
    private val messages: MessageDao,
    private val settings: SettingsRepository,
    private val azure: AzureTts,
    private val device: SystemTts,
    private val network: NetworkMonitor,
) {
    val audioDir = File(context.filesDir, "audio")
    private val clipsDir = File(audioDir, "_clips")
    private val scratchDir = File(context.cacheDir, "tts")
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    private val _cloudStatus = MutableStateFlow(CloudStatus.OK)
    val cloudStatus: StateFlow<CloudStatus> = _cloudStatus.asStateFlow()

    /** After a quota/throttle answer, leave the service alone until this time. */
    @Volatile
    private var cloudPausedUntil = 0L
    @Volatile
    private var rejectedKey: String? = null

    enum class Prefetch { DONE, NETWORK_FAILED, CLOUD_UNAVAILABLE }

    private fun <T> locked(key: String, block: () -> T): T =
        locks.getOrPut(key) { ReentrantLock() }.withLock(block)

    private fun messageFile(channel: String, id: Int, neural: Boolean) =
        File(File(audioDir, channel), "$id.${if (neural) "mp3" else "wav"}")

    /** Cloud voices are configured, the key was not rejected, and we are not backing off. */
    fun cloudReady(cfg: AppSettings): Boolean =
        cfg.cloudEnabled && rejectedKey != cfg.azureKey && System.currentTimeMillis() >= cloudPausedUntil

    private fun canUseCloudNow(cfg: AppSettings) = cloudReady(cfg) && network.isOnline(cfg.allowMetered)

    /**
     * Returns a playable file for the post, rendering it now if needed: neural when online
     * (short timeout, since a listener is waiting), otherwise with the device engine.
     */
    fun ensureMessageAudio(channel: String, id: Int): File = locked("$channel/$id") {
        val m = messages.getBlocking(channel, id) ?: throw IOException("Post $channel/$id not found")
        m.audioPath?.let(::File)?.takeIf { it.length() > 0 }?.let { return@locked it }
        val cfg = settings.state.value
        var neuralFailed = m.audioState == AudioState.NEURAL_FAILED
        if (canUseCloudNow(cfg) && !neuralFailed) {
            try {
                return@locked writeNeural(m, cfg, timeoutMs = 20_000)
            } catch (e: IOException) {
                Log.w(TAG, "Neural render failed for $channel/$id, using device voice: ${e.message}")
                noteCloudFailure(e, cfg)
                if (e is AzureTts.RejectedTextException) neuralFailed = true
            }
        }
        writeLocal(m, if (neuralFailed) AudioState.NEURAL_FAILED else AudioState.LOCAL)
    }

    /** Downloads neural audio for an upcoming post ahead of time. */
    fun prefetchNeural(m: MessageEntity, cfg: AppSettings): Prefetch = locked("${m.channel}/${m.id}") {
        if (!cloudReady(cfg)) return@locked Prefetch.CLOUD_UNAVAILABLE
        val fresh = messages.getBlocking(m.channel, m.id) ?: return@locked Prefetch.DONE
        if (!needsNeural(fresh, cfg)) return@locked Prefetch.DONE
        try {
            writeNeural(fresh, cfg, timeoutMs = 60_000)
            Prefetch.DONE
        } catch (e: AzureTts.RejectedTextException) {
            markNeuralFailed(fresh)
            Prefetch.DONE
        } catch (e: IOException) {
            Log.w(TAG, "Prefetch failed for ${m.channel}/${m.id}: ${e.message}")
            if (noteCloudFailure(e, cfg)) Prefetch.CLOUD_UNAVAILABLE else Prefetch.NETWORK_FAILED
        }
    }

    /** Records key/quota problems; returns true if cloud voices are now paused. */
    private fun noteCloudFailure(e: IOException, cfg: AppSettings): Boolean = when (e) {
        is AzureTts.AuthException -> {
            rejectedKey = cfg.azureKey
            _cloudStatus.value = CloudStatus.BAD_KEY
            true
        }
        is AzureTts.ThrottledException -> {
            val seconds = e.retryAfterSeconds ?: if (e.quota) 6 * 3600L else 60L
            cloudPausedUntil = System.currentTimeMillis() + seconds * 1000
            _cloudStatus.value = if (e.quota) CloudStatus.QUOTA else CloudStatus.THROTTLED
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

    /** Remembers that the service cannot voice this text, keeping any device-rendered file. */
    private fun markNeuralFailed(m: MessageEntity) {
        messages.setAudioBlocking(m.channel, m.id, AudioState.NEURAL_FAILED, m.audioPath, null)
    }

    private fun writeNeural(m: MessageEntity, cfg: AppSettings, timeoutMs: Long): File {
        val bytes = renderNeural(m.speech, cfg, GAP_AFTER_POST_MS, timeoutMs)
        val file = messageFile(m.channel, m.id, neural = true)
        writeAtomically(file, bytes)
        messages.setAudioBlocking(m.channel, m.id, AudioState.NEURAL, file.path, cfg.voiceKey)
        m.audioPath?.takeIf { it != file.path }?.let { File(it).delete() }
        _cloudStatus.value = CloudStatus.OK
        return file
    }

    private fun writeLocal(m: MessageEntity, state: Int): File {
        val bytes = renderDevice(m.speech, GAP_AFTER_POST_MS)
        val file = messageFile(m.channel, m.id, neural = false)
        writeAtomically(file, bytes)
        messages.setAudioBlocking(m.channel, m.id, state, file.path, null)
        return file
    }

    /** Audio for a short spoken phrase (channel name, "all caught up"). Cached per text and voice. */
    fun ensureClip(key: String, text: String): File {
        val cfg = settings.state.value
        val tag = Integer.toHexString((text + cfg.voiceKey).hashCode())
        val safeKey = key.replace(Regex("[^A-Za-z0-9_]"), "_")
        val neuralFile = File(clipsDir, "${safeKey}_$tag.mp3")
        val localFile = File(clipsDir, "${safeKey}_$tag.wav")
        return locked("clip/$safeKey") {
            if (neuralFile.length() > 0) return@locked neuralFile
            if (canUseCloudNow(cfg)) {
                try {
                    writeAtomically(neuralFile, renderNeural(text, cfg, GAP_AFTER_CLIP_MS, 15_000))
                    return@locked neuralFile
                } catch (e: IOException) {
                    Log.w(TAG, "Neural clip failed: ${e.message}")
                    noteCloudFailure(e, cfg)
                }
            }
            if (localFile.length() > 0) return@locked localFile
            writeAtomically(localFile, renderDevice(text, GAP_AFTER_CLIP_MS))
            localFile
        }
    }

    /** Renders a sample sentence with one neural voice for the settings screen. */
    fun preview(account: AzureTts.Account, voice: String, text: String): File {
        val file = File(scratchDir, "preview.mp3")
        val bytes = try {
            azure.synthesize(account, listOf(voice to text), timeoutMs = 20_000)
        } catch (e: AzureTts.AuthException) {
            _cloudStatus.value = CloudStatus.BAD_KEY
            throw e
        }
        if (rejectedKey == account.key) rejectedKey = null
        _cloudStatus.value = CloudStatus.OK
        writeAtomically(file, bytes)
        return file
    }

    /** One request with a voice per language run (the official API accepts several voices). */
    private fun renderNeural(text: String, cfg: AppSettings, gapMs: Int, timeoutMs: Long): ByteArray {
        val parts = LanguageSegmenter.segment(text).map { seg ->
            Voices.neuralFor(seg.lang, cfg.hebrewVoice, cfg.englishVoice) to seg.text
        }
        if (parts.isEmpty()) throw AzureTts.RejectedTextException(0)
        return azure.synthesize(cfg.azure, parts, trailingSilenceMs = gapMs, timeoutMs = timeoutMs)
    }

    /** Device engine with a voice per language run; one voice for everything if that fails. */
    private fun renderDevice(text: String, gapMs: Int): ByteArray {
        val segments = LanguageSegmenter.segment(text)
        val clips = segments.mapNotNull { seg ->
            runCatching { device.synthesize(seg.text, seg.lang, scratchDir) }
                .onFailure { Log.w(TAG, "Device TTS skipped a ${seg.lang} run: ${it.message}") }
                .getOrNull()
        }
        val uniform = clips.isNotEmpty() && clips.all { it.format == clips.first().format }
        if (uniform && clips.size == segments.size) return join(clips, gapMs)
        val lang = LanguageSegmenter.dominant(text) ?: Lang.EN
        val whole = runCatching { device.synthesize(text, lang, scratchDir) }.getOrNull()
        if (whole != null) return join(listOf(whole), gapMs)
        if (uniform) return join(clips, gapMs) // part of the post is better than silence
        throw IOException("No device voice available for this post")
    }

    private fun join(clips: List<Wav.Clip>, gapMs: Int): ByteArray {
        val format = clips.first().format
        val parts = mutableListOf<ByteArray>()
        clips.forEachIndexed { i, c ->
            if (i > 0) parts += Wav.silence(format, 250)
            parts += c.pcm
        }
        parts += Wav.silence(format, gapMs)
        return ByteArrayOutputStream().also { Wav.write(it, format, parts) }.toByteArray()
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
    }
}

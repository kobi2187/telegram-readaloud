package io.github.kobi2187.readaloud.tts

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.core.content.edit
import io.github.kobi2187.readaloud.voice.ModelFile
import io.github.kobi2187.readaloud.voice.VoiceCatalog
import io.github.kobi2187.readaloud.voice.VoicePack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Speaks Hebrew without a network connection. */
interface OfflineHebrew {
    /** The offline voice that reads Hebrew now, or null when none is installed (the phone's voice reads). */
    val activeId: String?

    /** 16-bit mono speech from [voiceId], or null when that voice is not installed or cannot run here. */
    fun synthesize(text: String, voiceId: String? = activeId): Pcm? {
        val parts = ArrayList<Pcm>()
        if (!synthesizeStreaming(text, voiceId) { parts += it }) return null
        if (parts.isEmpty()) return null
        return Pcm(ShortArray(parts.sumOf { it.samples.size }).also { out ->
            var pos = 0
            for (p in parts) { p.samples.copyInto(out, pos); pos += p.samples.size }
        }, parts.first().sampleRate)
    }

    /**
     * Speaks [text] sentence by sentence through [emit]; false when [voiceId] is not available.
     * Exceptions from [emit] stop the rendering.
     */
    fun synthesizeStreaming(text: String, voiceId: String? = activeId, emit: (Pcm) -> Unit): Boolean

    /** Records how fast [voiceId] renders here (seconds of work per second of audio). */
    fun reportSpeed(voiceId: String, realTimeFactor: Float) = Unit

    /** How the voice is told to pronounce (part of the audio's identity: "" by default). */
    val variant: String get() = ""
}

/** How the offline voices are told to pronounce Hebrew. */
data class Pronunciation(
    /** The Phonikud pipeline (nikud model + rules) instead of renikud. */
    val phonikud: Boolean = false,
    /** Mark the stressed syllables in the IPA. */
    val stress: Boolean = true,
) {
    /** Part of the audio's identity: posts are voiced again when it changes. */
    val key: String get() = (if (phonikud) "|phonikud" else "") + (if (stress) "" else "|nostress")
}

/** A loaded voice, ready to speak. */
interface LoadedVoice : AutoCloseable {
    val sampleRate: Int

    /** Whether stressed syllables are marked in the IPA it is given. */
    var stress: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") value) = Unit
    fun synthesize(text: String, emit: (ShortArray) -> Unit)
    fun synthesize(text: String): ShortArray {
        val parts = ArrayList<ShortArray>()
        synthesize(text) { parts += it }
        return ShortArray(parts.sumOf { it.size }).also { out ->
            var pos = 0
            for (p in parts) { p.copyInto(out, pos); pos += p.size }
        }
    }
}

/**
 * The downloadable offline Hebrew voices ([VoiceCatalog]): which are installed, which one is
 * active, and downloading them. Files shared by several voices (the renikud phonemizer, the Blue
 * engine) are downloaded once and kept while any installed voice needs them. Every download is
 * resumable, checked against its SHA-256, and test-spoken before the voice counts as installed.
 */
class OfflineVoices(
    context: Context,
    http: OkHttpClient,
    private val catalog: List<VoicePack> = VoiceCatalog.all,
    /** Loads a voice; the flag asks for the Phonikud pipeline instead of renikud. */
    private val opener: (File, VoicePack, Boolean) -> LoadedVoice = ::openVoice,
    private val freeBytes: (File) -> Long = { runCatching { StatFs(it.path).availableBytes }.getOrDefault(Long.MAX_VALUE) },
    /** The pronunciation the settings ask for. */
    private val wanted: () -> Pronunciation = { Pronunciation() },
    /** Checks a downloaded add-on (not a voice) works here; throws if not. */
    private val addonTester: (File, VoicePack) -> Unit = ::testAddon,
) : OfflineHebrew {
    /** [speed]: measured real-time factor per voice (below 1 renders faster than it plays). */
    data class State(val installed: Set<String>, val active: String?, val speed: Map<String, Float> = emptyMap())

    class ChecksumException(name: String) : IOException("$name is corrupt")
    class NotEnoughSpaceException(val neededBytes: Long) : IOException("Not enough free space")
    class UnsupportedVoiceException(cause: Throwable) : IOException("The voice cannot run on this device", cause)

    val dir = File(context.filesDir, "models")
    private val prefs = context.getSharedPreferences("offline_voices", Context.MODE_PRIVATE)
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(State(emptySet(), null))
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var loaded: Pair<String, LoadedVoice>? = null
    private val failed = HashSet<String>()

    init {
        migrateFromSingleVoice(File(context.filesDir, "models/he-noa"))
        refresh()
    }

    override val activeId: String? get() = _state.value.active

    /** The pronunciation in use: Phonikud only once it is installed. */
    val pronunciation: Pronunciation
        get() = wanted().let { it.copy(phonikud = it.phonikud && VoiceCatalog.PHONIKUD_ID in _state.value.installed) }

    override val variant: String get() = pronunciation.key

    fun pack(id: String): VoicePack? = catalog.firstOrNull { it.id == id }

    private fun isInstalled(pack: VoicePack) = pack.files.all { File(dir, it.name).length() == it.size }

    /** Recomputes what is installed; the active voice falls back to another installed one. */
    private fun refresh() = synchronized(lock) {
        val installed = catalog.filter(::isInstalled).map { it.id }.toSet()
        val saved = prefs.getString(KEY_ACTIVE, null)
        val voices = catalog.filter { it.isVoice }
        val active = saved?.takeIf { id -> id in installed && voices.any { it.id == id } } ?: voices.firstOrNull { it.id in installed }?.id
        if (active != saved) prefs.edit { putString(KEY_ACTIVE, active) }
        val speed = installed.mapNotNull { id -> prefs.getFloat(KEY_SPEED + id, -1f).takeIf { it > 0 }?.let { id to it } }.toMap()
        _state.value = State(installed, active, speed)
    }

    /** Version 1.1 kept the Noa voice in its own folder; move it so it is not downloaded again. */
    private fun migrateFromSingleVoice(old: File) {
        if (!old.isDirectory) return
        dir.mkdirs()
        old.listFiles()?.forEach { f -> if (f.isFile && !File(dir, f.name).exists()) f.renameTo(File(dir, f.name)) }
        old.deleteRecursively()
    }

    fun select(id: String) {
        synchronized(lock) {
            if (id !in _state.value.installed || pack(id)?.isVoice != true) return
            prefs.edit { putString(KEY_ACTIVE, id) }
        }
        refresh()
    }

    override fun synthesizeStreaming(text: String, voiceId: String?, emit: (Pcm) -> Unit): Boolean {
        val id = voiceId ?: return false
        val p = pronunciation
        val voice = load(id, p.phonikud) ?: return false
        synchronized(voice) { voice.stress = p.stress }
        voice.synthesize(text) { emit(Pcm(it, voice.sampleRate)) }
        return true
    }

    /** Keeps a smoothed measurement, so Settings can warn about voices too slow for this phone. */
    override fun reportSpeed(voiceId: String, realTimeFactor: Float) {
        if (!realTimeFactor.isFinite() || realTimeFactor <= 0f) return
        val old = prefs.getFloat(KEY_SPEED + voiceId, -1f)
        val smoothed = if (old > 0) old * 0.7f + realTimeFactor * 0.3f else realTimeFactor
        prefs.edit { putFloat(KEY_SPEED + voiceId, smoothed) }
        refresh()
    }

    /** One voice is kept in memory; switching voices frees the previous one. */
    private fun load(id: String, phonikud: Boolean): LoadedVoice? {
        val old: LoadedVoice?
        val fresh: LoadedVoice
        val key = "$id|$phonikud"
        synchronized(lock) {
            loaded?.let { (lid, v) -> if (lid == key) return v }
            val pack = pack(id)?.takeIf { it.isVoice } ?: return null
            if (id in failed || id !in _state.value.installed) return null
            old = loaded?.second
            loaded = null
            fresh = try {
                opener(dir, pack, phonikud)
            } catch (e: Throwable) { // also native-library and out-of-memory errors
                Log.w(TAG, "Offline voice $id could not be loaded", e)
                failed += id
                old?.let { o -> synchronized(o) { o.close() } }
                return null
            }
            loaded = key to fresh
        }
        old?.let { o -> synchronized(o) { o.close() } }
        return fresh
    }

    /** Bytes still to download for [id] (shared files already present count as done). */
    fun remainingBytes(id: String): Long = pack(id)?.files?.sumOf { f ->
        val target = File(dir, f.name)
        if (target.length() == f.size) 0L else f.size - File(dir, f.name + ".part").length().coerceAtMost(f.size)
    } ?: 0L

    /**
     * Downloads what [id] still needs, resuming partial files, then test-speaks it and makes it the
     * active voice. [onProgress] gets bytes done and the total still needed when the call began.
     */
    fun download(id: String, stopped: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val pack = pack(id) ?: throw IOException("Unknown voice $id")
        dir.mkdirs()
        val needed = remainingBytes(id)
        if (needed + SPACE_MARGIN > freeBytes(dir)) throw NotEnoughSpaceException(needed + SPACE_MARGIN)
        var finished = 0L
        for (file in pack.files) {
            val target = File(dir, file.name)
            if (target.length() == file.size) continue
            target.parentFile?.mkdirs()
            val part = File(dir, file.name + ".part")
            if (part.length() > file.size) part.delete()
            val before = part.length()
            while (part.length() < file.size) {
                fetch(file, part, stopped) { written -> onProgress(finished + written - before, needed) }
            }
            if (sha256(part) != file.sha256) {
                part.delete()
                throw ChecksumException(file.name)
            }
            if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")
            finished += file.size - before
            onProgress(finished, needed)
        }
        synchronized(lock) { failed -= id }
        try {
            if (!pack.isVoice) {
                addonTester(dir, pack)
                refresh()
                return
            }
            opener(dir, pack, false).use { voice ->
                if (voice.synthesize(TEST_PHRASE).isEmpty()) throw IOException("The voice produced no audio")
                // Second run, warmed up: how fast this voice really is on this phone.
                val start = System.nanoTime()
                val pcm = voice.synthesize(SPEED_PHRASE)
                val seconds = pcm.size.toDouble() / voice.sampleRate
                if (seconds > 0) prefs.edit { putFloat(KEY_SPEED + id, ((System.nanoTime() - start) / 1e9 / seconds).toFloat()) }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Voice $id failed its test", e)
            deleteFiles(pack)
            refresh()
            throw UnsupportedVoiceException(e)
        }
        synchronized(lock) { prefs.edit { putString(KEY_ACTIVE, id) } }
        refresh()
    }

    private fun fetch(file: ModelFile, part: File, stopped: () -> Boolean, onBytes: (Long) -> Unit) {
        val offset = part.length()
        val request = Request.Builder().url(file.url)
            .apply { if (offset > 0) header("Range", "bytes=$offset-") }
            .build()
        http.newCall(request).execute().use { response ->
            val append = when {
                response.code == 206 && response.header("Content-Range")?.startsWith("bytes $offset-") == true -> true
                response.code == 200 -> false // no range support: start over
                response.code == 416 -> { part.delete(); return }
                else -> throw IOException("Download of ${file.name} failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty response for ${file.name}")
            var written = if (append) offset else 0L
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped()) throw InterruptedIOException("Download stopped")
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written > file.size) throw IOException("${file.name} is larger than expected")
                        onBytes(written)
                    }
                }
            }
        }
    }

    /** Deletes [id]; files other installed voices still use are kept. */
    fun remove(id: String) {
        val pack = pack(id) ?: return
        val unload = synchronized(lock) {
            // The voice itself, or (removing Phonikud) whichever voice was loaded with it.
            val uses = { key: String -> key.startsWith("$id|") || (id == VoiceCatalog.PHONIKUD_ID && key.endsWith("|true")) }
            loaded?.takeIf { uses(it.first) }?.second.also { if (it != null) loaded = null }
        }
        unload?.let { v -> synchronized(v) { v.close() } }
        deleteFiles(pack)
        refresh()
    }

    private fun deleteFiles(pack: VoicePack) {
        val keep = catalog.filter { it.id != pack.id && it.id in _state.value.installed }.flatMap { p -> p.files.map { it.name } }.toSet()
        for (f in pack.files) {
            if (f.name in keep) continue
            File(dir, f.name).delete()
            File(dir, f.name + ".part").delete()
        }
    }

    companion object {
        private const val TAG = "OfflineVoices"
        private const val KEY_ACTIVE = "active"
        private const val TEST_PHRASE = "שלום."
        private const val SPEED_PHRASE = "ראש הממשלה נפגש הבוקר עם שר הביטחון בירושלים, ודן איתו במצב בצפון."
        private const val KEY_SPEED = "speed_"
        const val SPACE_MARGIN = 50L * 1024 * 1024

        private fun openVoice(dir: File, pack: VoicePack, phonikud: Boolean): LoadedVoice {
            val voice = VoiceCatalog.open(dir, pack, phonikud)
            return object : LoadedVoice {
                override val sampleRate = voice.sampleRate
                override var stress: Boolean
                    get() = voice.stress
                    set(value) { voice.stress = value }
                override fun synthesize(text: String, emit: (ShortArray) -> Unit) = voice.synthesize(text, emit)
                override fun close() = voice.close()
            }
        }

        /** The Phonikud add-on: checks it turns Hebrew into IPA here. */
        private fun testAddon(dir: File, pack: VoicePack) {
            check(pack.id == VoiceCatalog.PHONIKUD_ID) { "Unknown add-on ${pack.id}" }
            io.github.kobi2187.readaloud.voice.PhonikudG2p(
                ai.onnxruntime.OrtEnvironment.getEnvironment(),
                File(dir, VoiceCatalog.PHONIKUD_MODEL),
                File(dir, VoiceCatalog.PHONIKUD_TOKENIZER),
            ).use { g2p -> if ("ʃ" !in g2p.phonemize(TEST_PHRASE)) throw IOException("Phonikud produced no IPA") }
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

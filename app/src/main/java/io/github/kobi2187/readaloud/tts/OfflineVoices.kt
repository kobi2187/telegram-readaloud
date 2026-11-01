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
    fun synthesize(text: String, voiceId: String? = activeId): Pcm?
}

/** A loaded voice, ready to speak. */
interface LoadedVoice : AutoCloseable {
    val sampleRate: Int
    fun synthesize(text: String): ShortArray
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
    private val catalog: List<VoicePack> = VoiceCatalog.voices,
    private val opener: (File, VoicePack) -> LoadedVoice = ::openVoice,
    private val freeBytes: (File) -> Long = { runCatching { StatFs(it.path).availableBytes }.getOrDefault(Long.MAX_VALUE) },
) : OfflineHebrew {
    data class State(val installed: Set<String>, val active: String?)

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

    fun pack(id: String): VoicePack? = catalog.firstOrNull { it.id == id }

    private fun isInstalled(pack: VoicePack) = pack.files.all { File(dir, it.name).length() == it.size }

    /** Recomputes what is installed; the active voice falls back to another installed one. */
    private fun refresh() = synchronized(lock) {
        val installed = catalog.filter(::isInstalled).map { it.id }.toSet()
        val saved = prefs.getString(KEY_ACTIVE, null)
        val active = saved?.takeIf { it in installed } ?: catalog.firstOrNull { it.id in installed }?.id
        if (active != saved) prefs.edit { putString(KEY_ACTIVE, active) }
        _state.value = State(installed, active)
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
            if (id !in _state.value.installed) return
            prefs.edit { putString(KEY_ACTIVE, id) }
        }
        refresh()
    }

    override fun synthesize(text: String, voiceId: String?): Pcm? {
        val id = voiceId ?: return null
        val voice = load(id) ?: return null
        return synchronized(voice) { Pcm(voice.synthesize(text), voice.sampleRate) }
    }

    /** One voice is kept in memory; switching voices frees the previous one. */
    private fun load(id: String): LoadedVoice? {
        val old: LoadedVoice?
        val fresh: LoadedVoice
        synchronized(lock) {
            loaded?.let { (lid, v) -> if (lid == id) return v }
            val pack = pack(id) ?: return null
            if (id in failed || id !in _state.value.installed) return null
            old = loaded?.second
            loaded = null
            fresh = try {
                opener(dir, pack)
            } catch (e: Throwable) { // also native-library and out-of-memory errors
                Log.w(TAG, "Offline voice $id could not be loaded", e)
                failed += id
                old?.let { o -> synchronized(o) { o.close() } }
                return null
            }
            loaded = id to fresh
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
            opener(dir, pack).use { voice ->
                if (voice.synthesize(TEST_PHRASE).isEmpty()) throw IOException("The voice produced no audio")
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
            loaded?.takeIf { it.first == id }?.second.also { if (it != null) loaded = null }
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
        const val SPACE_MARGIN = 50L * 1024 * 1024

        private fun openVoice(dir: File, pack: VoicePack): LoadedVoice {
            val voice = VoiceCatalog.open(dir, pack)
            return object : LoadedVoice {
                override val sampleRate = voice.sampleRate
                override fun synthesize(text: String) = voice.synthesize(text)
                override fun close() = voice.close()
            }
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

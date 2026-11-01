package io.github.kobi2187.readaloud.tts

import android.content.Context
import android.util.Log
import io.github.kobi2187.readaloud.voice.HebrewNeuralVoice
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
    /** The voice files are present (loading can still fail on an unsupported device). */
    val installed: Boolean

    /** 16-bit mono speech, or null when the voice is not installed or cannot run here. */
    fun synthesize(text: String): Pcm?
}

/**
 * The downloadable offline Hebrew voice: renikud turns unvocalized Hebrew into phonemes and the
 * Piper "Noa" voice speaks them, both on the phone (CC BY 4.0, by thewh1teagle and contributors).
 * The files come from Hugging Face at pinned revisions and are checked against known SHA-256 sums.
 */
class OfflineHebrewVoice(
    context: Context,
    http: OkHttpClient,
    private val files: List<ModelFile> = FILES,
) : OfflineHebrew {
    class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

    class ChecksumException(name: String) : IOException("$name is corrupt")

    val dir = File(context.filesDir, "models/he-noa")
    private val http = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    private val _installedFlow = MutableStateFlow(files.all { File(dir, it.name).length() == it.size })
    val installedFlow: StateFlow<Boolean> = _installedFlow.asStateFlow()
    override val installed: Boolean get() = _installedFlow.value

    val totalBytes: Long = files.sumOf { it.size }
    private var voice: HebrewNeuralVoice? = null
    private var loadFailed = false

    @Synchronized
    private fun load(): HebrewNeuralVoice? {
        if (!installed || loadFailed) return null
        voice?.let { return it }
        return try {
            HebrewNeuralVoice(dir).also { voice = it }
        } catch (e: Throwable) { // also native-library and out-of-memory errors
            Log.w(TAG, "Offline Hebrew voice could not be loaded", e)
            loadFailed = true
            null
        }
    }

    override fun synthesize(text: String): Pcm? {
        val v = load() ?: return null
        return Pcm(v.synthesize(text), v.sampleRate)
    }

    /**
     * Downloads whatever is missing, resuming partly downloaded files. [onProgress] receives bytes
     * done and the total; [stopped] is polled so a cancelled download stops promptly.
     */
    fun download(stopped: () -> Boolean = { false }, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        dir.mkdirs()
        var finished = 0L
        for (file in files) {
            val target = File(dir, file.name)
            if (target.length() != file.size) {
                val part = File(dir, file.name + ".part")
                if (part.length() > file.size) part.delete()
                while (part.length() < file.size) {
                    fetch(file, part, stopped) { onProgress(finished + it, totalBytes) }
                }
                if (sha256(part) != file.sha256) {
                    part.delete()
                    throw ChecksumException(file.name)
                }
                if (!part.renameTo(target)) throw IOException("Could not save ${file.name}")
            }
            finished += file.size
            onProgress(finished, totalBytes)
        }
        synchronized(this) {
            loadFailed = false
            _installedFlow.value = files.all { File(dir, it.name).length() == it.size }
        }
    }

    /** Bytes already on the phone, including partial files. */
    fun downloadedBytes(): Long = files.sumOf { f ->
        File(dir, f.name).takeIf { it.length() == f.size }?.length() ?: File(dir, f.name + ".part").length()
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

    /** Deletes the voice; Hebrew is then read by the online or the phone's voice again. */
    fun remove() {
        val loaded = synchronized(this) {
            _installedFlow.value = false
            voice.also { voice = null }
        }
        // Wait for a synthesis in progress on another thread before freeing the models.
        loaded?.let { v -> synchronized(v) { v.close() } }
        dir.deleteRecursively()
    }

    companion object {
        private const val TAG = "OfflineHebrewVoice"
        private const val RENIKUD = "https://huggingface.co/renikud/renikud/resolve/a59c632c8cbac0f8c63f298cad77ef3a3a2547c9"
        private const val NOA = "https://huggingface.co/thewh1teagle/piper-he-noa/resolve/23f8e06909e9a1e562a50f99df63e3785451379a"

        val FILES = listOf(
            ModelFile(
                HebrewNeuralVoice.CONFIG_FILE, "$NOA/he_IL-noa-medium.onnx.json", 5_048,
                "109a7a2a0739061510f318d987e81c27ba81ca57c9303444298f712f71b1082c",
            ),
            ModelFile(
                HebrewNeuralVoice.G2P_FILE, "$RENIKUD/model.onnx", 20_620_594,
                "8b881a3a8f00283d86c6d1feea44d37e09c1ea6609a4de7d820d937e7f3dbbca",
            ),
            ModelFile(
                HebrewNeuralVoice.VOICE_FILE, "$NOA/he_IL-noa-medium.onnx", 77_056_101,
                "83b18c0b8fad6ac00fcea5c5bdf3c1fdbec182f9eadad9f97bc423ab4e22be7d",
            ),
        )

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

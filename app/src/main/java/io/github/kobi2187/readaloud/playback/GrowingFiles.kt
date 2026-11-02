package io.github.kobi2187.readaloud.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Files that are still being written (audio rendered on the phone sentence by sentence), so
 * playback can start before the whole post is ready.
 */
object GrowingFiles {
    class Entry {
        private val lock = Object()
        @Volatile var done = false
            private set
        @Volatile var error: IOException? = null
            private set
        @Volatile var readers = 0
            private set
        @Volatile var everRead = false
            private set
        @Volatile var lastReaderLeft = 0L
            private set

        fun grew() = synchronized(lock) { lock.notifyAll() }

        /** Where the file went once finished (renamed), for readers that open it late. */
        @Volatile var finalFile: File? = null

        fun finish(failure: IOException?, renamedTo: File? = null) = synchronized(lock) {
            finalFile = renamedTo
            error = failure
            done = true
            lock.notifyAll()
        }

        fun opened() = synchronized(lock) { readers++; everRead = true }

        fun closed() = synchronized(lock) {
            readers = (readers - 1).coerceAtLeast(0)
            if (readers == 0) lastReaderLeft = System.currentTimeMillis()
        }

        /** Nobody has listened for a while: the listener moved on. */
        fun abandoned(graceMs: Long): Boolean = everRead && readers == 0 && System.currentTimeMillis() - lastReaderLeft > graceMs

        fun await(ms: Long) = synchronized(lock) { if (!done) lock.wait(ms) }
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    fun start(file: File): Entry = Entry().also { entries[file.path] = it }

    fun get(path: String?): Entry? = path?.let { entries[it] }

    fun forget(file: File) {
        entries.remove(file.path)
    }
}

/** Reads local files; for a file still being written, waits for more data until it is finished. */
class GrowingFileDataSource : BaseDataSource(/* isNetwork = */ false) {
    private var file: RandomAccessFile? = null
    private var entry: GrowingFiles.Entry? = null
    private var uri: Uri? = null
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        var path = dataSpec.uri.path ?: throw IOException("No path in ${dataSpec.uri}")
        val known = GrowingFiles.get(path)
        if (known != null && known.done && !File(path).exists()) known.finalFile?.let { path = it.path }
        val growing = known?.takeIf { !it.done || it.error != null }
        val f = RandomAccessFile(path, "r")
        file = f
        f.seek(dataSpec.position)
        entry = growing?.also { it.opened() }
        remaining = if (growing != null) {
            C.LENGTH_UNSET.toLong()
        } else if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            f.length() - dataSpec.position
        }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val f = file ?: throw IOException("Not open")
        val growing = entry
        if (growing == null) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val n = f.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (n < 0) return C.RESULT_END_OF_INPUT
            remaining -= n
            bytesTransferred(n)
            return n
        }
        while (true) {
            val n = f.read(buffer, offset, length)
            if (n > 0) {
                bytesTransferred(n)
                return n
            }
            if (growing.done) {
                growing.error?.let { throw it }
                val last = f.read(buffer, offset, length) // written just before finishing
                if (last > 0) {
                    bytesTransferred(last)
                    return last
                }
                return C.RESULT_END_OF_INPUT
            }
            try {
                growing.await(100)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Stopped waiting for audio")
            }
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try {
            file?.close()
        } finally {
            file = null
            entry?.closed()
            entry = null
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}

/** Local files through [GrowingFileDataSource], everything else through [fallback]. */
class LocalFirstDataSource(private val fallback: DataSource) : DataSource {
    private val local = GrowingFileDataSource()
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        fallback.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val scheme = dataSpec.uri.scheme
        val source = if (scheme == null || scheme == "file") local else fallback
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(private val fallback: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = LocalFirstDataSource(fallback.createDataSource())
    }
}

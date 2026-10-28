package io.github.kobi2187.readaloud.tts

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM WAV reader/writer, enough to stitch device-TTS output together. */
object Wav {
    data class Format(val sampleRate: Int, val channels: Int, val bitsPerSample: Int) {
        val bytesPerSecond: Int get() = sampleRate * channels * bitsPerSample / 8
        val blockAlign: Int get() = channels * bitsPerSample / 8
    }

    class Clip(val format: Format, val pcm: ByteArray)

    fun read(file: File): Clip = parse(file.readBytes())

    fun parse(bytes: ByteArray): Clip {
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE"
        ) {
            throw IOException("Not a WAV file")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format: Format? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xFFFFFFFFL
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    val audioFormat = bb.getShort(body).toInt() and 0xFFFF
                    if (audioFormat != 1 && audioFormat != 0xFFFE) throw IOException("Unsupported WAV encoding $audioFormat")
                    format = Format(
                        sampleRate = bb.getInt(body + 4),
                        channels = bb.getShort(body + 2).toInt(),
                        bitsPerSample = bb.getShort(body + 14).toInt(),
                    )
                }
                "data" -> {
                    val f = format ?: throw IOException("WAV data before fmt")
                    // Streaming writers may leave the size as 0 or 0xFFFFFFFF; take the rest then.
                    val end = if (size == 0L || body + size > bytes.size) bytes.size else (body + size).toInt()
                    return Clip(f, bytes.copyOfRange(body, end))
                }
            }
            pos = body + size.toInt() + (size.toInt() and 1)
            if (size > Int.MAX_VALUE) break
        }
        throw IOException("WAV without data chunk")
    }

    fun silence(format: Format, millis: Int): ByteArray {
        val frames = format.sampleRate.toLong() * millis / 1000
        return ByteArray((frames * format.blockAlign).toInt())
    }

    fun write(file: File, format: Format, parts: List<ByteArray>) {
        file.outputStream().buffered().use { write(it, format, parts) }
    }

    fun write(out: OutputStream, format: Format, parts: List<ByteArray>) {
        val dataSize = parts.sumOf { it.size }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(format.channels.toShort()); putInt(format.sampleRate)
            putInt(format.bytesPerSecond); putShort(format.blockAlign.toShort())
            putShort(format.bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
        }
        out.write(header.array())
        parts.forEach { out.write(it) }
    }
}

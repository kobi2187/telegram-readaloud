package io.github.kobi2187.readaloud.tts

import io.github.kobi2187.readaloud.voice.Resampler
import java.io.ByteArrayOutputStream
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

/** 16-bit mono samples, the common form for joining audio from different engines. */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate

    fun resampled(rate: Int): Pcm =
        if (rate == sampleRate) this else Pcm(Resampler.resample(samples, sampleRate, rate), rate)

    companion object {
        /** Converts 16-bit WAV PCM, mixing stereo down to mono. */
        fun of(clip: Wav.Clip): Pcm {
            val f = clip.format
            if (f.bitsPerSample != 16) throw IOException("Unsupported WAV: ${f.bitsPerSample}-bit")
            val bb = ByteBuffer.wrap(clip.pcm).order(ByteOrder.LITTLE_ENDIAN)
            val frames = clip.pcm.size / f.blockAlign
            val out = ShortArray(frames) { i ->
                var sum = 0
                for (ch in 0 until f.channels) sum += bb.getShort((i * f.channels + ch) * 2)
                (sum / f.channels).toShort()
            }
            return Pcm(out, f.sampleRate)
        }

        /**
         * Joins clips with [pauseMs] between them and [trailingMs] at the end, as a WAV file at
         * the highest of their sample rates.
         */
        fun joinToWav(clips: List<Pcm>, pauseMs: Int, trailingMs: Int): ByteArray {
            require(clips.isNotEmpty())
            val rate = clips.maxOf { it.sampleRate }
            val format = Wav.Format(rate, 1, 16)
            val parts = ArrayList<ByteArray>()
            clips.forEachIndexed { i, c ->
                if (i > 0) parts += Wav.silence(format, pauseMs)
                parts += c.resampled(rate).toBytes()
            }
            parts += Wav.silence(format, trailingMs)
            return ByteArrayOutputStream().also { Wav.write(it, format, parts) }.toByteArray()
        }
    }

    private fun toBytes(): ByteArray {
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples)
        return bb.array()
    }
}

/**
 * Writes 16-bit mono WAV while it is being produced: the header says "length unknown" (as
 * streaming encoders do) until [finish] fills in the sizes. The sample rate is fixed by the
 * first chunk; later chunks are resampled to it.
 */
class StreamingWav(private val out: java.io.RandomAccessFile) {
    var sampleRate = 0
        private set
    private var dataBytes = 0L

    val durationMs: Long get() = if (sampleRate == 0) 0 else dataBytes / 2 * 1000 / sampleRate

    fun append(pcm: Pcm) {
        if (pcm.samples.isEmpty()) return
        if (sampleRate == 0) {
            sampleRate = pcm.sampleRate
            out.seek(0)
            out.write(header(sampleRate, UNKNOWN, UNKNOWN))
        }
        val samples = pcm.resampled(sampleRate).samples
        val bb = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(samples)
        out.seek(HEADER + dataBytes)
        out.write(bb.array())
        dataBytes += bb.capacity()
    }

    fun silence(ms: Int) {
        if (sampleRate == 0) return
        append(Pcm(ShortArray((sampleRate.toLong() * ms / 1000).toInt()), sampleRate))
    }

    /** Fills in the real sizes; returns false when nothing was written. */
    fun finish(): Boolean {
        if (sampleRate == 0) return false
        out.seek(0)
        out.write(header(sampleRate, (36 + dataBytes).toInt(), dataBytes.toInt()))
        return true
    }

    private fun header(rate: Int, riffSize: Int, dataSize: Int): ByteArray =
        ByteBuffer.allocate(HEADER.toInt()).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(riffSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
        }.array()

    private companion object {
        const val HEADER = 44L
        const val UNKNOWN = -1 // 0xFFFFFFFF
    }
}

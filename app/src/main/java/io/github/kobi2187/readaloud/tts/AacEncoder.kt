package io.github.kobi2187.readaloud.tts

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns 16-bit mono PCM into a compressed stream as it arrives. */
interface PcmEncoder {
    /** File extension of the output. */
    val extension: String
    fun encode(samples: ShortArray)
    fun finish()
}

/**
 * AAC-LC with the phone's built-in encoder, written as ADTS: every frame carries its own header,
 * so the file plays while it is still being written and needs no index at the end. Speech at
 * 48 kbit/s takes about 6 KB a second, 7–14 times less than WAV.
 */
class AacEncoder(private val sampleRate: Int, private val out: OutputStream) : PcmEncoder {
    override val extension = "aac"
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val info = MediaCodec.BufferInfo()
    private val freqIndex = FREQUENCIES.indexOf(sampleRate).also { require(it >= 0) { "Unsupported rate $sampleRate" } }
    private var samplesIn = 0L
    private var ended = false

    init {
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("AAC encoder unavailable: ${e.message}", e)
        }
    }

    override fun encode(samples: ShortArray) {
        var offset = 0
        while (offset < samples.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) {
                drain(false)
                continue
            }
            val buffer = codec.getInputBuffer(index)!!
            buffer.clear()
            val count = minOf(samples.size - offset, buffer.remaining() / 2)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, count)
            codec.queueInputBuffer(index, 0, count * 2, samplesIn * 1_000_000L / sampleRate, 0)
            samplesIn += count
            offset += count
            drain(false)
        }
    }

    override fun finish() {
        try {
            var queued = false
            var tries = 0
            while (!queued && tries++ < 200) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, samplesIn * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain(false)
                }
            }
            drain(true)
            out.flush()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun drain(untilEnd: Boolean) {
        var idle = 0
        while (!ended) {
            val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                index >= 0 -> {
                    idle = 0
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!codecConfig && info.size > 0) {
                        val buffer: ByteBuffer = codec.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val frame = ByteArray(info.size).also { buffer.get(it) }
                        out.write(adtsHeader(frame.size, freqIndex))
                        out.write(frame)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) ended = true
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || ++idle > 500) return
                else -> Unit // format or buffers changed
            }
        }
    }

    companion object {
        const val BIT_RATE = 48_000
        private const val TIMEOUT_US = 10_000L
        val FREQUENCIES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** The 7-byte ADTS header of an AAC-LC mono frame of [payload] bytes. */
        fun adtsHeader(payload: Int, freqIndex: Int, channels: Int = 1): ByteArray {
            val length = payload + 7
            val profile = 1 // AAC LC (object type 2) minus one
            return byteArrayOf(
                0xFF.toByte(),
                0xF1.toByte(), // MPEG-4, no CRC
                ((profile shl 6) or (freqIndex shl 2) or (channels shr 2)).toByte(),
                (((channels and 3) shl 6) or (length shr 11)).toByte(),
                ((length shr 3) and 0xFF).toByte(),
                (((length and 7) shl 5) or 0x1F).toByte(),
                0xFC.toByte(),
            )
        }

        /** An encoder for [sampleRate] writing to [out], or null when this phone cannot encode it. */
        fun createOrNull(sampleRate: Int, out: OutputStream): PcmEncoder? =
            if (sampleRate !in FREQUENCIES) null else runCatching { AacEncoder(sampleRate, out) }.getOrNull()

        /** All of [pcm] as an ADTS file, or null when encoding is not possible here. */
        fun encodeAll(pcm: Pcm): ByteArray? = runCatching {
            val bytes = ByteArrayOutputStream()
            val encoder = createOrNull(pcm.sampleRate, bytes) ?: return null
            encoder.encode(pcm.samples)
            encoder.finish()
            bytes.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}

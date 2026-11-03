package io.github.kobi2187.tg_read_aloud

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Compresses voiced posts: a 16-bit mono WAV becomes AAC-LC in an .m4a file with the phone's
 * built-in encoder (about 6 KB a second of speech at 48 kbit/s, 7–14 times less than WAV). The
 * MP4 container keeps an index, so playback can resume mid-post.
 */
object AudioChannel {
    private var attachedTo: BinaryMessenger? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private const val BIT_RATE = 48_000
    private const val TIMEOUT_US = 10_000L

    fun attach(messenger: BinaryMessenger) {
        if (attachedTo === messenger) return
        attachedTo = messenger
        MethodChannel(messenger, "readaloud/audio").setMethodCallHandler { call, result ->
            if (call.method != "encodeM4a") return@setMethodCallHandler result.notImplemented()
            val args = call.arguments as Map<*, *>
            val wav = File(args["wav"] as String)
            val out = File(args["out"] as String)
            worker.execute {
                val ok = try {
                    encode(wav, out)
                    true
                } catch (e: Exception) {
                    android.util.Log.w("AudioChannel", "AAC encoding failed for $wav", e)
                    out.delete()
                    false
                }
                main.post { result.success(ok) }
            }
        }
    }

    /** Reads the PCM of a canonical 16-bit mono WAV as written by the app (Wav.header). */
    private fun readWav(file: File): Pair<Int, ShortArray> {
        RandomAccessFile(file, "r").use { f ->
            val header = ByteArray(44)
            f.readFully(header)
            val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") throw IOException("Not a WAV file")
            val channels = bb.getShort(22).toInt()
            val rate = bb.getInt(24)
            val bits = bb.getShort(34).toInt()
            if (channels != 1 || bits != 16) throw IOException("Expected 16-bit mono, got $bits-bit $channels-channel")
            val data = ByteArray((f.length() - 44).toInt())
            f.readFully(data)
            val samples = ShortArray(data.size / 2)
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            return rate to samples
        }
    }

    private fun encode(wav: File, out: File) {
        val (rate, samples) = readWav(wav)
        val tmp = File(out.path + ".tmp")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val count = minOf(samples.size - offset, buffer.remaining() / 2)
                        val time = offset * 1_000_000L / rate
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples, offset, count)
                            codec.queueInputBuffer(index, 0, count * 2, time, 0)
                            offset += count
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        idle = 0
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && muxerStarted) {
                            val buffer = codec.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                    index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idle > 500 -> throw IOException("The encoder stopped responding")
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
        if (!tmp.renameTo(out)) throw IOException("Could not save $out")
    }
}

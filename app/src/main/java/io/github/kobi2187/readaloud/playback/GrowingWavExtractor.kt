package io.github.kobi2187.readaloud.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Plays a 16-bit PCM WAV that is still being written (its size fields say "unknown", see
 * StreamingWav) until the data runs out. Media3's own WAV reader would take the unknown size as
 * 27 hours and keep "playing" silence after the real audio ends.
 */
class GrowingWavExtractor : Extractor {
    private lateinit var track: TrackOutput
    private var output: ExtractorOutput? = null
    private var bytesPerSecond = 0
    private var frameBytes = 2
    private var started = false
    private var pending = 0
    private var writtenBytes = 0L

    override fun sniff(input: ExtractorInput): Boolean {
        val header = ParsableByteArray(HEADER)
        if (!input.peekFully(header.data, 0, HEADER, true)) return false
        val riff = String(header.data, 0, 4, Charsets.US_ASCII)
        val wave = String(header.data, 8, 4, Charsets.US_ASCII)
        val data = String(header.data, 36, 4, Charsets.US_ASCII)
        header.position = 40
        return riff == "RIFF" && wave == "WAVE" && data == "data" && header.readLittleEndianUnsignedInt() == UNKNOWN_SIZE
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!started) {
            val header = ParsableByteArray(HEADER)
            input.readFully(header.data, 0, HEADER)
            header.position = 22
            val channels = header.readLittleEndianUnsignedShort()
            val rate = header.readLittleEndianUnsignedIntToInt()
            header.position = 34
            val bits = header.readLittleEndianUnsignedShort()
            frameBytes = channels * bits / 8
            bytesPerSecond = rate * frameBytes
            track.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(C.ENCODING_PCM_16BIT)
                    .setChannelCount(channels)
                    .setSampleRate(rate)
                    .build(),
            )
            started = true
        }
        val chunk = (bytesPerSecond / 10 / frameBytes).coerceAtLeast(1) * frameBytes // 100 ms
        val n = track.sampleData(input, chunk - pending, true)
        if (n == C.RESULT_END_OF_INPUT) {
            flush(force = true)
            return Extractor.RESULT_END_OF_INPUT
        }
        pending += n
        if (pending >= chunk) flush(force = false)
        return Extractor.RESULT_CONTINUE
    }

    private fun flush(force: Boolean) {
        val size = if (force) pending - pending % frameBytes else pending
        if (size <= 0) return
        val timeUs = writtenBytes * C.MICROS_PER_SECOND / bytesPerSecond
        track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, pending - size, null)
        writtenBytes += size
        pending -= size
    }

    override fun seek(position: Long, timeUs: Long) {
        pending = 0
        writtenBytes = 0
        started = false
    }

    override fun release() = Unit

    companion object {
        private const val HEADER = 44
        private const val UNKNOWN_SIZE = 0xFFFFFFFFL

        /** Growing WAVs first, then everything Media3 reads by default. */
        fun factory(): ExtractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(GrowingWavExtractor()) + DefaultExtractorsFactory().createExtractors()
        }
    }
}

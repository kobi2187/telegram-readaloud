package io.github.kobi2187.readaloud.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AacEncoderTest {
    /** Every frame header of an ffmpeg-made ADTS file is reproduced byte for byte. */
    @Test
    fun adtsHeadersMatchFfmpeg() {
        val bytes = javaClass.getResourceAsStream("/tone-22050.aac")!!.readBytes()
        var pos = 0
        var frames = 0
        while (pos + 7 <= bytes.size) {
            val length = ((bytes[pos + 3].toInt() and 0x03) shl 11) or ((bytes[pos + 4].toInt() and 0xFF) shl 3) or
                ((bytes[pos + 5].toInt() and 0xE0) shr 5)
            val expected = bytes.copyOfRange(pos, pos + 7)
            assertArrayEquals("frame $frames", expected, AacEncoder.adtsHeader(length - 7, AacEncoder.FREQUENCIES.indexOf(22_050)))
            pos += length
            frames++
        }
        assertEquals(bytes.size, pos)
        assertEquals("about 3 s of 1024-sample frames", 3.0, frames * 1024 / 22_050.0, 0.1)
    }
}

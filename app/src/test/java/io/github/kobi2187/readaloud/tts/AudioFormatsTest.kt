package io.github.kobi2187.readaloud.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class AudioFormatsTest {
    @Test
    fun wavRoundTrip() {
        val format = Wav.Format(24_000, 1, 16)
        val pcm = ByteArray(4800) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        Wav.write(out, format, listOf(pcm, Wav.silence(format, 100)))
        val clip = Wav.parse(out.toByteArray())
        assertEquals(format, clip.format)
        assertEquals(4800 + 4800, clip.pcm.size)
        assertArrayEquals(pcm, clip.pcm.copyOfRange(0, 4800))
    }

    @Test
    fun wavWithStreamingSizeAndExtraChunks() {
        val format = Wav.Format(22_050, 1, 16)
        val out = ByteArrayOutputStream()
        Wav.write(out, format, listOf(ByteArray(100) { 7 }))
        val bytes = out.toByteArray()
        // Insert a LIST chunk before data and zero the data size, as some engines do.
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "INFO".toByteArray()
        val patched = bytes.copyOfRange(0, 36) + list + bytes.copyOfRange(36, bytes.size)
        for (i in 0 until 4) patched[36 + list.size + 4 + i] = 0
        val clip = Wav.parse(patched)
        assertEquals(100, clip.pcm.size)
    }
}

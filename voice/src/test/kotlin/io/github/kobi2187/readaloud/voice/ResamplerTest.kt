package io.github.kobi2187.readaloud.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ResamplerTest {
    private fun tone(rate: Int, hz: Double, seconds: Double) =
        ShortArray((rate * seconds).toInt()) { (sin(2 * PI * hz * it / rate) * 12000).toInt().toShort() }

    private fun zeroCrossings(x: ShortArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test
    fun keepsPitchAndDuration() {
        val input = tone(22050, 440.0, 1.0)
        val out = Resampler.resample(input, 22050, 24000)
        assertEquals(24000, out.size)
        // 440 Hz -> ~880 zero crossings per second either way.
        assertTrue(abs(zeroCrossings(out) - zeroCrossings(input)) <= 2)
        val peak = out.drop(100).dropLast(100).maxOf { abs(it.toInt()) }
        assertTrue("amplitude kept: $peak", peak in 11000..13000)
    }

    @Test
    fun downsamplingRemovesWhatCannotBeRepresented() {
        // 10 kHz is above the 8 kHz Nyquist limit of 16 kHz audio; it must not alias into the band.
        val out = Resampler.resample(tone(48000, 10000.0, 0.5), 48000, 16000)
        val rms = kotlin.math.sqrt(out.drop(200).dropLast(200).map { it.toDouble() * it }.average())
        assertTrue("rms $rms", rms < 1500)
    }
}

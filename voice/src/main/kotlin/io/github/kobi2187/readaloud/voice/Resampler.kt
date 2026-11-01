package io.github.kobi2187.readaloud.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Windowed-sinc resampling for 16-bit mono speech (e.g. 22.05 kHz voice into a 24 kHz file). */
object Resampler {
    private const val HALF_TAPS = 16

    fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate || input.isEmpty()) return input.copyOf()
        val ratio = toRate.toDouble() / fromRate
        val outLength = floor(input.size * ratio).toInt()
        // Low-pass at the lower Nyquist frequency so downsampling does not alias.
        val cutoff = minOf(1.0, ratio) * 0.95
        val halfWidth = HALF_TAPS / minOf(1.0, ratio)
        val out = ShortArray(outLength)
        for (n in 0 until outLength) {
            val center = n / ratio
            val first = maxOf(0, floor(center - halfWidth).toInt() + 1)
            val last = minOf(input.size - 1, floor(center + halfWidth).toInt())
            var acc = 0.0
            var norm = 0.0
            for (k in first..last) {
                val x = k - center
                val w = 0.5 * (1 + cos(PI * x / halfWidth)) // Hann window
                val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
                val h = cutoff * sinc * w
                acc += input[k] * h
                norm += h
            }
            val v = if (norm != 0.0) acc / norm else 0.0
            out[n] = v.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
        return out
    }
}

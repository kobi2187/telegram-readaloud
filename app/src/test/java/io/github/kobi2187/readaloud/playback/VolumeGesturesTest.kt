package io.github.kobi2187.readaloud.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deterministic clock + scheduler. */
private class FakeTime : Scheduler {
    var now = 1_000L
    private val tasks = mutableListOf<Pair<Long, () -> Unit>>()

    override fun schedule(delayMs: Long, block: () -> Unit): () -> Unit {
        val task = (now + delayMs) to block
        tasks += task
        return { tasks.remove(task) }
    }

    fun advance(ms: Long) {
        val target = now + ms
        while (true) {
            val next = tasks.filter { it.first <= target }.minByOrNull { it.first } ?: break
            tasks.remove(next)
            now = next.first
            next.second()
        }
        now = target
    }
}

class VolumeGesturesTest {
    private val time = FakeTime()
    private val steps = mutableListOf<Int>()
    private val gestures = mutableListOf<Int>()
    private val presses = VolumePressDetector(time, { time.now }, { steps += it }, { gestures += it })

    @Test
    fun singlePressIsAVolumeStepAfterTheWindow() {
        presses.onPress(+1)
        assertEquals(emptyList<Int>(), steps)
        time.advance(450)
        assertEquals(listOf(1), steps)
        assertEquals(emptyList<Int>(), gestures)
    }

    @Test
    fun doublePressIsAGestureWithoutVolumeChange() {
        presses.onPress(+1)
        time.advance(220)
        presses.onPress(+1)
        time.advance(1000)
        assertEquals(listOf(1), gestures)
        assertEquals(emptyList<Int>(), steps)
    }

    @Test
    fun doublePressDown() {
        presses.onPress(-1)
        time.advance(150)
        presses.onPress(-1)
        assertEquals(listOf(-1), gestures)
    }

    @Test
    fun upThenDownAreTwoSteps() {
        presses.onPress(+1)
        time.advance(200)
        presses.onPress(-1)
        time.advance(1000)
        assertEquals(listOf(1, -1), steps)
        assertEquals(emptyList<Int>(), gestures)
    }

    @Test
    fun slowPressesAreSteps() {
        presses.onPress(+1)
        time.advance(600)
        presses.onPress(+1)
        time.advance(600)
        assertEquals(listOf(1, 1), steps)
        assertEquals(emptyList<Int>(), gestures)
    }

    @Test
    fun holdingAKeyIsJustVolume() {
        // Auto-repeat: first event, then 500 ms delay, then every 50 ms.
        presses.onPress(+1)
        time.advance(500)
        repeat(6) {
            presses.onPress(+1)
            time.advance(50)
        }
        time.advance(1000)
        assertEquals(emptyList<Int>(), gestures)
        assertEquals(7, steps.size)
    }

    @Test
    fun tripleTapIsOneGestureThenAStep() {
        presses.onPress(+1)
        time.advance(200)
        presses.onPress(+1)
        time.advance(200)
        presses.onPress(+1)
        time.advance(1000)
        assertEquals(listOf(1), gestures)
        assertEquals(listOf(1), steps)
    }

    // --- fallback: watching volume changes ---------------------------------------------------

    private val restored = mutableListOf<Pair<Int, Int>>()
    private val changes = VolumeChangeDetector(time, { time.now }, { dir, to -> restored += dir to to })

    @Test
    fun twoQuickStepsAreAGestureAndRestoreVolume() {
        changes.onChange(5, 6)
        time.advance(200)
        changes.onChange(6, 7)
        time.advance(400)
        assertEquals(listOf(1 to 5), restored)
    }

    @Test
    fun sliderDragIsIgnored() {
        var v = 3
        repeat(6) {
            changes.onChange(v, v + 1)
            v++
            time.advance(60)
        }
        time.advance(1000)
        assertEquals(emptyList<Pair<Int, Int>>(), restored)
    }

    @Test
    fun dragThatEndsOnAnEvenStepIsStillIgnored() {
        var v = 3
        repeat(4) {
            changes.onChange(v, v + 1)
            v++
            time.advance(120)
        }
        time.advance(1000)
        assertEquals(emptyList<Pair<Int, Int>>(), restored)
    }

    @Test
    fun jumpsAreNotGestures() {
        changes.onChange(2, 6)
        time.advance(100)
        changes.onChange(6, 7)
        time.advance(1000)
        assertEquals(emptyList<Pair<Int, Int>>(), restored)
    }

    @Test
    fun separateStepsAreNotGestures() {
        changes.onChange(5, 4)
        time.advance(800)
        changes.onChange(4, 3)
        time.advance(1000)
        assertEquals(emptyList<Pair<Int, Int>>(), restored)
    }
}

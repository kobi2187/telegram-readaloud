package io.github.kobi2187.readaloud.playback

/** Schedules [block] after [delayMs]; returns a handle that cancels it. */
fun interface Scheduler {
    fun schedule(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * Turns volume key presses into gestures. A single press is still a volume step (applied after a
 * short wait to rule out a double press); two quick presses in the same direction are a gesture.
 * Auto-repeat from holding a key (≈50 ms apart) is treated as plain volume steps.
 *
 * direction: +1 = volume up, -1 = volume down.
 */
class VolumePressDetector(
    private val scheduler: Scheduler,
    private val now: () -> Long,
    private val onVolumeStep: (direction: Int) -> Unit,
    private val onDoublePress: (direction: Int) -> Unit,
    private val windowMs: Long = DOUBLE_PRESS_WINDOW_MS,
) {
    private var pendingDirection = 0
    private var pendingAt = 0L
    private var cancelPending: (() -> Unit)? = null
    private var lastPressAt = Long.MIN_VALUE / 2
    private var lastDirection = 0
    private var holding = false

    fun onPress(direction: Int) {
        val t = now()
        val sinceLast = t - lastPressAt
        when {
            direction == lastDirection && sinceLast < REPEAT_MAX_INTERVAL_MS -> {
                // Key auto-repeat: the user is holding the key to change volume.
                flushPending()
                holding = true
                onVolumeStep(direction)
            }
            pendingDirection == direction && t - pendingAt <= windowMs && !holding -> {
                cancelPending?.invoke()
                clearPending()
                onDoublePress(direction)
            }
            else -> {
                flushPending()
                holding = false
                pendingDirection = direction
                pendingAt = t
                cancelPending = scheduler.schedule(windowMs) {
                    val d = pendingDirection
                    clearPending()
                    if (d != 0) onVolumeStep(d)
                }
            }
        }
        lastPressAt = t
        lastDirection = direction
    }

    private fun flushPending() {
        val d = pendingDirection
        if (d != 0) {
            cancelPending?.invoke()
            clearPending()
            onVolumeStep(d)
        }
    }

    private fun clearPending() {
        pendingDirection = 0
        cancelPending = null
    }

    companion object {
        const val DOUBLE_PRESS_WINDOW_MS = 400L
        const val REPEAT_MAX_INTERVAL_MS = 90L
    }
}

/**
 * Fallback for devices that keep volume keys away from media sessions: watches actual volume
 * changes. Exactly two one-step changes in the same direction within the window, followed by a
 * quiet moment (so slider drags and held keys don't count), is a gesture; the caller then
 * restores the volume that was in effect before the first change.
 */
class VolumeChangeDetector(
    private val scheduler: Scheduler,
    private val now: () -> Long,
    private val onDoubleChange: (direction: Int, restoreTo: Int) -> Unit,
    private val windowMs: Long = VolumePressDetector.DOUBLE_PRESS_WINDOW_MS,
    private val quietMs: Long = QUIET_MS,
) {
    private var firstAt = 0L
    private var firstDirection = 0
    private var volumeBefore = 0
    private var count = 0
    private var cancelConfirm: (() -> Unit)? = null
    private var suppressUntil = Long.MIN_VALUE

    fun onChange(previous: Int, current: Int) {
        val delta = current - previous
        val t = now()
        if (delta == 0) return
        val direction = if (delta > 0) 1 else -1
        cancelConfirm?.invoke()
        cancelConfirm = null
        if (t < suppressUntil) {
            suppressUntil = t + windowMs // still dragging or holding
            return
        }
        if (kotlin.math.abs(delta) != 1) {
            reset()
            suppressUntil = t + windowMs
            return
        }
        if (count == 0 || direction != firstDirection || t - firstAt > windowMs) {
            count = 1
            firstAt = t
            firstDirection = direction
            volumeBefore = previous
            return
        }
        count++
        if (count == 2) {
            val restore = volumeBefore
            cancelConfirm = scheduler.schedule(quietMs) {
                cancelConfirm = null
                reset()
                onDoubleChange(direction, restore)
            }
        } else {
            reset() // three or more steps: a slider drag or a held key
            suppressUntil = t + windowMs
        }
    }

    fun reset() {
        count = 0
        firstDirection = 0
    }

    companion object {
        const val QUIET_MS = 300L
    }
}

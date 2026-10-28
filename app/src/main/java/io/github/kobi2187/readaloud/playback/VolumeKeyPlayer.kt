package io.github.kobi2187.readaloud.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.DeviceInfo
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Wraps the player so volume keys can switch channels, even with the screen off.
 *
 * While enabled, the session reports *remote* playback. Android then sends volume key presses to
 * the session (as increase/decrease device volume) instead of changing the stream itself — this
 * works with the screen locked as long as we are playing. We turn presses into gestures and apply
 * ordinary single presses to the music stream ourselves, so normal volume control still works.
 *
 * Some Android 13–15 builds keep volume keys away from remote sessions that are not casting; there
 * the stream changes directly, so we also watch volume changes and detect the same double press.
 */
class VolumeKeyPlayer(
    player: Player,
    private val context: Context,
    private val onGesture: (direction: Int) -> Unit,
) : ForwardingSimpleBasePlayer(player) {

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(player.applicationLooper)
    private val scheduler = Scheduler { delay, block ->
        val r = Runnable(block)
        handler.postDelayed(r, delay)
        val cancel: () -> Unit = { handler.removeCallbacks(r) }
        cancel
    }
    private var ignoreVolumeChangesUntil = 0L

    /** When false the player behaves exactly like the wrapped one. */
    var hijackEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            changeDetector.reset()
            invalidateState()
        }

    private val pressDetector = VolumePressDetector(
        scheduler = scheduler,
        now = SystemClock::uptimeMillis,
        onVolumeStep = ::stepVolume,
        onDoublePress = { onGesture(it) },
    )

    private val changeDetector = VolumeChangeDetector(
        scheduler = scheduler,
        now = SystemClock::uptimeMillis,
        onDoubleChange = { direction, restoreTo ->
            setStreamVolume(restoreTo, showUi = false)
            onGesture(direction)
        },
    )

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            val previous = intent.getIntExtra(EXTRA_PREV_VALUE, -1)
            val current = intent.getIntExtra(EXTRA_VALUE, -1)
            if (hijackEnabled) invalidateState() // keep the reported remote volume in sync
            if (previous < 0 || current < 0 || !hijackEnabled || !isPlaying) return
            if (SystemClock.uptimeMillis() < ignoreVolumeChangesUntil) return
            changeDetector.onChange(previous, current)
        }
    }

    init {
        ContextCompat.registerReceiver(
            context,
            volumeReceiver,
            IntentFilter(VOLUME_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun releaseWatchers() {
        runCatching { context.unregisterReceiver(volumeReceiver) }
        handler.removeCallbacksAndMessages(null)
    }

    override fun getState(): State {
        val state = super.getState()
        if (!hijackEnabled) return state
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, max)
        return state.buildUpon()
            .setDeviceInfo(DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE).setMinVolume(min).setMaxVolume(max).build())
            .setDeviceVolume(current)
            .setIsDeviceMuted(false)
            .setAvailableCommands(
                state.availableCommands.buildUpon()
                    .addAll(
                        COMMAND_GET_DEVICE_VOLUME,
                        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
                    )
                    .build(),
            )
            .build()
    }

    override fun handleIncreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        if (!hijackEnabled) return super.handleIncreaseDeviceVolume(flags)
        pressDetector.onPress(+1)
        return Futures.immediateVoidFuture()
    }

    override fun handleDecreaseDeviceVolume(flags: Int): ListenableFuture<*> {
        if (!hijackEnabled) return super.handleDecreaseDeviceVolume(flags)
        pressDetector.onPress(-1)
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceVolume(deviceVolume: Int, flags: Int): ListenableFuture<*> {
        if (!hijackEnabled) return super.handleSetDeviceVolume(deviceVolume, flags)
        setStreamVolume(deviceVolume, showUi = false) // e.g. the system volume slider
        return Futures.immediateVoidFuture()
    }

    override fun handleSetDeviceMuted(muted: Boolean, flags: Int): ListenableFuture<*> {
        if (!hijackEnabled) return super.handleSetDeviceMuted(muted, flags)
        return Futures.immediateVoidFuture()
    }

    private fun stepVolume(direction: Int) {
        ignoreVolumeChangesUntil = SystemClock.uptimeMillis() + SELF_CHANGE_GRACE_MS
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI,
        )
        invalidateState()
    }

    private fun setStreamVolume(volume: Int, showUi: Boolean) {
        ignoreVolumeChangesUntil = SystemClock.uptimeMillis() + SELF_CHANGE_GRACE_MS
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, if (showUi) AudioManager.FLAG_SHOW_UI else 0)
        invalidateState()
    }

    private companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
        const val SELF_CHANGE_GRACE_MS = 700L
    }
}

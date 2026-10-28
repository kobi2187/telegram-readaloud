package io.github.kobi2187.readaloud.playback

import android.content.Context
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession as Media3Session
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class VolumeKeyPlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private lateinit var exo: ExoPlayer
    private lateinit var player: VolumeKeyPlayer
    private val gestures = mutableListOf<Int>()

    @Before
    fun setUp() {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0)
        exo = TestExoPlayerBuilder(context).build()
        player = VolumeKeyPlayer(exo, context) { gestures += it }
    }

    @After
    fun tearDown() {
        player.releaseWatchers()
        player.release()
    }

    private fun idle(ms: Long) = ShadowLooper.idleMainLooper(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    @Test
    fun reportsRemotePlaybackOnlyWhenEnabled() {
        assertEquals(DeviceInfo.PLAYBACK_TYPE_LOCAL, player.deviceInfo.playbackType)
        player.hijackEnabled = true
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, player.deviceInfo.playbackType)
        assertEquals(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), player.deviceInfo.maxVolume)
        assertEquals(7, player.deviceVolume)
        assertTrue(player.isCommandAvailable(Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS))
    }

    @Test
    fun doublePressIsAGestureAndLeavesVolumeAlone() {
        player.hijackEnabled = true
        player.increaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        idle(150)
        player.increaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        idle(1000)
        assertEquals(listOf(1), gestures)
        assertEquals(7, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test
    fun singlePressStillChangesTheVolume() {
        player.hijackEnabled = true
        player.decreaseDeviceVolume(C.VOLUME_FLAG_SHOW_UI)
        idle(1000)
        assertEquals(emptyList<Int>(), gestures)
        assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    /**
     * Through a real media session: controllers see remote playback, and volume adjustments
     * reach our handler. (On a phone, Media3 forwards hardware volume keys the same way via its
     * VolumeProvider; Robolectric has no system media service to exercise that hop.)
     */
    @Test
    fun sessionExposesRemoteVolumeToControllers() {
        player.hijackEnabled = true
        val session = Media3Session.Builder(context, player).setId("t").build()
        val future = androidx.media3.session.MediaController.Builder(context, session.token).buildAsync()
        RobolectricUtil.runMainLooperUntil { future.isDone }
        val controller = future.get()
        assertEquals(DeviceInfo.PLAYBACK_TYPE_REMOTE, controller.deviceInfo.playbackType)
        controller.increaseDeviceVolume(0)
        idle(120)
        controller.increaseDeviceVolume(0)
        idle(1000)
        assertEquals(listOf(1), gestures)
        controller.release()
        session.release()
    }
}

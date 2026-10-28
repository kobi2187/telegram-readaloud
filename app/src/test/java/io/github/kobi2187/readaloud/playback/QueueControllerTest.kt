package io.github.kobi2187.readaloud.playback

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.CapturingRenderersFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.ReadAloudApp
import io.github.kobi2187.readaloud.data.ChannelEntity
import io.github.kobi2187.readaloud.data.MessageEntity
import io.github.kobi2187.readaloud.net.NetworkMonitor
import io.github.kobi2187.readaloud.text.Lang
import io.github.kobi2187.readaloud.tts.AudioRenderer
import io.github.kobi2187.readaloud.tts.AzureTts
import io.github.kobi2187.readaloud.tts.SystemTts
import io.github.kobi2187.readaloud.tts.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/**
 * Drives a real ExoPlayer (fake clock, capturing audio output) through the queue: posts are voiced
 * on demand by a fake device TTS, exactly as offline playback works on a phone.
 */
@RunWith(AndroidJUnit4::class)
class QueueControllerTest {
    private val app = ApplicationProvider.getApplicationContext<ReadAloudApp>()
    private val db get() = app.graph.db
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val tts = FakeTts(app)
    private lateinit var exo: ExoPlayer
    private lateinit var queue: QueueController

    private class FakeTts(context: Context) : SystemTts(context) {
        val spoken: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun isLanguageAvailable(lang: Lang) = true

        override fun synthesize(text: String, lang: Lang, scratchDir: File, timeoutMs: Long): Wav.Clip {
            spoken += text
            val format = Wav.Format(16_000, 1, 16)
            return Wav.Clip(format, Wav.silence(format, 400))
        }
    }

    @Before
    fun setUp() = runBlocking {
        db.channels().insert(ChannelEntity(username = "a", title = "Channel A", startDate = 0, sortOrder = 0))
        db.channels().insert(ChannelEntity(username = "b", title = "Channel B", startDate = 0, sortOrder = 1))
        db.messages().insertAll(
            listOf(1, 2, 3).map { MessageEntity("a", it, it * 1000L, "A post $it", "A post $it.") } +
                listOf(10, 11).map { MessageEntity("b", it, it * 1000L, "B post $it", "B post $it.") },
        )
        val offline = object : NetworkMonitor(app) {
            override fun isOnline(allowMetered: Boolean) = false
        }
        val renderer = AudioRenderer(app, db.messages(), app.graph.settings, AzureTts(OkHttpClient()), tts, offline)
        val resolver = MediaIds.Resolver(renderer, db.channels()) { key -> queue.noticeText(key) }
        exo = TestExoPlayerBuilder(app)
            .setRenderersFactory(CapturingRenderersFactory(app))
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(app).setDataSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(app), resolver)),
            )
            .build()
        queue = QueueController(app, exo, app.graph, scope)
        exo.addListener(queue.listener)
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { log += "item ${mediaItem?.mediaId} r=$reason" }
            override fun onPlaybackStateChanged(state: Int) { log += "state $state @${exo.currentMediaItem?.mediaId}" }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { log += "error ${error.errorCodeName} ${error.cause}" }
        })
    }

    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun waitFor(what: String, condition: () -> Boolean) {
        try {
            runMainLooperUntil({ condition() }, 20_000, androidx.media3.common.util.Clock.DEFAULT)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("Timed out waiting for $what; now at $current state=${exo.playbackState}\n" + log.joinToString("\n"))
        }
    }

    @After
    fun tearDown() {
        exo.release()
        scope.cancel()
    }

    private val current: String? get() = exo.currentMediaItem?.mediaId

    @Test
    fun readsAChannelThenTheNextThenSaysAllCaughtUp() {
        scope.launch { queue.playChannel("a", announce = false) }
        runMainLooperUntil { exo.mediaItemCount == 3 }
        assertEquals("msg:a:1", current)

        waitFor("caught up") { current == "notice:caught_up" && exo.playbackState == Player.STATE_ENDED }

        runBlocking {
            val a = db.channels().get("a")!!
            val b = db.channels().get("b")!!
            assertEquals(4, a.cursorId) // past the last post
            assertEquals(12, b.cursorId)
            assertTrue((1..3).all { db.messages().get("a", it)!!.played })
            assertTrue(db.messages().get("b", 11)!!.played)
            // Every post got a playable file rendered by the device voice.
            assertTrue(db.messages().get("b", 10)!!.audioPath != null)
        }
        assertTrue("B was announced: ${tts.spoken}", tts.spoken.contains("Channel B"))
        assertTrue(tts.spoken.contains("B post 11."))
    }

    @Test
    fun newPostInTheCurrentChannelAfterCaughtUpIsReadRightAway() {
        scope.launch { queue.playChannel("a", announce = false) }
        waitFor("caught up") { current == "notice:caught_up" && exo.playbackState == Player.STATE_ENDED }
        // B was read after A, so B is the current channel now.
        runBlocking { db.messages().insertAll(listOf(MessageEntity("b", 12, 12_000, "More news", "More news."))) }
        waitFor("new post") { current == "msg:b:12" }
    }

    @Test
    fun newPostsArrivingAfterCaughtUpAreReadRightAway() {
        scope.launch { queue.playChannel("b", announce = false) }
        waitFor("caught up") { current == "notice:caught_up" && exo.playbackState == Player.STATE_ENDED }
        runBlocking { db.messages().insertAll(listOf(MessageEntity("b", 12, 12_000, "Fresh news", "Fresh news."))) }
        waitFor("new post") { current == "msg:b:12" }
        assertTrue(exo.playWhenReady)
    }

    @Test
    fun switchingChannelsAnnouncesAndRemembersWhereWeWere() {
        scope.launch { queue.playChannel("a", announce = false) }
        runMainLooperUntil { exo.mediaItemCount == 3 }
        exo.pause()
        exo.seekTo(1, 0) // listening to post 2 of A
        runMainLooperUntil { current == "msg:a:2" }

        scope.launch { queue.switchChannel(+1) }
        runMainLooperUntil { current == "announce:b" }
        assertEquals(listOf("announce:b", "msg:b:10", "msg:b:11"), (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId })
        runBlocking { assertEquals(2, db.channels().get("a")!!.cursorId) }

        scope.launch { queue.switchChannel(+1) } // wraps around to A, resuming at post 2
        runMainLooperUntil { current == "announce:a" }
        assertEquals("msg:a:2", exo.getMediaItemAt(1).mediaId)
    }

    @Test
    fun switchSaysSoWhenNoOtherChannelHasNews() {
        runBlocking { app.graph.channels.markAllHeard("b") }
        scope.launch { queue.playChannel("a", announce = false) }
        runMainLooperUntil { exo.mediaItemCount == 3 }
        scope.launch { queue.switchChannel(+1) }
        runMainLooperUntil { current == "notice:no_other" }
        assertEquals("msg:a:1", exo.getMediaItemAt(exo.currentMediaItemIndex + 1).mediaId) // then carries on
    }

    @Test
    fun voiceSearchFindsChannelsByTitle() = runBlocking {
        assertEquals("b", queue.findChannel("channel b")?.username)
        assertEquals("a", queue.findChannel("A")?.username)
        assertEquals(null, queue.findChannel("weather"))
    }
}

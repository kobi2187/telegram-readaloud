package io.github.kobi2187.readaloud.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.test.utils.CapturingRenderersFactory
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.kobi2187.readaloud.tts.Pcm
import io.github.kobi2187.readaloud.tts.StreamingWav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Playback starts on audio that is still being rendered and plays all of it. */
@RunWith(AndroidJUnit4::class)
class StreamingPlaybackTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var exo: ExoPlayer

    @get:org.junit.Rule
    val codecs: androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig =
        androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig.withAllDefaultSupportedCodecs()

    @Before
    fun setUp() {
        exo = TestExoPlayerBuilder(context)
            .setRenderersFactory(CapturingRenderersFactory(context))
            // As in PlaybackService: start after one second of audio.
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(context, GrowingWavExtractor.factory()).setDataSourceFactory(LocalFirstDataSource.Factory(DefaultDataSource.Factory(context))))
            .build()
    }

    @After
    fun tearDown() = exo.release()

    @Test
    fun playsAGrowingFileToTheEnd() {
        val part = File(context.cacheDir, "post.wav.part").apply { delete(); createNewFile() }
        val target = File(context.cacheDir, "post.wav").apply { delete() }
        val entry = GrowingFiles.start(part)
        val firstChunk = CountDownLatch(1)
        val rate = 22_050
        // Rendering faster than real time: 0.5 s of audio every 250 ms, 6 s in all.
        val writer = Thread {
            RandomAccessFile(part, "rw").use { out ->
                val wav = StreamingWav(out)
                repeat(12) {
                    wav.append(Pcm(ShortArray(rate / 2) { 500 }, rate))
                    entry.grew()
                    firstChunk.countDown()
                    Thread.sleep(250)
                }
                wav.finish()
            }
            part.renameTo(target)
            entry.finish(null, target)
        }
        writer.start()
        firstChunk.await(5, TimeUnit.SECONDS)

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(part)))
        exo.prepare()
        exo.play()
        runMainLooperUntil({ exo.isPlaying }, 10_000, androidx.media3.common.util.Clock.DEFAULT)
        assertFalse("started before the render finished", entry.done)

        try {
            runMainLooperUntil({ exo.playbackState == Player.STATE_ENDED }, 15_000, androidx.media3.common.util.Clock.DEFAULT)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw AssertionError("state=${exo.playbackState} pos=${exo.currentPosition} dur=${exo.duration} loading=${exo.isLoading} done=${entry.done} error=${exo.playerError}")
        }
        writer.join(5_000)
        assertEquals(44L + 6 * rate * 2, target.length())
        assertEquals("all 6 s were played", 6000.0, exo.currentPosition.toDouble(), 200.0)
    }

    /** Compressed posts (AAC in ADTS) also play while they are being written, and to the end. */
    @Test
    fun playsAGrowingAacFileToTheEnd() {
        val aac = javaClass.getResourceAsStream("/tone-22050.aac")!!.readBytes()
        val part = File(context.cacheDir, "post.audio.part").apply { delete(); createNewFile() }
        val target = File(context.cacheDir, "post.aac").apply { delete() }
        val entry = GrowingFiles.start(part)
        val started = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val writer = Thread {
            java.io.FileOutputStream(part).use { out ->
                aac.asList().chunked(1500).forEachIndexed { i, chunk ->
                    if (i == 8) playing.await(20, TimeUnit.SECONDS) // the last part only once playback has begun
                    out.write(chunk.toByteArray())
                    out.flush()
                    entry.grew()
                    started.countDown()
                    Thread.sleep(50)
                }
            }
            part.renameTo(target)
            entry.finish(null, target)
        }
        writer.start()
        started.await(5, TimeUnit.SECONDS)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(part)))
        exo.prepare()
        exo.play()
        runMainLooperUntil({ exo.isPlaying }, 20_000, androidx.media3.common.util.Clock.DEFAULT)
        assertFalse("started before the file was complete", entry.done)
        playing.countDown()
        awaitEndWhileProgressing(entry)
        assertEquals("all 3 s were played", 3000.0, exo.currentPosition.toDouble(), 150.0)
    }

    /**
     * Waits for the end of playback for as long as it keeps moving: decoding speed under
     * Robolectric varies a lot between machines, a stall does not.
     */
    private fun awaitEndWhileProgressing(entry: GrowingFiles.Entry, stallMs: Long = 10_000) {
        var last = -1L
        val deadline = System.currentTimeMillis() + 120_000
        while (exo.playbackState != Player.STATE_ENDED) {
            try {
                runMainLooperUntil({ exo.playbackState == Player.STATE_ENDED || exo.currentPosition > last + 100 }, stallMs, androidx.media3.common.util.Clock.DEFAULT)
            } catch (e: java.util.concurrent.TimeoutException) {
                throw AssertionError("stalled: state=${exo.playbackState} pos=${exo.currentPosition} dur=${exo.duration} loading=${exo.isLoading} done=${entry.done} error=${exo.playerError}")
            }
            last = exo.currentPosition
            if (System.currentTimeMillis() > deadline) throw AssertionError("did not finish in 2 minutes: pos=$last")
        }
    }

    /** A finished AAC post can be resumed mid-way. */
    @Test
    fun aFinishedAacFileIsSeekable() {
        val bytes = javaClass.getResourceAsStream("/tone-22050.aac")!!.readBytes()
        val file = File(context.cacheDir, "done.aac").apply { writeBytes(bytes) }
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)), 2_000)
        exo.prepare()
        runMainLooperUntil { exo.playbackState == Player.STATE_READY }
        assertTrue(exo.isCurrentMediaItemSeekable)
        assertEquals(3000.0, exo.duration.toDouble(), 200.0)
        assertEquals(2000.0, exo.currentPosition.toDouble(), 100.0)
    }

    @Test
    fun aFinishedFileReadsNormally() {
        val file = File(context.cacheDir, "done.wav")
        RandomAccessFile(file, "rw").use { out ->
            file.writeBytes(ByteArray(0))
            val wav = StreamingWav(out)
            wav.append(Pcm(ShortArray(22_050) { 300 }, 22_050))
            wav.finish()
        }
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.play()
        runMainLooperUntil({ exo.playbackState == Player.STATE_ENDED }, 20_000, androidx.media3.common.util.Clock.DEFAULT)
        assertEquals(1000.0, exo.duration.toDouble(), 50.0)
    }
}

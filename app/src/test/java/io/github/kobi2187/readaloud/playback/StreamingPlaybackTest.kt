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
        // Rendering a little faster than real time: 0.5 s of audio every 150 ms, 6 s in all.
        val writer = Thread {
            RandomAccessFile(part, "rw").use { out ->
                val wav = StreamingWav(out)
                repeat(12) {
                    wav.append(Pcm(ShortArray(rate / 2) { 500 }, rate))
                    entry.grew()
                    firstChunk.countDown()
                    Thread.sleep(150)
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
        val writer = Thread {
            java.io.FileOutputStream(part).use { out ->
                aac.asList().chunked(1500).forEach { chunk ->
                    out.write(chunk.toByteArray())
                    out.flush()
                    entry.grew()
                    started.countDown()
                    Thread.sleep(60)
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
        runMainLooperUntil({ exo.isPlaying }, 10_000, androidx.media3.common.util.Clock.DEFAULT)
        assertFalse("started before the file was complete", entry.done)
        runMainLooperUntil({ exo.playbackState == Player.STATE_ENDED }, 15_000, androidx.media3.common.util.Clock.DEFAULT)
        assertEquals("all 3 s were played", 3000.0, exo.currentPosition.toDouble(), 150.0)
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

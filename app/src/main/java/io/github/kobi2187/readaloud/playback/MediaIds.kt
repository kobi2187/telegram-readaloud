package io.github.kobi2187.readaloud.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import io.github.kobi2187.readaloud.data.ChannelDao
import io.github.kobi2187.readaloud.tts.AudioRenderer
import java.io.IOException

/** Media ids and URIs for queue items. Audio behind the URIs is produced on demand. */
object MediaIds {
    const val ROOT = "root"
    private const val SCHEME = "readaloud"

    sealed interface Parsed
    data class Message(val channel: String, val id: Int) : Parsed
    data class Announce(val channel: String) : Parsed
    data class Notice(val key: String) : Parsed
    data class Channel(val channel: String) : Parsed

    fun message(channel: String, id: Int) = "msg:$channel:$id"
    fun announce(channel: String) = "announce:$channel"
    fun notice(key: String) = "notice:$key"
    fun channel(channel: String) = "channel:$channel"

    fun messageUri(channel: String, id: Int): Uri = "$SCHEME://msg/$channel/$id".toUri()
    fun announceUri(channel: String): Uri = "$SCHEME://announce/$channel".toUri()
    fun noticeUri(key: String): Uri = "$SCHEME://notice/$key".toUri()

    fun parse(mediaId: String?): Parsed? {
        if (mediaId == null) return null
        val parts = mediaId.split(':')
        return when (parts.firstOrNull()) {
            "msg" -> parts.getOrNull(2)?.toIntOrNull()?.let { Message(parts[1], it) }
            "announce" -> parts.getOrNull(1)?.let { Announce(it) }
            "notice" -> parts.getOrNull(1)?.let { Notice(it) }
            "channel" -> parts.getOrNull(1)?.let { Channel(it) }
            else -> null
        }
    }

    /**
     * Resolves readaloud:// URIs to audio files on ExoPlayer's loading thread, rendering them
     * first if needed (neural if online, otherwise the device voice). ExoPlayer preloads the next
     * item while the current one plays, so rendering usually happens ahead of time.
     */
    class Resolver(
        private val renderer: AudioRenderer,
        private val channels: ChannelDao,
        private val noticeText: (String) -> String,
    ) : ResolvingDataSource.Resolver {
        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
            val uri = dataSpec.uri
            if (uri.scheme != SCHEME) return dataSpec
            val segments = uri.pathSegments
            val file = try {
                when (uri.host) {
                    "msg" -> renderer.ensureMessageAudio(segments[0], segments[1].toInt())
                    "announce" -> {
                        val title = channels.getBlocking(segments[0])?.title ?: segments[0]
                        renderer.ensureClip("ch_${segments[0]}", title)
                    }
                    "notice" -> renderer.ensureClip("notice_${segments[0]}", noticeText(segments[0]))
                    else -> throw IOException("Unknown media $uri")
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Could not prepare audio for $uri", e)
            }
            return dataSpec.withUri(Uri.fromFile(file))
        }
    }
}

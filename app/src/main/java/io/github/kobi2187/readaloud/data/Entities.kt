package io.github.kobi2187.readaloud.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val username: String,
    val title: String,
    val description: String? = null,
    val avatarUrl: String? = null,
    val avatarPath: String? = null,
    /** Read posts published at or after this instant (epoch millis). */
    val startDate: Long,
    val sortOrder: Int,
    val enabled: Boolean = true,
    /** First post id at or after [startDate]; null when it must be (re)located. */
    val startId: Int? = null,
    /** Contiguous range of post ids already downloaded: [fetchedFrom, fetchedTo]. 0 = none. */
    val fetchedFrom: Int = 0,
    val fetchedTo: Int = 0,
    /** Newest post id seen on the channel. */
    val latestId: Int = 0,
    /** Next post to play; posts with a smaller id count as heard. 0 = from the start date. */
    val cursorId: Int = 0,
    /** Resume offset inside the post at [cursorId]. */
    val cursorPositionMs: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

object AudioState {
    const val NONE = 0
    /** Neural (Azure) audio downloaded for the voices in [MessageEntity.audioVoiceKey]. */
    const val NEURAL = 1
    /** Rendered offline with the device engine. */
    const val LOCAL = 2
    /** Neural rendering failed for this text; use the device engine. */
    const val NEURAL_FAILED = 3
}

@Entity(
    tableName = "messages",
    primaryKeys = ["channel", "id"],
    indices = [Index(value = ["channel", "date"])],
)
data class MessageEntity(
    val channel: String,
    val id: Int,
    val date: Long,
    val text: String,
    val speech: String,
    val media: String? = null,
    val edited: Boolean = false,
    val forwardedFrom: String? = null,
    val audioState: Int = AudioState.NONE,
    val audioPath: String? = null,
    val audioVoiceKey: String? = null,
    val played: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis(),
)

data class ChannelSummary(
    @Embedded val channel: ChannelEntity,
    /** Posts from the cursor onwards that have something to read. */
    val remaining: Int,
    /** Of those, how many have audio ready for offline playback. */
    val audioReady: Int,
)

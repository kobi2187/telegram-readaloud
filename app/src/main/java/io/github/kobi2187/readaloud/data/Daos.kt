package io.github.kobi2187.readaloud.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query(
        """
        SELECT c.*,
          (SELECT COUNT(*) FROM messages m WHERE m.channel = c.username AND m.id >= c.cursorId
             AND m.date >= c.startDate AND m.speech != '') AS remaining,
          (SELECT COUNT(*) FROM messages m WHERE m.channel = c.username AND m.id >= c.cursorId
             AND m.date >= c.startDate AND m.speech != '' AND m.audioPath IS NOT NULL) AS audioReady
        FROM channels c ORDER BY c.sortOrder, c.addedAt
        """,
    )
    fun observeSummaries(): Flow<List<ChannelSummary>>

    @Query("SELECT * FROM channels ORDER BY sortOrder, addedAt")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE enabled = 1 ORDER BY sortOrder, addedAt")
    suspend fun enabled(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE username = :username")
    suspend fun get(username: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE username = :username")
    fun getBlocking(username: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE username = :username")
    fun observe(username: String): Flow<ChannelEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM channels")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(channel: ChannelEntity): Long

    @Update
    suspend fun update(channel: ChannelEntity)

    @Query("DELETE FROM channels WHERE username = :username")
    suspend fun delete(username: String)

    @Query("UPDATE channels SET cursorId = :cursorId, cursorPositionMs = :positionMs WHERE username = :username")
    suspend fun setCursor(username: String, cursorId: Int, positionMs: Long)

    @Query("UPDATE channels SET lastError = :error, lastSyncAt = :at WHERE username = :username")
    suspend fun setSyncResult(username: String, error: String?, at: Long)

    @Query("UPDATE channels SET sortOrder = :order WHERE username = :username")
    suspend fun setSortOrder(username: String, order: Int)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE channel = :channel AND date >= :startDate ORDER BY id")
    fun observeChannel(channel: String, startDate: Long): Flow<List<MessageEntity>>

    @Query(
        """SELECT * FROM messages WHERE channel = :channel AND id >= :fromId AND date >= :startDate
           AND speech != '' ORDER BY id LIMIT :limit""",
    )
    suspend fun queue(channel: String, fromId: Int, startDate: Long, limit: Int): List<MessageEntity>

    @Query(
        """SELECT COUNT(*) FROM messages WHERE channel = :channel AND id >= :fromId AND date >= :startDate
           AND speech != ''""",
    )
    suspend fun countFrom(channel: String, fromId: Int, startDate: Long): Int

    @Query("SELECT * FROM messages WHERE channel = :channel AND id = :id")
    suspend fun get(channel: String, id: Int): MessageEntity?

    @Query("SELECT * FROM messages WHERE channel = :channel AND id = :id")
    fun getBlocking(channel: String, id: Int): MessageEntity?

    @Query("SELECT MAX(id) FROM messages WHERE channel = :channel")
    fun observeMaxId(channel: String): Flow<Int?>

    @Query("SELECT * FROM messages WHERE channel = :channel AND id IN (:ids)")
    suspend fun byIds(channel: String, ids: List<Int>): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("UPDATE messages SET text = :text, speech = :speech, media = :media, edited = 1, audioState = 0, audioPath = NULL, audioVoiceKey = NULL WHERE channel = :channel AND id = :id")
    suspend fun updateText(channel: String, id: Int, text: String, speech: String, media: String?)

    @Query("UPDATE messages SET audioState = :state, audioPath = :path, audioVoiceKey = :voiceKey WHERE channel = :channel AND id = :id")
    fun setAudioBlocking(channel: String, id: Int, state: Int, path: String?, voiceKey: String?)

    @Query("UPDATE messages SET played = 1 WHERE channel = :channel AND id = :id")
    suspend fun markPlayed(channel: String, id: Int)

    @Query("UPDATE messages SET played = 1 WHERE channel = :channel AND id < :beforeId")
    suspend fun markPlayedBefore(channel: String, beforeId: Int)

    @Query("UPDATE messages SET played = 0 WHERE channel = :channel")
    suspend fun markAllUnplayed(channel: String)

    @Query(
        """SELECT * FROM messages WHERE audioPath IS NOT NULL AND played = 1
           AND channel = :channel AND id < :beforeId ORDER BY id DESC LIMIT -1 OFFSET :keep""",
    )
    suspend fun playedWithAudio(channel: String, beforeId: Int, keep: Int): List<MessageEntity>

    @Query("SELECT audioPath FROM messages WHERE audioPath IS NOT NULL")
    suspend fun allAudioPaths(): List<String>

    @Query("UPDATE messages SET audioState = 0, audioPath = NULL, audioVoiceKey = NULL")
    suspend fun clearAllAudio()

    @Query("DELETE FROM messages WHERE channel = :channel")
    suspend fun deleteChannel(channel: String)

    @Transaction
    suspend fun upsertFromWeb(incoming: List<MessageEntity>) {
        if (incoming.isEmpty()) return
        val channel = incoming.first().channel
        val existing = byIds(channel, incoming.map { it.id }).associateBy { it.id }
        val fresh = incoming.filter { it.id !in existing }
        insertAll(fresh)
        for (m in incoming) {
            val old = existing[m.id] ?: continue
            if (old.text != m.text || old.speech != m.speech) updateText(channel, m.id, m.text, m.speech, m.media)
        }
    }
}

package io.github.kobi2187.readaloud.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaosTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun msg(id: Int, date: Long, speech: String = "post $id", audio: String? = null, played: Boolean = false) =
        MessageEntity("ch", id, date, "text $id", speech, audioPath = audio, played = played)

    @Test
    fun summaryCountsFromCursorAndStartDate() = runTest {
        db.channels().insert(ChannelEntity(username = "ch", title = "Ch", startDate = 100, sortOrder = 0, cursorId = 3))
        db.messages().insertAll(
            listOf(
                msg(1, 50), // before start date
                msg(2, 150), // before cursor
                msg(3, 160, audio = "/a/3.mp3"),
                msg(4, 170, speech = ""), // nothing to read
                msg(5, 180),
            ),
        )
        val s = db.channels().observeSummaries().first().single()
        assertEquals(2, s.remaining)
        assertEquals(1, s.audioReady)
        assertEquals(listOf(3, 5), db.messages().queue("ch", 3, 100, 10).map { it.id })
    }

    @Test
    fun editedPostsResetTheirAudio() = runTest {
        db.messages().insertAll(listOf(msg(1, 10, audio = "/a/1.mp3")))
        db.messages().upsertFromWeb(listOf(msg(1, 10, speech = "changed"), msg(2, 20)))
        val edited = db.messages().get("ch", 1)!!
        assertEquals("changed", edited.speech)
        assertEquals(null, edited.audioPath)
        assertTrue(edited.edited)
        assertEquals(2, db.messages().byIds("ch", listOf(1, 2)).size)
    }

    @Test
    fun playedAudioCleanupKeepsTheLatestFew() = runTest {
        db.messages().insertAll((1..10).map { msg(it, it.toLong(), audio = "/a/$it.mp3", played = true) })
        val toDelete = db.messages().playedWithAudio("ch", beforeId = 9, keep = 3).map { it.id }
        assertEquals(listOf(5, 4, 3, 2, 1), toDelete) // 8, 7, 6 kept for "previous"
    }
}

package io.github.kobi2187.readaloud.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Upgrading from 1.2 keeps channels, posts and audio. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun version1DataSurvivesTheUpgrade() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile?.mkdirs(); delete() }
        val schema = Json.parseToJsonElement(File("schemas/io.github.kobi2187.readaloud.data.AppDatabase/1.json").readText()).jsonObject
        val database = schema.getValue("database").jsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (entity in database.getValue("entities").jsonArray) {
                val e = entity.jsonObject
                val table = e.getValue("tableName").jsonPrimitive.content
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                e["indices"]?.jsonArray?.forEach { i ->
                    db.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            database.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = 1
            db.execSQL("INSERT INTO channels (username, title, startDate, sortOrder, enabled, fetchedFrom, fetchedTo, latestId, cursorId, cursorPositionMs, lastSyncAt, addedAt) VALUES ('kann_news', 'כאן חדשות', 0, 0, 1, 1, 10, 10, 5, 1200, 0, 0)")
            db.execSQL("INSERT INTO messages (channel, id, date, text, speech, edited, audioState, audioPath, played, fetchedAt) VALUES ('kann_news', 5, 1000, 'טקסט', 'טקסט.', 0, 1, '/a/5.mp3', 0, 0)")
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, file.path).addMigrations(AppDatabase.MIGRATION_1_2).build()
        val ch = room.channels().get("kann_news")!!
        assertEquals(ChannelKind.TELEGRAM, ch.kind)
        assertEquals(5, ch.cursorId)
        assertNull(ch.url)
        val m = room.messages().get("kann_news", 5)!!
        assertEquals("/a/5.mp3", m.audioPath)
        assertNull(m.link)
        room.close()
    }
}

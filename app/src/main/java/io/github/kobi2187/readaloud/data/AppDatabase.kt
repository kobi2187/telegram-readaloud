package io.github.kobi2187.readaloud.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChannelEntity::class, MessageEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channels(): ChannelDao
    abstract fun messages(): MessageDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "readaloud.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()

        /** 1.3: website and saved-link channels. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE channels ADD COLUMN kind TEXT NOT NULL DEFAULT 'telegram'")
                db.execSQL("ALTER TABLE channels ADD COLUMN url TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN link TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN guid TEXT")
            }
        }
    }
}

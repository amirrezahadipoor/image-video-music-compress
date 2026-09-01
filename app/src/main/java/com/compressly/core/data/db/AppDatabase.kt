package com.compressly.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [HistoryEntry::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "compressly.db"
                )
                    // MIGRATION-FIX: no fallbackToDestructiveMigration(). The
                    // first future schema change must ship a real Migration,
                    // or the app should fail loudly on upgrade — silently
                    // wiping the user's whole history is not acceptable.
                    .build()
                    .also { instance = it }
            }
    }
}

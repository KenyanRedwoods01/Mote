package com.mote.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocalModelEntity::class,
        ModelStudioPrefsEntity::class,
        ModelBenchmarkEntity::class,
        ClockAlarmEntity::class,
        CalendarEventEntity::class,
        ToolExecutionLogEntity::class,
        SavedNoteEntity::class,
        ToolChainRunEntity::class,
        ToolChainStateEntity::class
    ],
    version = 11,
    exportSchema = false
)
abstract class LocalModelDatabase : RoomDatabase() {
    abstract fun localModelDao(): LocalModelDao

    companion object {
        @Volatile
        private var INSTANCE: LocalModelDatabase? = null

        fun getInstance(context: Context): LocalModelDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalModelDatabase::class.java,
                    "mote_local_models.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

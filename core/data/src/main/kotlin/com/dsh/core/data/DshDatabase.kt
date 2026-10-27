package com.dsh.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AppSettingsEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class DshDatabase : RoomDatabase() {
    abstract fun dshDao(): DshDao
}


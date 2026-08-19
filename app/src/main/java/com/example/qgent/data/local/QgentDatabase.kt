package com.example.qgent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class], version = 8, exportSchema = false)
abstract class QgentDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: QgentDatabase? = null

        fun getInstance(context: Context): QgentDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QgentDatabase::class.java,
                    "qgent.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}

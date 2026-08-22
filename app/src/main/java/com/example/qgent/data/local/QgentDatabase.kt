package com.example.qgent.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MessageEntity::class], version = 10, exportSchema = false)
abstract class QgentDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: QgentDatabase? = null

        /**
         * v8 曾在未升版本时分批新增任务卡片缓存字段，已安装设备可能只缺其中一列，
         * 也可能两列都已存在但 identity hash 仍为旧值；迁移必须按实际表结构补齐。
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                if (!hasColumn(database, "repositoryMappingsJson")) {
                    database.execSQL("ALTER TABLE chat_message ADD COLUMN repositoryMappingsJson TEXT")
                }
                if (!hasColumn(database, "currentRepositoryPathsJson")) {
                    database.execSQL("ALTER TABLE chat_message ADD COLUMN currentRepositoryPathsJson TEXT")
                }
            }
        }

        /** v9→v10：新增 DIFF 卡驳回/拒绝意见 reviewReason 列（diff 拒绝后回群引用续作） */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                if (!hasColumn(database, "reviewReason")) {
                    database.execSQL("ALTER TABLE chat_message ADD COLUMN reviewReason TEXT")
                }
            }
        }

        private fun hasColumn(database: SupportSQLiteDatabase, columnName: String): Boolean {
            var found = false
            database.query("PRAGMA table_info(chat_message)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == columnName) {
                        found = true
                        break
                    }
                }
            }
            return found
        }

        fun getInstance(context: Context): QgentDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    QgentDatabase::class.java,
                    "qgent.db"
                )
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

package com.example.qgent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MessageDao {

    @Query("SELECT * FROM chat_message WHERE groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getByGroup(groupId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM chat_message WHERE groupId = :groupId")
    suspend fun clearByGroup(groupId: String)
}

package com.example.qgent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MessageDao {

    @Query("SELECT * FROM chat_message WHERE groupId = :groupId ORDER BY timestamp ASC")
    suspend fun getByGroup(groupId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("DELETE FROM chat_message WHERE groupId = :groupId")
    suspend fun clearByGroup(groupId: String)

    /** 清理该群不在最新列表中的残留消息（后端已删除/本地过滤掉的），避免只增量插导致缓存残留过期消息 */
    @Query("DELETE FROM chat_message WHERE groupId = :groupId AND id NOT IN (:keepIds)")
    suspend fun deleteNotIn(groupId: String, keepIds: List<String>)

    /** 原子替换某群缓存：删除残留 + 按 id 覆盖（新增/更新）。空列表时清空该群。 */
    @Transaction
    suspend fun replaceGroupMessages(groupId: String, messages: List<MessageEntity>) {
        if (messages.isEmpty()) {
            clearByGroup(groupId)
            return
        }
        deleteNotIn(groupId, messages.map { it.id })
        insertAll(messages)
    }
}

package com.example.qgent.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chat_message", indices = [Index("groupId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val senderName: String,
    val content: String,
    val type: String,
    val timestamp: Long,
    val isMine: Boolean,
    val fileName: String?,
    val fileSize: Long?
)

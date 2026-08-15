package com.example.qgent.data.local

import com.example.qgent.model.ChatMessage
import com.example.qgent.model.MessageType

class MessageCache(private val dao: MessageDao) {

    suspend fun load(groupId: String): List<ChatMessage> =
        dao.getByGroup(groupId).map { it.toChatMessage() }

    suspend fun save(groupId: String, messages: List<ChatMessage>) {
        dao.clearByGroup(groupId)
        dao.insertAll(messages.map { it.toEntity(groupId) })
    }

    private fun MessageEntity.toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        senderName = senderName,
        content = content,
        type = runCatching { MessageType.valueOf(type) }.getOrDefault(MessageType.TEXT),
        timestamp = timestamp,
        isMine = isMine,
        fileName = fileName,
        fileSize = fileSize
    )

    private fun ChatMessage.toEntity(groupId: String): MessageEntity = MessageEntity(
        id = id,
        groupId = groupId,
        senderName = senderName,
        content = content,
        type = type.name,
        timestamp = timestamp,
        isMine = isMine,
        fileName = fileName,
        fileSize = fileSize
    )
}

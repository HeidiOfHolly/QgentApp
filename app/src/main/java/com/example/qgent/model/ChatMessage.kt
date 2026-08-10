package com.example.qgent.model

enum class MessageType { TEXT, IMAGE, FILE }

data class ChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val type: MessageType,
    val timestamp: Long,
    val isMine: Boolean
) {
    fun displayContent(): String = when (type) {
        MessageType.TEXT -> content
        MessageType.IMAGE -> "📷 图片（占位）"
        MessageType.FILE -> "📎 文件（占位）"
    }
}

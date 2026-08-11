package com.example.qgent.model

enum class MessageType { TEXT, CODE, IMAGE, FILE, SYSTEM, QUOTE }

data class ChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val type: MessageType,
    val timestamp: Long,
    val isMine: Boolean
) {
    fun displayContent(): String = when (type) {
        MessageType.TEXT, MessageType.CODE -> content
        MessageType.IMAGE -> "[图片]"
        MessageType.FILE -> "[文件]"
        MessageType.SYSTEM -> content
        MessageType.QUOTE -> "[引用消息]"
    }
}

package com.example.qgent.model

enum class MessageType { TEXT, CODE, IMAGE, FILE, SYSTEM, QUOTE, DIFF }

data class ChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val type: MessageType,
    val timestamp: Long,
    val isMine: Boolean,
    val diff: List<DiffFile>? = null
) {
    fun displayContent(): String = when (type) {
        MessageType.TEXT, MessageType.CODE -> content
        MessageType.IMAGE -> "[图片]"
        MessageType.FILE -> content.ifBlank { "[文件]" }
        MessageType.SYSTEM -> content
        MessageType.QUOTE -> "[引用消息]"
        MessageType.DIFF -> "[代码变更]"
    }
}

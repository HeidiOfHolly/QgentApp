package com.example.qgent.model

enum class MessageType { TEXT, CODE, IMAGE, FILE, SYSTEM, QUOTE, DIFF, TASK_STATUS }

data class ChatMessage(
    val id: String,
    val senderName: String,
    val content: String,
    val type: MessageType,
    val timestamp: Long,
    val isMine: Boolean,
    val diff: List<DiffFile>? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val sequence: Long = 0,
    /** 引用消息：被引用消息 id + 展示摘要（非空表示本条为 QUOTE） */
    val replyToId: String? = null,
    val replyToSummary: String? = null
) {
    fun displayContent(): String = when (type) {
        MessageType.TEXT, MessageType.CODE -> content
        MessageType.IMAGE -> "[图片]"
        MessageType.FILE -> fileName ?: "[文件]"
        MessageType.SYSTEM -> content
        MessageType.QUOTE -> replyToSummary?.let { "引用：$it" } ?: "[引用消息]"
        MessageType.DIFF -> "[代码变更]"
        MessageType.TASK_STATUS -> content.ifBlank { "[任务状态] " }
    }
}

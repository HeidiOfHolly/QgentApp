package com.example.qgent.model

enum class MessageType { TEXT, CODE, IMAGE, FILE, SYSTEM, QUOTE, DIFF, TASK_STATUS }

/** 消息发送状态：SENDING 显示小加载标，FAILED 显示红色感叹号（可点击重发/删除）；null 表示已发送成功或无状态 */
enum class SendState { SENDING, FAILED }

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
    val replyToSummary: String? = null,
    /** 发送者类型：USER / AGENT / SYSTEM（文档 §7；Agent 消息用于视觉区分） */
    val senderType: String? = null,
    /** TASK_STATUS 卡片：状态 + 执行节点 + 关联任务 id（文档 §7 content 含 taskId/status/node/message） */
    val taskId: String? = null,
    val taskStatus: String? = null,
    val taskNode: String? = null,
    /** DIFF 卡片：content 含 diffId，展示时用 DiffRepository 拉取文件内容 */
    val diffId: String? = null,
    /** 发送状态（仅自己发送的消息有效）：发送中 / 失败 */
    val sendState: SendState? = null,
    /** 发送失败原因（后端错误码/文案，仅 FAILED 时可能非空，用于重发弹窗展示） */
    val sendError: String? = null
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

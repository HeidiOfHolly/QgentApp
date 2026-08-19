package com.example.qgent.model

/** 群类型：项目总群 / 需求群（文档 §7 统一建模 PROJECT_MAIN + REQUIREMENT） */
enum class GroupType { PROJECT_MAIN, REQUIREMENT }

data class ChatGroup(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    val unread: Int,
    val isPinned: Boolean = false,
    val lastActiveTime: Long = System.currentTimeMillis(),
    val mentionedMe: Boolean = false,
    /** 群类型：项目总群（PROJECT_MAIN）恒置顶并标识；其余为需求群 */
    val type: GroupType = GroupType.REQUIREMENT,
    /** 最新消息类型（TEXT/IMAGE/SYSTEM/QUOTE/...），通知过滤 SYSTEM 用 */
    val lastMessageType: String? = null
)

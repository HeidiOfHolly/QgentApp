package com.example.qgent.model

data class ChatGroup(
    val id: String,
    val name: String,
    val lastMessage: String,
    val time: String,
    val unread: Int,
    val isPinned: Boolean = false,
    val lastActiveTime: Long = System.currentTimeMillis(),
    val mentionedMe: Boolean = false
)

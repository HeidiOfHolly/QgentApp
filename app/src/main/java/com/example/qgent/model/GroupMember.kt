package com.example.qgent.model

enum class MemberType { HUMAN, AGENT }

data class GroupMember(
    val id: String,
    val name: String,
    val type: MemberType = MemberType.HUMAN,
    /** 成员头像 URL（后端 GroupMemberDto.avatar，用于消息气泡旁展示） */
    val avatar: String? = null
)

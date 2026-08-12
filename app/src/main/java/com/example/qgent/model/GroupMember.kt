package com.example.qgent.model

enum class MemberType { HUMAN, AGENT }

data class GroupMember(
    val name: String,
    val type: MemberType = MemberType.HUMAN
)

package com.example.qgent.data.repository

import com.example.qgent.data.model.GroupDto
import com.example.qgent.data.model.GroupMemberDto
import com.example.qgent.data.model.GroupMessageDto

interface ChatRepository {
    suspend fun getGroups(projectId: String, cursor: String? = null, limit: Int = 30): Result<List<GroupDto>>
    suspend fun createGroup(projectId: String, title: String, description: String? = null): Result<GroupDto>
    suspend fun getGroup(projectId: String, groupId: String): Result<GroupDto>
    suspend fun updateGroup(projectId: String, groupId: String, title: String? = null, description: String? = null): Result<GroupDto>
    suspend fun archiveGroup(projectId: String, groupId: String): Result<GroupDto>
    suspend fun getMembers(projectId: String, groupId: String): Result<List<GroupMemberDto>>
    suspend fun leaveGroup(projectId: String, groupId: String): Result<Unit>
    suspend fun getMessages(projectId: String, groupId: String, cursor: String? = null, limit: Int = 30): Result<List<GroupMessageDto>>
    suspend fun sendMessage(projectId: String, groupId: String, text: String, type: String = "TEXT", clientMessageId: String? = null): Result<GroupMessageDto>
}
